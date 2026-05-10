package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
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
    private static final String AUTH_SQS_EU_WEST_2 =
            "AWS4-HMAC-SHA256 Credential=AKID/20260215/eu-west-2/sqs/aws4_request, "
                    + "SignedHeaders=host;x-amz-date;x-amz-security-token, Signature=abc";

    private static String queueUrl;
    private static String receiptHandle;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
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
    @Order(6)
    void sendMessageBatchReturnsMd5OfMessageAttributes() {
        String body = "{\"QueueUrl\":\"" + queueUrl + "\","
                + "\"Entries\":[{"
                + "\"Id\":\"m1\","
                + "\"MessageBody\":\"batch body\","
                + "\"MessageAttributes\":{"
                + "\"trace-id\":{\"DataType\":\"String\",\"StringValue\":\"abc-123\"}"
                + "}}]}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.SendMessageBatch")
            .body(body)
        .when()
            .post("/" + ACCOUNT_ID + "/" + QUEUE_NAME)
        .then()
            .statusCode(200)
            .body("Successful", hasSize(1))
            .body("Successful[0].Id", equalTo("m1"))
            .body("Successful[0].MD5OfMessageBody", notNullValue())
            .body("Successful[0].MD5OfMessageAttributes", notNullValue());
    }

    @Test
    @Order(7)
    void receiveMessageOnEmptyQueueOmitsMessagesField() {
        // AWS omits the Messages field entirely when no messages are available.
        // The .NET SDK with InitializeCollections=false then leaves the
        // response collection as null. Floci previously returned "Messages": []
        // which broke parity tests asserting null.
        String emptyQueueName = QUEUE_NAME + "-empty";
        String createBody = "{\"QueueName\":\"" + emptyQueueName + "\"}";
        String emptyQueueUrl = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .body(createBody)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("QueueUrl");

        String body = "{\"QueueUrl\":\"" + emptyQueueUrl + "\",\"MaxNumberOfMessages\":1}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
            .body(body)
        .when()
            .post("/" + ACCOUNT_ID + "/" + emptyQueueName)
        .then()
            .statusCode(200)
            .body("$", not(hasKey("Messages")));
    }

    @Test
    @Order(8)
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

    @Test
    @Order(8)
    void signedJsonRequestsAcceptTemporaryCredentialsAndRewrittenQueueHost() {
        String signedQueueName = QUEUE_NAME + "-signed";
        String createBody = "{\"QueueName\":\"" + signedQueueName + "\"}";

        String signedQueueUrl = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .header("Authorization", AUTH_SQS_EU_WEST_2)
            .header("X-Amz-Date", "20260215T120000Z")
            .header("X-Amz-Security-Token", "session-token")
            .body(createBody)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("QueueUrl", containsString(signedQueueName))
            .extract().jsonPath().getString("QueueUrl");

        String lambdaReachableQueueUrl = signedQueueUrl.replaceFirst("://[^/]+", "://floci:4566");
        String sendBody = "{\"QueueUrl\":\"" + lambdaReachableQueueUrl + "\","
                + "\"MessageBody\":\"hello from signed json\"}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.SendMessage")
            .header("Authorization", AUTH_SQS_EU_WEST_2)
            .header("X-Amz-Date", "20260215T120000Z")
            .header("X-Amz-Security-Token", "session-token")
            .body(sendBody)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("MessageId", notNullValue());

        String receiveBody = "{\"QueueUrl\":\"" + lambdaReachableQueueUrl + "\",\"MaxNumberOfMessages\":1}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
            .header("Authorization", AUTH_SQS_EU_WEST_2)
            .header("X-Amz-Date", "20260215T120000Z")
            .header("X-Amz-Security-Token", "session-token")
            .body(receiveBody)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Messages", hasSize(1))
            .body("Messages[0].Body", equalTo("hello from signed json"));
    }
}
