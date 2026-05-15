package io.github.hectorvent.floci.services.sqs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqsFifoIntegrationTest {

    private static String queueUrl;

    @Test
    @Order(1)
    void createFifoQueue() {
        queueUrl = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "fifo-test.fifo")
            .formParam("Attribute.1.Name", "FifoQueue")
            .formParam("Attribute.1.Value", "true")
            .formParam("Attribute.2.Name", "ContentBasedDeduplication")
            .formParam("Attribute.2.Value", "true")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<QueueUrl>"))
            .body(containsString("fifo-test.fifo"))
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    @Test
    @Order(2)
    void sendMessageToFifoQueue() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MessageBody", "FIFO message 1")
            .formParam("MessageGroupId", "group-a")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"))
            .body(containsString("<SequenceNumber>"));
    }

    @Test
    @Order(3)
    void sendMessageWithExplicitDedup() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MessageBody", "FIFO message 2")
            .formParam("MessageGroupId", "group-a")
            .formParam("MessageDeduplicationId", "explicit-dedup-1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SequenceNumber>"));
    }

    @Test
    @Order(4)
    void sendDuplicateMessageIsIdempotent() {
        // Send same dedup ID — should return same message ID
        String msgId1 = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MessageBody", "dedup-test")
            .formParam("MessageGroupId", "group-b")
            .formParam("MessageDeduplicationId", "unique-dedup-1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("SendMessageResponse.SendMessageResult.MessageId");

        String msgId2 = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MessageBody", "dedup-test")
            .formParam("MessageGroupId", "group-b")
            .formParam("MessageDeduplicationId", "unique-dedup-1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("SendMessageResponse.SendMessageResult.MessageId");

        // Same message ID returned
        org.junit.jupiter.api.Assertions.assertEquals(msgId1, msgId2);
    }

    @Test
    @Order(5)
    void receiveMessageIncludesFifoAttributes() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "10")
            .formParam("AttributeName.1", "All")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("MessageGroupId"))
            .body(containsString("SequenceNumber"));
    }

    @Test
    @Order(6)
    void sendToFifoWithoutGroupIdFails() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MessageBody", "should fail")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("MissingParameter"));
    }

    @Test
    @Order(7)
    void getFifoQueueAttributes() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", queueUrl)
            .formParam("AttributeName.1", "All")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("FifoQueue"))
            .body(containsString("ContentBasedDeduplication"));
    }

    @Test
    @Order(8)
    void deleteFifoQueue() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteQueue")
            .formParam("QueueUrl", queueUrl)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
