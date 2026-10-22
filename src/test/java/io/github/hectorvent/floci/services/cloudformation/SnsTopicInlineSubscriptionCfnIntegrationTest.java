package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A topic that subscribes a queue through its own {@code Subscription} list, the shape the
 * Serverless Framework emits for an SNS fan-out: the subscription exists and delivers, and an
 * UpdateStack that drops it from the list removes it.
 */
@QuarkusTest
class SnsTopicInlineSubscriptionCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/cloudformation/aws4_request";
    private static final String SNS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/sns/aws4_request";
    private static final String SQS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/sqs/aws4_request";
    private static final String STACK = "sns-inline-subscription-cfn-it";

    private static final String SUBSCRIBED = """
        {
          "Resources": {
            "Queue": {
              "Type": "AWS::SQS::Queue",
              "Properties": {"QueueName": "sns-inline-subscription-cfn-it"}
            },
            "Topic": {
              "Type": "AWS::SNS::Topic",
              "Properties": {
                "TopicName": "sns-inline-subscription-cfn-it",
                "Subscription": [
                  {"Protocol": "sqs", "Endpoint": {"Fn::GetAtt": ["Queue", "Arn"]}}
                ]
              }
            }
          },
          "Outputs": {
            "TopicArn": {"Value": {"Ref": "Topic"}},
            "QueueUrl": {"Value": {"Ref": "Queue"}}
          }
        }
        """;

    private static final String UNSUBSCRIBED = """
        {
          "Resources": {
            "Queue": {
              "Type": "AWS::SQS::Queue",
              "Properties": {"QueueName": "sns-inline-subscription-cfn-it"}
            },
            "Topic": {
              "Type": "AWS::SNS::Topic",
              "Properties": {"TopicName": "sns-inline-subscription-cfn-it"}
            }
          },
          "Outputs": {
            "TopicArn": {"Value": {"Ref": "Topic"}},
            "QueueUrl": {"Value": {"Ref": "Queue"}}
          }
        }
        """;

    @Test
    void aTopicsInlineSubscriptionIsCreatedDeliversAndIsRemovedWhenDropped() {
        cloudFormation("CreateStack", SUBSCRIBED);
        String created = describeStacks("CREATE_COMPLETE");
        String topicArn = outputValue(created, "TopicArn");
        String queueUrl = outputValue(created, "QueueUrl");

        String subscriptions = subscriptionsByTopic(topicArn);
        assertTrue(subscriptions.contains("<Protocol>sqs</Protocol>")
                        && subscriptions.contains(":sns-inline-subscription-cfn-it</Endpoint>"),
                "the topic's Subscription list has to create the subscription: " + subscriptions);
        publish(topicArn, "inline-subscription-delivery");
        assertTrue(receive(queueUrl).contains("inline-subscription-delivery"),
                "a message published to the topic must reach the subscribed queue");

        cloudFormation("UpdateStack", UNSUBSCRIBED);
        describeStacks("UPDATE_COMPLETE");
        assertFalse(subscriptionsByTopic(topicArn).contains("<SubscriptionArn>"),
                "dropping the entry from the list must unsubscribe it");

        cloudFormation("DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(STACK);
    }

    private static void cloudFormation(String action, String template) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", STACK);
        if (template != null) {
            request.formParam("TemplateBody", template);
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

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static String subscriptionsByTopic(String topicArn) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", SNS_AUTH)
            .formParam("Action", "ListSubscriptionsByTopic")
            .formParam("TopicArn", topicArn)
        .when().post("/").then().statusCode(200).extract().asString();
    }

    private static void publish(String topicArn, String message) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", SNS_AUTH)
            .formParam("Action", "Publish")
            .formParam("TopicArn", topicArn)
            .formParam("Message", message)
        .when().post("/").then().statusCode(200);
    }

    private static String receive(String queueUrl) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", SQS_AUTH)
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "10")
            .formParam("WaitTimeSeconds", "1")
        .when().post("/").then().statusCode(200).extract().asString();
    }
}
