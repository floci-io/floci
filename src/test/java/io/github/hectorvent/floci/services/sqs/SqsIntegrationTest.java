package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqsIntegrationTest {

    private static String queueUrl;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

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
        // MD5 of "Hello from integration test!" = 72077a684c89bfbf51991620feedff61
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
            .body(containsString("<MD5OfMessageBody>72077a684c89bfbf51991620feedff61</MD5OfMessageBody>"));
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
    void sendMessageWithStringAttribute() {
        // MD5 of body "attr-test" = 6eee3c38f0022ec400be5d6eb6f22709
        // MD5 of attributes {color=red (String)} = 20ca9041878c8c65d5a4bf6eaf446c21
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MessageBody", "attr-test")
            .formParam("MessageAttribute.1.Name", "color")
            .formParam("MessageAttribute.1.Value.DataType", "String")
            .formParam("MessageAttribute.1.Value.StringValue", "red")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MD5OfMessageBody>6eee3c38f0022ec400be5d6eb6f22709</MD5OfMessageBody>"))
            .body(containsString("<MD5OfMessageAttributes>20ca9041878c8c65d5a4bf6eaf446c21</MD5OfMessageAttributes>"));
    }

    @Test
    @Order(10)
    void sendMessageWithBinaryAttribute() {
        // body "binary-attr-test" MD5 = c090a04ce0c88aea830b4bf78051e834
        // attribute data=bytes[1,2,3] (Binary), base64=AQID
        // MD5 of attributes {data=[1,2,3] (Binary)} = 922637243eb93fabf39f19417c7e2b43
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MessageBody", "binary-attr-test")
            .formParam("MessageAttribute.1.Name", "data")
            .formParam("MessageAttribute.1.Value.DataType", "Binary")
            .formParam("MessageAttribute.1.Value.BinaryValue", "AQID")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MD5OfMessageAttributes>922637243eb93fabf39f19417c7e2b43</MD5OfMessageAttributes>"));
    }

    @Test
    @Order(11)
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
    @Order(12)
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
            .contentType("application/x-amz-json-1.0")
            .header("X-Amz-Target", "AmazonSQS.GetQueueUrl")
            .body("{\"QueueName\": \"no-such-queue-xyz\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .header("x-amzn-query-error", "AWS.SimpleQueueService.NonExistentQueue;Sender")
            .header("X-Amzn-Errortype", "QueueDoesNotExist")
            .body(containsString("QueueDoesNotExist"))
            .body(not(containsString("AWS.SimpleQueueService.NonExistentQueue")));
    }
}
