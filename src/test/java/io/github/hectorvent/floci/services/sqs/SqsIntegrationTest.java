package io.github.hectorvent.floci.services.sqs;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqsIntegrationTest {

    private static String queueUrl;

    @Test
    @Order(1)
    void createQueue() {
        queueUrl = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "integration-test-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<QueueUrl>"))
            .body(containsString("integration-test-queue"))
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    @Test
    @Order(2)
    void getQueueUrl() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", "integration-test-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("integration-test-queue"));
    }

    @Test
    @Order(3)
    void listQueues() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListQueues")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("integration-test-queue"));
    }

    @Test
    @Order(4)
    void sendMessage() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MessageBody", "Hello from integration test!")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"))
            .body(containsString("<MD5OfMessageBody>"));
    }

    @Test
    @Order(5)
    void receiveMessage() {
        String receiptHandle = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("Hello from integration test!"))
            .body(containsString("<ReceiptHandle>"))
            .extract().xmlPath().getString(
                "ReceiveMessageResponse.ReceiveMessageResult.Message.ReceiptHandle");

        // Store for delete test — use static field
        SqsIntegrationTest.receiptHandle = receiptHandle;
    }

    private static String receiptHandle;

    @Test
    @Order(6)
    void deleteMessage() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("ReceiptHandle", receiptHandle)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DeleteMessageResponse>"));
    }

    @Test
    @Order(7)
    void receiveMessageAfterDeleteReturnsEmpty() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "1")
            .formParam("VisibilityTimeout", "0")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<Message>")));
    }

    @Test
    @Order(8)
    void sendAndPurgeQueue() {
        // Send some messages
        for (int i = 0; i < 3; i++) {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "SendMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("MessageBody", "purge-msg-" + i)
            .when()
                .post("/");
        }

        // Purge
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "PurgeQueue")
            .formParam("QueueUrl", queueUrl)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify empty
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "10")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<Message>")));
    }

    @Test
    @Order(9)
    void getQueueAttributes() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", queueUrl)
            .formParam("AttributeName.1", "All")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Attribute>"))
            .body(containsString("QueueArn"));
    }

    @Test
    @Order(10)
    void deleteQueue() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteQueue")
            .formParam("QueueUrl", queueUrl)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify it's gone
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", "integration-test-queue")
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    @Test
    void unsupportedAction() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UnsupportedAction")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("UnsupportedOperation"));
    }

    @Test
    void createQueue_idempotent_sameAttributes() {
        String queueName = "idempotent-test-queue";

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", queueName)
            .formParam("Attribute.1.Name", "VisibilityTimeout")
            .formParam("Attribute.1.Value", "60")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(queueName));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", queueName)
            .formParam("Attribute.1.Name", "VisibilityTimeout")
            .formParam("Attribute.1.Value", "60")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(queueName));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteQueue")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/" + queueName)
        .when()
            .post("/");
    }

    @Test
    void createQueue_conflictingAttributes_returns400() {
        String queueName = "conflict-test-queue";

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", queueName)
            .formParam("Attribute.1.Name", "VisibilityTimeout")
            .formParam("Attribute.1.Value", "30")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", queueName)
            .formParam("Attribute.1.Name", "VisibilityTimeout")
            .formParam("Attribute.1.Value", "60")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("QueueNameExists"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteQueue")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/" + queueName)
        .when()
            .post("/");
    }

    @Test
    void jsonProtocol_nonExistentQueue_returnsQueueDoesNotExist() {
        given()
            .config(RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                    .encodeContentTypeAs("application/x-amz-json-1.0", ContentType.TEXT)))
            .contentType("application/x-amz-json-1.0")
            .header("X-Amz-Target", "AmazonSQS.GetQueueUrl")
            .body("{\"QueueName\": \"no-such-queue-xyz\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("QueueDoesNotExist"))
            .body(not(containsString("AWS.SimpleQueueService.NonExistentQueue")));
    }
}
