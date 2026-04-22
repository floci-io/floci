package io.github.hectorvent.floci.services.pipes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PipesPollerIntegrationTest {

    private static final String SQS_CONTENT_TYPE = "application/x-www-form-urlencoded";

    @Test
    @Order(1)
    void createSourceQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-source-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void createTargetQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-target-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
    void createPipeFromSqsToSqs() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Source": "arn:aws:sqs:us-east-1:000000000000:pipe-source-queue",
                    "Target": "arn:aws:sqs:us-east-1:000000000000:pipe-target-queue",
                    "RoleArn": "arn:aws:iam::000000000000:role/pipe-role",
                    "DesiredState": "RUNNING"
                }
                """)
        .when()
            .post("/v1/pipes/sqs-to-sqs-pipe")
        .then()
            .statusCode(200)
            .body("CurrentState", equalTo("RUNNING"));
    }

    @Test
    @Order(4)
    void sendMessageToSourceQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-source-queue")
            .formParam("MessageBody", "hello from pipes")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(5)
    void targetQueueReceivesForwardedMessage() throws Exception {
        String body = null;
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);
            body = given()
                .contentType(SQS_CONTENT_TYPE)
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-target-queue")
                .formParam("MaxNumberOfMessages", "1")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().body().asString();

            if (body.contains("hello from pipes") || body.contains("Records")) {
                break;
            }
        }
        assertTrue(body.contains("hello from pipes") || body.contains("Records"),
                "Target queue should contain forwarded message but got: " + body);
    }

    @Test
    @Order(6)
    void sourceQueueIsDrained() throws Exception {
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);
            String body = given()
                .contentType(SQS_CONTENT_TYPE)
                .formParam("Action", "GetQueueAttributes")
                .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-source-queue")
                .formParam("AttributeName.1", "ApproximateNumberOfMessages")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().body().asString();

            if (body.contains("<Value>0</Value>")) {
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail("Source queue should be drained");
    }

    @Test
    @Order(7)
    void stopPipeStopsPolling() {
        given()
            .contentType("application/json")
        .when()
            .post("/v1/pipes/sqs-to-sqs-pipe/stop")
        .then()
            .statusCode(200)
            .body("CurrentState", equalTo("STOPPED"));

        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-source-queue")
            .formParam("MessageBody", "should stay in source")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-source-queue")
            .formParam("AttributeName.1", "ApproximateNumberOfMessages")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Value>1</Value>"));
    }

    @Test
    @Order(8)
    void cleanupPipe() {
        given()
            .contentType("application/json")
        .when()
            .delete("/v1/pipes/sqs-to-sqs-pipe")
        .then()
            .statusCode(200);
    }
}
