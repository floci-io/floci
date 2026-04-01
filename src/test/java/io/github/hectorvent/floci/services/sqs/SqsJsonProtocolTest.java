package io.github.hectorvent.floci.services.sqs;

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
 * Integration tests for the SQS JSON 1.0 protocol (application/x-amz-json-1.0).
 *
 * Covers two routing modes used by AWS SDKs:
 * - Root path: POST / with X-Amz-Target header (older SDKs)
 * - Queue URL path: POST /{accountId}/{queueName} with X-Amz-Target header
 *   (newer SDKs, e.g. aws-sdk-sqs Ruby gem >= 1.71)
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqsJsonProtocolTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String QUEUE_NAME = "json-protocol-test-queue";

    private static String queueUrl;
    private static String receiptHandle;

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.config = RestAssured.config().encoderConfig(
            EncoderConfig.encoderConfig()
                .encodeContentTypeAs(CONTENT_TYPE, ContentType.TEXT)
        );
    }

    // --- Root-path JSON 1.0 (POST /) ---

    @Test
    @Order(1)
    void createQueueViaRootPath() {
        String body = "{\"QueueName\":\"" + QUEUE_NAME + "\"}";

        queueUrl = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("QueueUrl", containsString(QUEUE_NAME))
            .extract().jsonPath().getString("QueueUrl");
    }

    @Test
    @Order(2)
    void getQueueAttributesViaRootPath() {
        String body = "{\"QueueUrl\":\"" + queueUrl + "\",\"AttributeNames\":[\"All\"]}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Attributes.QueueArn", notNullValue());
    }

    // --- Queue-URL-path JSON 1.0 (POST /{accountId}/{queueName}) ---
    // Regression: these requests were previously routed to S3Controller,
    // returning NoSuchBucket errors.

    @Test
    @Order(3)
    void sendMessageViaQueueUrlPath() {
        String body = "{\"QueueUrl\":\"" + queueUrl + "\","
                + "\"MessageBody\":\"hello from json protocol test\"}";

        receiptHandle = null;

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.SendMessage")
            .body(body)
        .when()
            .post("/" + ACCOUNT_ID + "/" + QUEUE_NAME)
        .then()
            .statusCode(200)
            .body("MessageId", notNullValue())
            .body("MD5OfMessageBody", notNullValue());
    }

    @Test
    @Order(4)
    void receiveMessageViaQueueUrlPath() {
        String body = "{\"QueueUrl\":\"" + queueUrl + "\",\"MaxNumberOfMessages\":1}";

        receiptHandle = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
            .body(body)
        .when()
            .post("/" + ACCOUNT_ID + "/" + QUEUE_NAME)
        .then()
            .statusCode(200)
            .body("Messages", hasSize(1))
            .body("Messages[0].Body", equalTo("hello from json protocol test"))
            .extract().jsonPath().getString("Messages[0].ReceiptHandle");
    }

    @Test
    @Order(5)
    void deleteMessageViaQueueUrlPath() {
        String body = "{\"QueueUrl\":\"" + queueUrl + "\","
                + "\"ReceiptHandle\":\"" + receiptHandle + "\"}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.DeleteMessage")
            .body(body)
        .when()
            .post("/" + ACCOUNT_ID + "/" + QUEUE_NAME)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void getQueueAttributesViaQueueUrlPath() {
        String body = "{\"QueueUrl\":\"" + queueUrl + "\",\"AttributeNames\":[\"All\"]}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
            .body(body)
        .when()
            .post("/" + ACCOUNT_ID + "/" + QUEUE_NAME)
        .then()
            .statusCode(200)
            .body("Attributes.QueueArn", notNullValue());
    }

    @Test
    @Order(7)
    void deleteQueueViaQueueUrlPath() {
        String body = "{\"QueueUrl\":\"" + queueUrl + "\"}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.DeleteQueue")
            .body(body)
        .when()
            .post("/" + ACCOUNT_ID + "/" + QUEUE_NAME)
        .then()
            .statusCode(200);
    }
}
