package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provisions an {@code AWS::SNS::TopicPolicy} over two topics the same stack creates, with the
 * topic ARNs referenced inside the document the way CDK emits them. Asserts that both topics carry
 * the resolved policy after create, that {@code Ref} and {@code Fn::GetAtt Id} agree and survive an
 * update that rewrites the document, and that deleting the stack takes the topics with it.
 */
@QuarkusTest
class SnsTopicPolicyCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/cloudformation/aws4_request";
    private static final String SNS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/sns/aws4_request";
    private static final String STACK = "sns-topic-policy-cfn-it";

    private static final String TEMPLATE = """
        {
          "Parameters": {"Sid": {"Type": "String"}},
          "Resources": {
            "TopicA": {"Type": "AWS::SNS::Topic", "Properties": {"TopicName": "sns-topic-policy-cfn-it-a"}},
            "TopicB": {"Type": "AWS::SNS::Topic", "Properties": {"TopicName": "sns-topic-policy-cfn-it-b"}},
            "Policy": {
              "Type": "AWS::SNS::TopicPolicy",
              "Properties": {
                "Topics": [{"Ref": "TopicA"}, {"Ref": "TopicB"}],
                "PolicyDocument": {
                  "Version": "2012-10-17",
                  "Statement": [{
                    "Sid": {"Ref": "Sid"},
                    "Effect": "Allow",
                    "Principal": {"AWS": "*"},
                    "Action": "sns:Publish",
                    "Resource": [{"Ref": "TopicA"}, {"Ref": "TopicB"}]
                  }]
                }
              }
            }
          },
          "Outputs": {
            "PolicyRef": {"Value": {"Ref": "Policy"}},
            "PolicyId": {"Value": {"Fn::GetAtt": ["Policy", "Id"]}},
            "TopicA": {"Value": {"Ref": "TopicA"}},
            "TopicB": {"Value": {"Ref": "TopicB"}}
          }
        }
        """;

    @Test
    void createUpdateAndDeleteATopicPolicy() throws InterruptedException {
        cloudFormation("CreateStack", Map.of("Sid", "AllowPublishV1"));
        String created = describeStacks("CREATE_COMPLETE");
        String policyId = outputValue(created, "PolicyRef");
        String topicA = outputValue(created, "TopicA");
        String topicB = outputValue(created, "TopicB");

        // Ref and Fn::GetAtt Id agree, and the id is the synthetic one a policy without an entity gets.
        assertEquals(policyId, outputValue(created, "PolicyId"));
        assertTrue(policyId.startsWith("topic-policy-"), policyId);

        // Both topics carry the document, with the Ref inside Resource resolved to the ARN.
        for (String topicArn : new String[] {topicA, topicB}) {
            topicAttributes(topicArn)
                .body(containsString("<key>Policy</key>"))
                .body(containsString("AllowPublishV1"))
                .body(containsString(topicA))
                .body(containsString(topicB));
        }

        cloudFormation("UpdateStack", Map.of("Sid", "AllowPublishV2"));
        String updated = describeStacks("UPDATE_COMPLETE");

        // The document is rewritten on the same topics under the same id.
        assertEquals(policyId, outputValue(updated, "PolicyRef"));
        assertEquals(topicA, outputValue(updated, "TopicA"));
        topicAttributes(topicA)
            .body(containsString("AllowPublishV2"))
            .body(not(containsString("AllowPublishV1")));
        topicAttributes(topicB).body(containsString("AllowPublishV2"));

        cloudFormation("DeleteStack", Map.of());
        awaitStackDeleted();

        topicAttributes(topicA).statusCode(404);
        topicAttributes(topicB).statusCode(404);
    }

    private static void cloudFormation(String action, Map<String, String> parameters) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", STACK);
        if (!"DeleteStack".equals(action)) {
            request.formParam("TemplateBody", TEMPLATE);
        }
        int index = 1;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            request.formParam("Parameters.member." + index + ".ParameterKey", parameter.getKey());
            request.formParam("Parameters.member." + index + ".ParameterValue", parameter.getValue());
            index++;
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", STACK)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    /** DeleteStack runs asynchronously; a successful delete removes the stack entirely. */
    private static void awaitStackDeleted() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", STACK)
            .when().post("/").then().extract().asString();
            if (body.contains("does not exist")) {
                return;
            }
            if (body.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                fail("stack delete failed: " + body);
            }
            Thread.sleep(50);
        }
        fail("stack " + STACK + " was not deleted within the timeout");
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static ValidatableResponse topicAttributes(String topicArn) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", SNS_AUTH)
            .formParam("Action", "GetTopicAttributes")
            .formParam("TopicArn", topicArn)
        .when().post("/").then();
    }
}
