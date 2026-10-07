package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
            .body("Attributes.QueueArn", notNullValue())
            .body("Attributes.ApproximateNumberOfMessages", notNullValue())
            .body("Attributes.ApproximateNumberOfMessagesNotVisible", notNullValue())
            .body("Attributes.ApproximateNumberOfMessagesDelayed", notNullValue());
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
    @Order(6)
    void sendMessageBatchExceedingQueueMaximumMessageSizeReturnsBatchRequestTooLong() {
        // Default queue MaximumMessageSize is 1048576 bytes. Each entry is well under the
        // per-message limit; the sum is what trips the batch-level check.
        String bigBody = "x".repeat(400_000);
        String body = "{\"QueueUrl\":\"" + queueUrl + "\","
                + "\"Entries\":["
                + "{\"Id\":\"a\",\"MessageBody\":\"" + bigBody + "\"},"
                + "{\"Id\":\"b\",\"MessageBody\":\"" + bigBody + "\"},"
                + "{\"Id\":\"c\",\"MessageBody\":\"" + bigBody + "\"}"
                + "]}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.SendMessageBatch")
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("BatchRequestTooLong"));
    }

    @Test
    @Order(6)
    void sendMessageBatchPersistsAwsTraceHeaderPerEntry() {
        String traceQueueName = "json-batch-trace-queue";
        String createBody = "{\"QueueName\":\"" + traceQueueName + "\"}";
        String traceQueueUrl = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .body(createBody)
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("QueueUrl");

        String batchBody = "{\"QueueUrl\":\"" + traceQueueUrl + "\","
                + "\"Entries\":["
                + "{\"Id\":\"a\",\"MessageBody\":\"first\","
                + "\"MessageSystemAttributes\":{"
                + "\"AWSTraceHeader\":{\"DataType\":\"String\",\"StringValue\":\"Root=1-aaa\"}}},"
                + "{\"Id\":\"b\",\"MessageBody\":\"second\","
                + "\"MessageSystemAttributes\":{"
                + "\"AWSTraceHeader\":{\"DataType\":\"String\",\"StringValue\":\"Root=1-bbb\"}}}"
                + "]}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.SendMessageBatch")
            .body(batchBody)
        .when().post("/").then().statusCode(200)
            .body("Successful", hasSize(2));

        String receiveBody = "{\"QueueUrl\":\"" + traceQueueUrl + "\","
                + "\"MaxNumberOfMessages\":10,"
                + "\"MessageSystemAttributeNames\":[\"AWSTraceHeader\"]}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
            .body(receiveBody)
        .when().post("/").then().statusCode(200)
            .body("Messages", hasSize(2))
            .body("Messages.Attributes.AWSTraceHeader", hasItems("Root=1-aaa", "Root=1-bbb"));
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
    @Order(7)
    void receiveMessage_messageSystemAttributeNamesFiltersOutput() {
        String filterQueueName = QUEUE_NAME + "-filter";
        String filterQueueUrl = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .body("{\"QueueName\":\"" + filterQueueName + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("QueueUrl");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.SendMessage")
            .body("{\"QueueUrl\":\"" + filterQueueUrl + "\",\"MessageBody\":\"hi\"}")
        .when().post("/").then().statusCode(200);

        // Empty filter: no system attributes returned
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
            .body("{\"QueueUrl\":\"" + filterQueueUrl + "\","
                    + "\"MaxNumberOfMessages\":1,"
                    + "\"VisibilityTimeout\":0,"
                    + "\"MessageSystemAttributeNames\":[]}")
        .when().post("/").then().statusCode(200)
            .body("Messages", hasSize(1))
            .body("Messages[0]", not(hasKey("Attributes")));

        // Only SenderId requested: returns only SenderId
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
            .body("{\"QueueUrl\":\"" + filterQueueUrl + "\","
                    + "\"MaxNumberOfMessages\":1,"
                    + "\"VisibilityTimeout\":0,"
                    + "\"MessageSystemAttributeNames\":[\"SenderId\"]}")
        .when().post("/").then().statusCode(200)
            .body("Messages[0].Attributes.SenderId", equalTo(ACCOUNT_ID))
            .body("Messages[0].Attributes", not(hasKey("SentTimestamp")))
            .body("Messages[0].Attributes", not(hasKey("ApproximateReceiveCount")));

        // All: returns SenderId, SentTimestamp, ApproximateReceiveCount,
        // ApproximateFirstReceiveTimestamp
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
            .body("{\"QueueUrl\":\"" + filterQueueUrl + "\","
                    + "\"MaxNumberOfMessages\":1,"
                    + "\"VisibilityTimeout\":0,"
                    + "\"MessageSystemAttributeNames\":[\"All\"]}")
        .when().post("/").then().statusCode(200)
            .body("Messages[0].Attributes.SenderId", equalTo(ACCOUNT_ID))
            .body("Messages[0].Attributes.SentTimestamp", notNullValue())
            .body("Messages[0].Attributes.ApproximateReceiveCount", notNullValue())
            .body("Messages[0].Attributes.ApproximateFirstReceiveTimestamp", notNullValue());

        // Legacy AttributeNames parameter (pre-2023 SDKs): SenderId-only filter
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
            .body("{\"QueueUrl\":\"" + filterQueueUrl + "\","
                    + "\"MaxNumberOfMessages\":1,"
                    + "\"VisibilityTimeout\":0,"
                    + "\"AttributeNames\":[\"SenderId\"]}")
        .when().post("/").then().statusCode(200)
            .body("Messages[0].Attributes.SenderId", equalTo(ACCOUNT_ID))
            .body("Messages[0].Attributes", not(hasKey("SentTimestamp")))
            .body("Messages[0].Attributes", not(hasKey("ApproximateReceiveCount")));

        // Legacy AttributeNames=["All"]: same expansion as MessageSystemAttributeNames
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
            .body("{\"QueueUrl\":\"" + filterQueueUrl + "\","
                    + "\"MaxNumberOfMessages\":1,"
                    + "\"VisibilityTimeout\":0,"
                    + "\"AttributeNames\":[\"All\"]}")
        .when().post("/").then().statusCode(200)
            .body("Messages[0].Attributes.SenderId", equalTo(ACCOUNT_ID))
            .body("Messages[0].Attributes.SentTimestamp", notNullValue())
            .body("Messages[0].Attributes.ApproximateReceiveCount", notNullValue())
            .body("Messages[0].Attributes.ApproximateFirstReceiveTimestamp", notNullValue());
    }

    @Test
    void receiveMessage_messageAttributeNamesFiltersOutput() {
        String filterQueueUrl = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .body("{\"QueueName\":\"json-message-attribute-filter-queue\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("QueueUrl");

        try {
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.SendMessage")
                .body("{\"QueueUrl\":\"" + filterQueueUrl + "\","
                        + "\"MessageBody\":\"filter-test\","
                        + "\"MessageAttributes\":{"
                        + "\"color.primary\":{\"DataType\":\"String\",\"StringValue\":\"red\"},"
                        + "\"color\":{\"DataType\":\"String\",\"StringValue\":\"blue\"},"
                        + "\"colorful\":{\"DataType\":\"String\",\"StringValue\":\"bright\"},"
                        + "\"secret\":{\"DataType\":\"String\",\"StringValue\":\"private\"}}}")
            .when().post("/").then().statusCode(200);

            String receiveBody = "{\"QueueUrl\":\"" + filterQueueUrl + "\",\"VisibilityTimeout\":0";
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .body(receiveBody + "}")
            .when().post("/").then().statusCode(200)
                .body("Messages", hasSize(1))
                .body("Messages[0]", not(hasKey("MessageAttributes")))
                .body("Messages[0]", not(hasKey("MD5OfMessageAttributes")));

            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .body(receiveBody + ",\"MessageAttributeNames\":[\"color.primary\"]}")
            .when().post("/").then().statusCode(200)
                .body("Messages[0].MessageAttributes", hasKey("color.primary"))
                .body("Messages[0].MessageAttributes", not(hasKey("color")))
                .body("Messages[0].MessageAttributes", not(hasKey("colorful")))
                .body("Messages[0].MessageAttributes", not(hasKey("secret")))
                .body("Messages[0].MD5OfMessageAttributes", equalTo("d1cf84dfbac1cbe5c78ed09a1768b0d9"));

            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .body(receiveBody + ",\"MessageAttributeNames\":[\"color.*\"]}")
            .when().post("/").then().statusCode(200)
                .body("Messages[0].MessageAttributes", hasKey("color.primary"))
                .body("Messages[0].MessageAttributes", hasKey("color"))
                .body("Messages[0].MessageAttributes", hasKey("colorful"))
                .body("Messages[0].MessageAttributes", not(hasKey("secret")))
                .body("Messages[0].MD5OfMessageAttributes", equalTo("46db9885b8f221ea04082f03e3a63d14"));

            for (String allSelector : new String[]{"All", ".*"}) {
                given()
                    .contentType(CONTENT_TYPE)
                    .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                    .body(receiveBody + ",\"MessageAttributeNames\":[\"" + allSelector + "\"]}")
                .when().post("/").then().statusCode(200)
                    .body("Messages[0].MessageAttributes", hasKey("color.primary"))
                    .body("Messages[0].MessageAttributes.secret.StringValue", equalTo("private"));
            }
        } finally {
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.DeleteQueue")
                .body("{\"QueueUrl\":\"" + filterQueueUrl + "\"}")
            .when().post("/");
        }
    }

    @Test
    @Order(9)
    void sendMessageToStandardQueueRetainsMessageGroupId() {
        String groupQueueName = QUEUE_NAME + "-group";
        String groupQueueUrl = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .body("{\"QueueName\":\"" + groupQueueName + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("QueueUrl");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.SendMessage")
            .body("{\"QueueUrl\":\"" + groupQueueUrl + "\","
                    + "\"MessageBody\":\"fair-queues message\","
                    + "\"MessageGroupId\":\"tenant-1\"}")
        .when().post("/").then().statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
            .body("{\"QueueUrl\":\"" + groupQueueUrl + "\","
                    + "\"MaxNumberOfMessages\":1,"
                    + "\"VisibilityTimeout\":0,"
                    + "\"MessageSystemAttributeNames\":[\"MessageGroupId\"]}")
        .when().post("/").then().statusCode(200)
            .body("Messages[0].Attributes.MessageGroupId", equalTo("tenant-1"));
    }

    @Test
    @Order(7)
    void tagQueueWithJsonNullValueIsRejected() {
        String tagBody = "{\"QueueUrl\":\"" + queueUrl + "\","
                + "\"Tags\":{\"NullTag\":null,\"RealTag\":\"value\"}}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.TagQueue")
            .body(tagBody)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValue"))
            .body("message", equalTo("the parameter 'value' may not be null"));

        String listBody = "{\"QueueUrl\":\"" + queueUrl + "\"}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.ListQueueTags")
            .body(listBody)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.NullTag", nullValue())
            .body("Tags.RealTag", nullValue());
    }

    @Test
    @Order(7)
    void createQueueWithJsonNullTagValueIsRejected() {
        String body = "{\"QueueName\":\"" + QUEUE_NAME + "-null-tag\","
                + "\"tags\":{\"NullTag\":null}}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValue"))
            .body("message", equalTo("the parameter 'value' may not be null"));
    }

    @Test
    @Order(7)
    void createQueueWithJsonNullAttributeValueIsRejected() {
        String body = "{\"QueueName\":\"" + QUEUE_NAME + "-null-attr\","
                + "\"Attributes\":{\"DelaySeconds\":null}}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValue"))
            .body("message", equalTo("the parameter 'value' may not be null"));
    }

    @Test
    @Order(7)
    void setQueueAttributesWithJsonNullValueIsRejected() {
        String body = "{\"QueueUrl\":\"" + queueUrl + "\","
                + "\"Attributes\":{\"DelaySeconds\":null}}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.SetQueueAttributes")
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValue"))
            .body("message", equalTo("the parameter 'value' may not be null"));
    }

    @Test
    @Order(7)
    void malformedReceiptHandleIsRejected() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.DeleteMessage")
            .body("{\"QueueUrl\":\"" + queueUrl + "\",\"ReceiptHandle\":\"abc\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ReceiptHandleIsInvalid"))
            .body("message", equalTo("The input receipt handle is invalid."));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.DeleteMessageBatch")
            .body("{\"QueueUrl\":\"" + queueUrl + "\",\"Entries\":[{\"Id\":\"e\",\"ReceiptHandle\":\"\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Failed[0].Id", equalTo("e"))
            .body("Failed[0].Code", equalTo("ReceiptHandleIsInvalid"))
            .body("Failed[0].Message", equalTo("The request must contain the parameter ReceiptHandle."));
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


    @Test
    void receiveMessageWithoutWaitTimeSecondsHonoursQueueReceiveMessageWaitTimeSeconds() {
        String createBody = "{\"QueueName\":\"json-long-poll-attr-queue\","
                + "\"Attributes\":{\"ReceiveMessageWaitTimeSeconds\":\"1\"}}";
        String longPollQueueUrl = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .body(createBody)
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("QueueUrl");

        try {
            long start = System.nanoTime();
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .body("{\"QueueUrl\":\"" + longPollQueueUrl + "\"}")
            .when().post("/").then().statusCode(200)
                .body("$", not(hasKey("Messages")));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs >= 900,
                    "Omitting WaitTimeSeconds must long poll for the queue's ReceiveMessageWaitTimeSeconds, but returned after " + elapsedMs + "ms");

            start = System.nanoTime();
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .body("{\"QueueUrl\":\"" + longPollQueueUrl + "\",\"WaitTimeSeconds\":0}")
            .when().post("/").then().statusCode(200)
                .body("$", not(hasKey("Messages")));
            elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 1000,
                    "WaitTimeSeconds=0 must override the queue attribute, but returned after " + elapsedMs + "ms");
        } finally {
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.DeleteQueue")
                .body("{\"QueueUrl\":\"" + longPollQueueUrl + "\"}")
            .when().post("/");
        }
    }

    @Test
    void receiveMessageRejectsInvalidWaitTimeSeconds() {
        String rangeQueueUrl = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .body("{\"QueueName\":\"json-wait-time-range-queue\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("QueueUrl");

        try {
            for (String invalid : new String[]{"-1", "21", "1.5", "\"abc\""}) {
                given()
                    .contentType(CONTENT_TYPE)
                    .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                    .body("{\"QueueUrl\":\"" + rangeQueueUrl + "\",\"WaitTimeSeconds\":" + invalid + "}")
                .when().post("/").then()
                    .statusCode(400)
                    .body("__type", equalTo("InvalidParameterValue"))
                    .body("message", containsString("WaitTimeSeconds"));
            }
        } finally {
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.DeleteQueue")
                .body("{\"QueueUrl\":\"" + rangeQueueUrl + "\"}")
            .when().post("/");
        }
    }

    @Test
    void receiveMessageReturnsPerMessageSenderId() {
        String testQueueUrl = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .body("{\"QueueName\":\"json-sender-id-per-message-queue\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("QueueUrl");

        try {
            String auth1 = "AWS4-HMAC-SHA256 Credential=111122223333/20260215/us-east-1/sqs/aws4_request, "
                    + "SignedHeaders=host;x-amz-date;x-amz-security-token, Signature=abc";
            String auth2 = "AWS4-HMAC-SHA256 Credential=444455556666/20260215/us-east-1/sqs/aws4_request, "
                    + "SignedHeaders=host;x-amz-date;x-amz-security-token, Signature=abc";

            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.SendMessage")
                .header("Authorization", auth1)
                .body("{\"QueueUrl\":\"" + testQueueUrl + "\",\"MessageBody\":\"msg-sender-1\"}")
            .when().post("/").then().statusCode(200);

            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.SendMessage")
                .header("Authorization", auth2)
                .body("{\"QueueUrl\":\"" + testQueueUrl + "\",\"MessageBody\":\"msg-sender-2\"}")
            .when().post("/").then().statusCode(200);

            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .body("{\"QueueUrl\":\"" + testQueueUrl + "\","
                        + "\"MaxNumberOfMessages\":10,"
                        + "\"VisibilityTimeout\":0,"
                        + "\"MessageSystemAttributeNames\":[\"SenderId\"]}")
            .when().post("/").then().statusCode(200)
                .body("Messages", hasSize(2))
                .body("Messages.Attributes.SenderId", hasItems("111122223333", "444455556666"));
        } finally {
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.DeleteQueue")
                .body("{\"QueueUrl\":\"" + testQueueUrl + "\"}")
            .when().post("/");
        }
    }
}
