package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A FIFO topic fanning out to a FIFO queue, the shape a template takes to publish one event per
 * message group: the scope reaches the topic, per-group dedup holds end to end, and an UpdateStack
 * back to {@code Topic} takes effect on the topic that already exists.
 */
@QuarkusTest
class SnsFifoThroughputScopeCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/cloudformation/aws4_request";
    private static final String SNS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/sns/aws4_request";
    private static final String SQS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/sqs/aws4_request";
    private static final String STACK = "sns-fifo-scope-cfn-it";

    /** Per-group dedup on the queue too, so its own topic-wide window cannot mask the topic's. */
    private static final String TEMPLATE = """
        {
          "Parameters": {"Scope": {"Type": "String"}},
          "Resources": {
            "Topic": {
              "Type": "AWS::SNS::Topic",
              "Properties": {
                "TopicName": "sns-fifo-scope-cfn-it.fifo",
                "FifoTopic": true,
                "ContentBasedDeduplication": false,
                "FifoThroughputScope": {"Ref": "Scope"}
              }
            },
            "Queue": {
              "Type": "AWS::SQS::Queue",
              "Properties": {
                "QueueName": "sns-fifo-scope-cfn-it.fifo",
                "FifoQueue": true,
                "DeduplicationScope": "messageGroup",
                "FifoThroughputLimit": "perMessageGroupId"
              }
            },
            "Subscription": {
              "Type": "AWS::SNS::Subscription",
              "Properties": {
                "TopicArn": {"Ref": "Topic"},
                "Protocol": "sqs",
                "Endpoint": {"Fn::GetAtt": ["Queue", "Arn"]},
                "RawMessageDelivery": true
              }
            }
          },
          "Outputs": {
            "TopicArn": {"Value": {"Ref": "Topic"}},
            "QueueUrl": {"Value": {"Ref": "Queue"}}
          }
        }
        """;

    @Test
    void aTemplateAskingForPerGroupDeduplicationGetsIt() throws InterruptedException {
        cloudFormation("CreateStack", "MessageGroup");
        String created = describeStacks("CREATE_COMPLETE");
        String topicArn = outputValue(created, "TopicArn");
        String queueUrl = outputValue(created, "QueueUrl");

        assertEquals("MessageGroup", topicAttribute(topicArn, "FifoThroughputScope"),
                "the template's FifoThroughputScope has to reach the topic");

        // One deduplication id, two message groups. Under MessageGroup scope they are two messages.
        publish(topicArn, "group-1", "per-group-dedup", "per-group-one");
        publish(topicArn, "group-2", "per-group-dedup", "per-group-two");
        String perGroup = drain(queueUrl);
        assertTrue(perGroup.contains("per-group-one"), perGroup);
        assertTrue(perGroup.contains("per-group-two"),
                "the second group's message was deduplicated against the first: " + perGroup);

        // A received message stays in flight and holds its group, so clear the queue first.
        purgeQueue(queueUrl);

        cloudFormation("UpdateStack", "Topic");
        describeStacks("UPDATE_COMPLETE");
        assertEquals("Topic", topicAttribute(topicArn, "FifoThroughputScope"),
                "createTopic hands an existing topic back untouched, so an update has to rewrite it");

        // The same pair against the AWS default scope: the second publish is a topic-wide duplicate.
        publish(topicArn, "group-1", "topic-wide-dedup", "topic-scope-one");
        publish(topicArn, "group-2", "topic-wide-dedup", "topic-scope-two");
        String topicWide = drain(queueUrl);
        assertTrue(topicWide.contains("topic-scope-one"), topicWide);
        assertFalse(topicWide.contains("topic-scope-two"),
                "Topic scope must keep deduplicating across groups: " + topicWide);

        cloudFormation("DeleteStack", null);
        awaitStackDeleted();
        topicAttributes(topicArn).statusCode(404);
    }

    private static void cloudFormation(String action, String scope) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", STACK);
        if (scope != null) {
            request.formParam("TemplateBody", TEMPLATE)
                   .formParam("Parameters.member.1.ParameterKey", "Scope")
                   .formParam("Parameters.member.1.ParameterValue", scope);
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
            .body(org.hamcrest.Matchers.containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
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

    private static String topicAttribute(String topicArn, String name) {
        String xml = topicAttributes(topicArn).statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "entry", "key", "value").get(name);
    }

    private static void publish(String topicArn, String groupId, String dedupId, String message) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", SNS_AUTH)
            .formParam("Action", "Publish")
            .formParam("TopicArn", topicArn)
            .formParam("Message", message)
            .formParam("MessageGroupId", groupId)
            .formParam("MessageDeduplicationId", dedupId)
        .when().post("/").then().statusCode(200);
    }

    /** A FIFO receive can return one group at a time, so read repeatedly. */
    private static String drain(String queueUrl) {
        StringBuilder received = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            received.append(given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", SQS_AUTH)
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("MaxNumberOfMessages", "10")
            .when().post("/").then().statusCode(200).extract().asString());
        }
        return received.toString();
    }

    private static void purgeQueue(String queueUrl) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", SQS_AUTH)
            .formParam("Action", "PurgeQueue")
            .formParam("QueueUrl", queueUrl)
        .when().post("/").then().statusCode(200);
    }
}
