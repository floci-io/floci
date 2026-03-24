package io.github.hectorvent.floci.services.sns;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for SNS via the query (form-encoded) protocol.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SnsIntegrationTest {

    private static final String SNS_CONTENT_TYPE = "application/x-amz-json-1.0";

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(SNS_CONTENT_TYPE, ContentType.TEXT));
    }

    private static String topicArn;
    private static String subscriptionArn;
    private static String sqsQueueUrl;
    private static String rawDeliveryQueueUrl;
    private static String rawDeliverySubArn;
    private static String envelopeQueueUrl;
    private static String envelopeSubArn;

    @Test
    @Order(1)
    void createQueue_forFanout() {
        sqsQueueUrl = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "sns-fanout-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<QueueUrl>"))
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    @Test
    @Order(2)
    void createTopic() {
        topicArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateTopic")
            .formParam("Name", "integration-test-topic")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<TopicArn>"))
            .body(containsString("integration-test-topic"))
            .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");
    }

    @Test
    @Order(3)
    void createTopic_idempotent() {
        String arn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateTopic")
            .formParam("Name", "integration-test-topic")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");
        assert arn.equals(topicArn);
    }

    @Test
    @Order(4)
    void listTopics() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListTopics")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("integration-test-topic"));
    }

    @Test
    @Order(5)
    void getTopicAttributes() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTopicAttributes")
            .formParam("TopicArn", topicArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("TopicArn"))
            .body(containsString("SubscriptionsConfirmed"));
    }

    @Test
    @Order(6)
    void subscribe_toSqsQueue() {
        subscriptionArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Subscribe")
            .formParam("TopicArn", topicArn)
            .formParam("Protocol", "sqs")
            .formParam("Endpoint", sqsQueueUrl)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SubscriptionArn>"))
            .extract().xmlPath()
                .getString("SubscribeResponse.SubscribeResult.SubscriptionArn");
    }

    @Test
    @Order(7)
    void listSubscriptionsByTopic() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListSubscriptionsByTopic")
            .formParam("TopicArn", topicArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("sqs"))
            .body(containsString("sns-fanout-queue"));
    }

    @Test
    @Order(8)
    void listSubscriptions() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListSubscriptions")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("integration-test-topic"));
    }

    @Test
    @Order(9)
    void publish_fanOutToSqsSubscriber() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Publish")
            .formParam("TopicArn", topicArn)
            .formParam("Message", "Hello from SNS!")
            .formParam("Subject", "Test message")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"));

        // Verify the message arrived in the SQS queue
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", sqsQueueUrl)
            .formParam("MaxNumberOfMessages", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("Hello from SNS!"))
            .body(containsString("Notification"));
    }

    @Test
    @Order(10)
    void publishBatch() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "PublishBatch")
            .formParam("TopicArn", topicArn)
            .formParam("PublishBatchRequestEntries.member.1.Id", "msg1")
            .formParam("PublishBatchRequestEntries.member.1.Message", "Batch message 1")
            .formParam("PublishBatchRequestEntries.member.2.Id", "msg2")
            .formParam("PublishBatchRequestEntries.member.2.Message", "Batch message 2")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Id>msg1</Id>"))
            .body(containsString("<Id>msg2</Id>"))
            .body(containsString("<MessageId>"));
    }

    @Test
    @Order(20)
    void publishBatch_jsonProtocol() {
        given()
            .contentType(SNS_CONTENT_TYPE)
            .header("X-Amz-Target", "SNS_20100331.PublishBatch")
            .body("""
                {
                    "TopicArn": "%s",
                    "PublishBatchRequestEntries": [
                        {"Id": "json-msg1", "Message": "JSON batch message 1"},
                        {"Id": "json-msg2", "Message": "JSON batch message 2", "Subject": "Test"}
                    ]
                }
                """.formatted(topicArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Successful.size()", equalTo(2))
            .body("Successful[0].Id", equalTo("json-msg1"))
            .body("Successful[0].MessageId", notNullValue())
            .body("Successful[1].Id", equalTo("json-msg2"))
            .body("Successful[1].MessageId", notNullValue())
            .body("Failed.size()", equalTo(0));
    }

    @Test
    @Order(21)
    void publishBatch_jsonProtocol_emptyEntries() {
        given()
            .contentType(SNS_CONTENT_TYPE)
            .header("X-Amz-Target", "SNS_20100331.PublishBatch")
            .body("""
                {
                    "TopicArn": "%s",
                    "PublishBatchRequestEntries": []
                }
                """.formatted(topicArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Successful.size()", equalTo(0))
            .body("Failed.size()", equalTo(0));
    }

    @Test
    @Order(11)
    void tagResource() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "TagResource")
            .formParam("ResourceArn", topicArn)
            .formParam("Tags.member.1.Key", "env")
            .formParam("Tags.member.1.Value", "test")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(11)
    void listTagsForResource() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListTagsForResource")
            .formParam("ResourceArn", topicArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("env"))
            .body(containsString("test"));
    }

    @Test
    @Order(12)
    void setTopicAttributes() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SetTopicAttributes")
            .formParam("TopicArn", topicArn)
            .formParam("AttributeName", "DisplayName")
            .formParam("AttributeValue", "My Test Topic")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static String filterQueueUrlA;
    private static String filterQueueUrlB;
    private static String filterSubArnA;
    private static String filterSubArnB;

    @Test
    @Order(22)
    void getSubscriptionAttributes_jsonProtocol() {
        given()
            .contentType(SNS_CONTENT_TYPE)
            .header("X-Amz-Target", "SNS_20100331.GetSubscriptionAttributes")
            .body("""
                {"SubscriptionArn": "%s"}
                """.formatted(subscriptionArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Attributes.SubscriptionArn", equalTo(subscriptionArn))
            .body("Attributes.Protocol", equalTo("sqs"))
            .body("Attributes.TopicArn", equalTo(topicArn));
    }

    @Test
    @Order(23)
    void setSubscriptionAttributes_jsonProtocol() {
        given()
            .contentType(SNS_CONTENT_TYPE)
            .header("X-Amz-Target", "SNS_20100331.SetSubscriptionAttributes")
            .body("""
                {
                    "SubscriptionArn": "%s",
                    "AttributeName": "RawMessageDelivery",
                    "AttributeValue": "true"
                }
                """.formatted(subscriptionArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(SNS_CONTENT_TYPE)
            .header("X-Amz-Target", "SNS_20100331.GetSubscriptionAttributes")
            .body("""
                {"SubscriptionArn": "%s"}
                """.formatted(subscriptionArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Attributes.RawMessageDelivery", equalTo("true"));
    }

    @Test
    @Order(24)
    void getSubscriptionAttributes_jsonProtocol_notFound() {
        given()
            .contentType(SNS_CONTENT_TYPE)
            .header("X-Amz-Target", "SNS_20100331.GetSubscriptionAttributes")
            .body("""
                {"SubscriptionArn": "arn:aws:sns:us-east-1:000000000000:nonexistent:fake-id"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(13)
    void filterPolicy_createQueuesAndSubscribe() {
        filterQueueUrlA = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "filter-queue-sports")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

        filterQueueUrlB = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "filter-queue-weather")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

        String sportsQueueArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", filterQueueUrlA)
            .formParam("AttributeName.1", "QueueArn")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("**.find { it.Name == 'QueueArn' }.Value");

        String weatherQueueArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", filterQueueUrlB)
            .formParam("AttributeName.1", "QueueArn")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("**.find { it.Name == 'QueueArn' }.Value");

        filterSubArnA = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Subscribe")
            .formParam("TopicArn", topicArn)
            .formParam("Protocol", "sqs")
            .formParam("Endpoint", sportsQueueArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("SubscribeResponse.SubscribeResult.SubscriptionArn");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SetSubscriptionAttributes")
            .formParam("SubscriptionArn", filterSubArnA)
            .formParam("AttributeName", "FilterPolicy")
            .formParam("AttributeValue", "{\"category\":[\"sports\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        filterSubArnB = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Subscribe")
            .formParam("TopicArn", topicArn)
            .formParam("Protocol", "sqs")
            .formParam("Endpoint", weatherQueueArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("SubscribeResponse.SubscribeResult.SubscriptionArn");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SetSubscriptionAttributes")
            .formParam("SubscriptionArn", filterSubArnB)
            .formParam("AttributeName", "FilterPolicy")
            .formParam("AttributeValue", "{\"category\":[\"weather\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(14)
    void filterPolicy_routesMessageToMatchingSubscription() {
        drainQueue(filterQueueUrlA);
        drainQueue(filterQueueUrlB);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Publish")
            .formParam("TopicArn", topicArn)
            .formParam("Message", "Goal scored!")
            .formParam("MessageAttributes.entry.1.Name", "category")
            .formParam("MessageAttributes.entry.1.Value.DataType", "String")
            .formParam("MessageAttributes.entry.1.Value.StringValue", "sports")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", filterQueueUrlA)
            .formParam("MaxNumberOfMessages", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("Goal scored!"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", filterQueueUrlB)
            .formParam("MaxNumberOfMessages", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<Message>")));
    }

    @Test
    @Order(15)
    void filterPolicy_noFilterPolicyReceivesAllMessages() {
        drainQueue(sqsQueueUrl);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Publish")
            .formParam("TopicArn", topicArn)
            .formParam("Message", "Unfiltered broadcast")
            .formParam("MessageAttributes.entry.1.Name", "category")
            .formParam("MessageAttributes.entry.1.Value.DataType", "String")
            .formParam("MessageAttributes.entry.1.Value.StringValue", "weather")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", sqsQueueUrl)
            .formParam("MaxNumberOfMessages", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("Unfiltered broadcast"));
    }

    @Test
    @Order(16)
    void filterPolicy_nonMatchingMessageNotDelivered() {
        drainQueue(filterQueueUrlA);
        drainQueue(filterQueueUrlB);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Publish")
            .formParam("TopicArn", topicArn)
            .formParam("Message", "Stock update")
            .formParam("MessageAttributes.entry.1.Name", "category")
            .formParam("MessageAttributes.entry.1.Value.DataType", "String")
            .formParam("MessageAttributes.entry.1.Value.StringValue", "finance")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", filterQueueUrlA)
            .formParam("MaxNumberOfMessages", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<Message>")));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", filterQueueUrlB)
            .formParam("MaxNumberOfMessages", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<Message>")));
    }

    @Test
    @Order(17)
    void filterPolicy_cleanup() {
        given().contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Unsubscribe").formParam("SubscriptionArn", filterSubArnA)
            .when().post("/");
        given().contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Unsubscribe").formParam("SubscriptionArn", filterSubArnB)
            .when().post("/");
        given().contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteQueue").formParam("QueueUrl", filterQueueUrlA)
            .when().post("/");
        given().contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteQueue").formParam("QueueUrl", filterQueueUrlB)
            .when().post("/");
    }

    @Test
    @Order(50)
    void rawDelivery_createQueuesAndSubscribe() {
        given().contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteQueue")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/sns-raw-delivery-queue")
            .when().post("/");
        given().contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteQueue")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/sns-envelope-delivery-queue")
            .when().post("/");

        rawDeliveryQueueUrl = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "sns-raw-delivery-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

        envelopeQueueUrl = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "sns-envelope-delivery-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

        rawDeliverySubArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Subscribe")
            .formParam("TopicArn", topicArn)
            .formParam("Protocol", "sqs")
            .formParam("Endpoint", rawDeliveryQueueUrl)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("SubscribeResponse.SubscribeResult.SubscriptionArn");

        envelopeSubArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Subscribe")
            .formParam("TopicArn", topicArn)
            .formParam("Protocol", "sqs")
            .formParam("Endpoint", envelopeQueueUrl)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("SubscribeResponse.SubscribeResult.SubscriptionArn");
    }

    @Test
    @Order(51)
    void rawDelivery_setSubscriptionAttribute() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SetSubscriptionAttributes")
            .formParam("SubscriptionArn", rawDeliverySubArn)
            .formParam("AttributeName", "RawMessageDelivery")
            .formParam("AttributeValue", "true")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(52)
    void rawDelivery_publishAndVerifyRawMessage() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Publish")
            .formParam("TopicArn", topicArn)
            .formParam("Message", "Raw delivery test message")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", rawDeliveryQueueUrl)
            .formParam("MaxNumberOfMessages", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("Raw delivery test message"))
            .body(not(containsString("Notification")));
    }

    @Test
    @Order(53)
    void rawDelivery_defaultSubscriptionWrapsInEnvelope() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", envelopeQueueUrl)
            .formParam("MaxNumberOfMessages", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("Raw delivery test message"))
            .body(containsString("Notification"));
    }

    @Test
    @Order(54)
    void rawDelivery_cleanup() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Unsubscribe")
            .formParam("SubscriptionArn", rawDeliverySubArn)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Unsubscribe")
            .formParam("SubscriptionArn", envelopeSubArn)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteQueue")
            .formParam("QueueUrl", rawDeliveryQueueUrl)
        .when()
            .post("/");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteQueue")
            .formParam("QueueUrl", envelopeQueueUrl)
        .when()
            .post("/");
    }

    @Test
    @Order(100)
    void unsubscribe() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Unsubscribe")
            .formParam("SubscriptionArn", subscriptionArn)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListSubscriptionsByTopic")
            .formParam("TopicArn", topicArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("sns-fanout-queue")));
    }

    @Test
    @Order(101)
    void deleteTopic() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteTopic")
            .formParam("TopicArn", topicArn)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListTopics")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("integration-test-topic")));
    }

    @Test
    @Order(102)
    void unsupportedAction_returns400() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UnknownSnsAction")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("UnsupportedOperation"));
    }

    /**
     * Drains all pending messages from the given SQS queue using PurgeQueue.
     */
    private void drainQueue(String queueUrl) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "PurgeQueue")
            .formParam("QueueUrl", queueUrl)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
