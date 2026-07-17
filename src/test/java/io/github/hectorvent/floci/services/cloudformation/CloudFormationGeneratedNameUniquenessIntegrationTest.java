package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies auto-generated physical resource names stay unique when a stack's name approaches the
 * resource name-length limit.
 *
 * <p>Two SQS queues in a stack whose name is longer than the 80-char queue-name cap must still get
 * distinct generated names: CloudFormation keeps the random uniqueness suffix when it truncates a
 * generated name. Previously the whole {@code stackName-logicalId-suffix} string was truncated to the
 * cap, which dropped the suffix, so every auto-named resource in such a stack collapsed onto a single
 * name. That broke Ref/GetAtt-based lookup — most visibly a {@code Custom::LogRetention} whose
 * {@code ServiceToken} (GetAtt of its provider Lambda's Arn) resolved to a different, colliding
 * function, so the invoke ran the wrong handler and no response was ever PUT to the ResponseURL.
 *
 * <p>All resources are metadata-only (no container), so the test is Docker-free.
 */
@QuarkusTest
class CloudFormationGeneratedNameUniquenessIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private void createStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);
    }

    private String describeStacks(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200).extract().asString();
    }

    private void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200);
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static String queueName(String queueUrl) {
        String path = URI.create(queueUrl).getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Test
    void generatedNamesStayUniqueWhenStackNameNearsLengthLimit() {
        // A stack name longer than the 80-char SQS queue-name cap: after truncation the descriptive
        // prefix is identical for both queues, so only a preserved suffix can keep them distinct.
        String uniq = Long.toString(System.nanoTime(), 36);
        String stackName = "gen-name-uniqueness-stack-with-a-deliberately-very-long-name-"
                + "padding-padding-padding-padding-" + uniq;

        String template = """
                {
                  "Resources": {
                    "QueueOne": { "Type": "AWS::SQS::Queue" },
                    "QueueTwo": { "Type": "AWS::SQS::Queue" }
                  },
                  "Outputs": {
                    "QOne": { "Value": { "Ref": "QueueOne" } },
                    "QTwo": { "Value": { "Ref": "QueueTwo" } }
                  }
                }
                """;

        createStack(stackName, template);
        try {
            String xml = describeStacks(stackName);
            assertTrue(xml.contains("<StackStatus>CREATE_COMPLETE</StackStatus>"),
                    "stack should be CREATE_COMPLETE");

            String qOne = outputValue(xml, "QOne");
            String qTwo = outputValue(xml, "QTwo");
            assertTrue(qOne != null && !qOne.isBlank(), "QOne ref should be present");
            assertTrue(qTwo != null && !qTwo.isBlank(), "QTwo ref should be present");
            String qOneName = queueName(qOne);
            String qTwoName = queueName(qTwo);
            assertTrue(qOneName.length() <= 80, "QOne must respect the SQS queue-name limit");
            assertTrue(qTwoName.length() <= 80, "QTwo must respect the SQS queue-name limit");
            assertTrue(qOneName.matches(".*-[0-9a-f]{12}$"), "QOne must retain its 12-hex uniqueness suffix");
            assertTrue(qTwoName.matches(".*-[0-9a-f]{12}$"), "QTwo must retain its 12-hex uniqueness suffix");
            assertNotEquals(qOne, qTwo,
                    "two auto-named queues in a long-named stack must get distinct names (unique suffix preserved)");
        } finally {
            deleteStack(stackName);
        }
    }
}
