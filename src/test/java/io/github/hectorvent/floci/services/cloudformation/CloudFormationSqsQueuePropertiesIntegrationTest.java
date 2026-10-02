package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code AWS::SQS::Queue} properties end to end through the real SQS service: every mutable property
 * the template declares reaches the queue, and an update that drops one puts it back to its default,
 * because CloudFormation applies the whole template as the desired state.
 */
@QuarkusTest
class CloudFormationSqsQueuePropertiesIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String STACK = "cfn-sqs-queue-properties";
    private static final String NO_VALUE_STACK = "cfn-sqs-queue-no-value";

    @Test
    void declaredPropertiesReachTheQueueAndDroppedOnesReturnToTheirDefaults() {
        String declared = """
                {
                  "Resources": {
                    "Queue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {
                        "VisibilityTimeout": 60,
                        "DelaySeconds": 5,
                        "MessageRetentionPeriod": 86400,
                        "ReceiveMessageWaitTimeSeconds": 10,
                        "RedriveAllowPolicy": {"redrivePermission": "denyAll"}
                      }
                    }
                  },
                  "Outputs": {"QueueUrl": {"Value": {"Ref": "Queue"}}}
                }
                """;
        cloudFormation(STACK, "CreateStack", declared);
        String queueUrl = XmlParser.extractPairs(describeStacks(STACK, "CREATE_COMPLETE"), "Outputs", "OutputKey",
                "OutputValue").get("QueueUrl");

        Map<String, String> created = queueAttributes(queueUrl);
        assertEquals("60", created.get("VisibilityTimeout"));
        assertEquals("5", created.get("DelaySeconds"));
        assertEquals("86400", created.get("MessageRetentionPeriod"));
        assertEquals("10", created.get("ReceiveMessageWaitTimeSeconds"));
        assertEquals("{\"redrivePermission\":\"denyAll\"}", created.get("RedriveAllowPolicy"));

        String dropped = """
                {
                  "Resources": {
                    "Queue": {"Type": "AWS::SQS::Queue", "Properties": {"VisibilityTimeout": 45}}
                  },
                  "Outputs": {"QueueUrl": {"Value": {"Ref": "Queue"}}}
                }
                """;
        cloudFormation(STACK, "UpdateStack", dropped);
        describeStacks(STACK, "UPDATE_COMPLETE");

        Map<String, String> updated = queueAttributes(queueUrl);
        assertEquals("45", updated.get("VisibilityTimeout"));
        assertEquals("0", updated.get("DelaySeconds"));
        assertEquals("345600", updated.get("MessageRetentionPeriod"));
        assertEquals("0", updated.get("ReceiveMessageWaitTimeSeconds"));
        assertFalse(updated.containsKey("RedriveAllowPolicy"), "a dropped RedriveAllowPolicy is removed");

        cloudFormation(STACK, "DeleteStack", null);
        await().untilAsserted(() -> given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueUrl")
                .formParam("QueueName", queueUrl.substring(queueUrl.lastIndexOf('/') + 1))
            .when().post("/").then().statusCode(400));
    }

    @Test
    void aPropertyAConditionDropsWithNoValueKeepsItsDefault() {
        String template = """
                {
                  "Parameters": {"LongRetention": {"Type": "String", "Default": "%s"}},
                  "Conditions": {"Long": {"Fn::Equals": [{"Ref": "LongRetention"}, "yes"]}},
                  "Resources": {
                    "Queue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {
                        "MessageRetentionPeriod": {"Fn::If": ["Long", 1209600, {"Ref": "AWS::NoValue"}]},
                        "RedriveAllowPolicy": {"Fn::If": ["Long", {"redrivePermission": "denyAll"},
                                {"Ref": "AWS::NoValue"}]}
                      }
                    }
                  },
                  "Outputs": {"QueueUrl": {"Value": {"Ref": "Queue"}}}
                }
                """;
        cloudFormation(NO_VALUE_STACK, "CreateStack", template.formatted("no"));
        String queueUrl = XmlParser.extractPairs(describeStacks(NO_VALUE_STACK, "CREATE_COMPLETE"), "Outputs",
                "OutputKey", "OutputValue").get("QueueUrl");
        assertEquals("345600", queueAttributes(queueUrl).get("MessageRetentionPeriod"));
        assertFalse(queueAttributes(queueUrl).containsKey("RedriveAllowPolicy"));

        cloudFormation(NO_VALUE_STACK, "UpdateStack", template.formatted("yes"));
        describeStacks(NO_VALUE_STACK, "UPDATE_COMPLETE");
        assertEquals("1209600", queueAttributes(queueUrl).get("MessageRetentionPeriod"));

        cloudFormation(NO_VALUE_STACK, "UpdateStack", template.formatted("no"));
        describeStacks(NO_VALUE_STACK, "UPDATE_COMPLETE");
        assertEquals("345600", queueAttributes(queueUrl).get("MessageRetentionPeriod"));
        assertFalse(queueAttributes(queueUrl).containsKey("RedriveAllowPolicy"));

        cloudFormation(NO_VALUE_STACK, "DeleteStack", null);
    }

    private static void cloudFormation(String stack, String action, String templateBody) {
        if (templateBody == null) {
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", stack)
            .when().post("/").then().statusCode(200);
            return;
        }
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stack)
            .formParam("TemplateBody", templateBody)
        .when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String stack, String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    private static Map<String, String> queueAttributes(String queueUrl) {
        String xml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", queueUrl)
            .formParam("AttributeName.1", "All")
        .when().post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "Attribute", "Name", "Value");
    }
}
