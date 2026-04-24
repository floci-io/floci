package io.github.hectorvent.floci.services.pipes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

            if (body.contains("hello from pipes")) {
                break;
            }
        }
        assertTrue(body.contains("hello from pipes"),
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

    // ──────────────────────────── FilterCriteria Tests ────────────────────────────

    @Test
    @Order(10)
    void createFilterSourceQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-filter-source")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(11)
    void createFilterTargetQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-filter-target")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(12)
    void createPipeWithFilterCriteria() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Source": "arn:aws:sqs:us-east-1:000000000000:pipe-filter-source",
                    "Target": "arn:aws:sqs:us-east-1:000000000000:pipe-filter-target",
                    "RoleArn": "arn:aws:iam::000000000000:role/pipe-role",
                    "DesiredState": "RUNNING",
                    "SourceParameters": {
                        "FilterCriteria": {
                            "Filters": [
                                {"Pattern": "{\\"body\\": {\\"status\\": [\\"active\\"]}}"}
                            ]
                        }
                    }
                }
                """)
        .when()
            .post("/v1/pipes/filter-pipe")
        .then()
            .statusCode(200)
            .body("CurrentState", equalTo("RUNNING"));
    }

    @Test
    @Order(13)
    void sendMatchingAndNonMatchingMessages() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-filter-source")
            .formParam("MessageBody", "{\"status\": \"active\", \"id\": \"match-1\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-filter-source")
            .formParam("MessageBody", "{\"status\": \"inactive\", \"id\": \"no-match\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(14)
    void onlyMatchingMessageForwardedToTarget() throws Exception {
        String body = null;
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);
            body = given()
                .contentType(SQS_CONTENT_TYPE)
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-filter-target")
                .formParam("MaxNumberOfMessages", "10")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().body().asString();

            if (body.contains("match-1")) {
                break;
            }
        }
        assertTrue(body.contains("match-1"),
                "Target should contain the matching message");
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("no-match"),
                "Target should NOT contain the non-matching message");
    }

    @Test
    @Order(15)
    void nonMatchingMessageDeletedFromSource() throws Exception {
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);
            String body = given()
                .contentType(SQS_CONTENT_TYPE)
                .formParam("Action", "GetQueueAttributes")
                .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-filter-source")
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
        org.junit.jupiter.api.Assertions.fail(
                "Source queue should be drained (non-matching messages deleted per AWS behavior)");
    }

    @Test
    @Order(16)
    void cleanupFilterPipe() {
        given()
            .contentType("application/json")
        .when()
            .delete("/v1/pipes/filter-pipe")
        .then()
            .statusCode(200);
    }

    // ──────────────────────────── Batch Size Tests ────────────────────────────

    @Test
    @Order(20)
    void createBatchSourceQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-batch-source")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(21)
    void createBatchTargetQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-batch-target")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(22)
    void createPipeWithBatchSize() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Source": "arn:aws:sqs:us-east-1:000000000000:pipe-batch-source",
                    "Target": "arn:aws:sqs:us-east-1:000000000000:pipe-batch-target",
                    "RoleArn": "arn:aws:iam::000000000000:role/pipe-role",
                    "DesiredState": "RUNNING",
                    "SourceParameters": {
                        "SqsQueueParameters": {
                            "BatchSize": 1
                        }
                    }
                }
                """)
        .when()
            .post("/v1/pipes/batch-pipe")
        .then()
            .statusCode(200)
            .body("CurrentState", equalTo("RUNNING"));
    }

    @Test
    @Order(23)
    void sendMultipleMessagesForBatchTest() {
        for (int i = 1; i <= 3; i++) {
            given()
                .contentType(SQS_CONTENT_TYPE)
                .formParam("Action", "SendMessage")
                .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-batch-source")
                .formParam("MessageBody", "batch-msg-" + i)
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
    }

    @Test
    @Order(24)
    void allBatchMessagesEventuallyForwarded() throws Exception {
        java.util.Set<String> found = new java.util.HashSet<>();
        for (int i = 0; i < 20 && found.size() < 3; i++) {
            Thread.sleep(500);
            String body = given()
                .contentType(SQS_CONTENT_TYPE)
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-batch-target")
                .formParam("MaxNumberOfMessages", "10")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().body().asString();

            for (int j = 1; j <= 3; j++) {
                if (body.contains("batch-msg-" + j)) found.add("batch-msg-" + j);
            }
        }
        assertEquals(3, found.size(),
                "All 3 batch messages should eventually reach the target, found: " + found);
    }

    @Test
    @Order(25)
    void cleanupBatchPipe() {
        given()
            .contentType("application/json")
        .when()
            .delete("/v1/pipes/batch-pipe")
        .then()
            .statusCode(200);
    }

    // ──────────────────────────── InputTemplate Tests ────────────────────────────

    @Test
    @Order(30)
    void createInputTemplateSourceQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-tmpl-source")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(31)
    void createInputTemplateTargetQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-tmpl-target")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(32)
    void createPipeWithInputTemplate() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Source": "arn:aws:sqs:us-east-1:000000000000:pipe-tmpl-source",
                    "Target": "arn:aws:sqs:us-east-1:000000000000:pipe-tmpl-target",
                    "RoleArn": "arn:aws:iam::000000000000:role/pipe-role",
                    "DesiredState": "RUNNING",
                    "TargetParameters": {
                        "InputTemplate": "{\\"transformed\\": <$.body>}"
                    }
                }
                """)
        .when()
            .post("/v1/pipes/tmpl-pipe")
        .then()
            .statusCode(200)
            .body("CurrentState", equalTo("RUNNING"));
    }

    @Test
    @Order(33)
    void sendMessageForInputTemplate() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-tmpl-source")
            .formParam("MessageBody", "template-test-payload")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(34)
    void inputTemplateTransformsPayload() throws Exception {
        String body = null;
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);
            body = given()
                .contentType(SQS_CONTENT_TYPE)
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-tmpl-target")
                .formParam("MaxNumberOfMessages", "1")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().body().asString();

            if (body.contains("transformed")) {
                break;
            }
        }
        assertTrue(body.contains("transformed"),
                "Target should contain the InputTemplate-transformed message but got: " + body);
        assertTrue(body.contains("template-test-payload"),
                "InputTemplate should resolve $.body to the original message body but got: " + body);
    }

    @Test
    @Order(35)
    void cleanupInputTemplatePipe() {
        given()
            .contentType("application/json")
        .when()
            .delete("/v1/pipes/tmpl-pipe")
        .then()
            .statusCode(200);
    }

    // ──────────────────────────── DLQ Routing Tests ────────────────────────────

    @Test
    @Order(40)
    void createDlqSourceQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-dlq-source")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(41)
    void createDlqQueue() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "pipe-dlq-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(42)
    void createPipeWithDlqAndBadTarget() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Source": "arn:aws:sqs:us-east-1:000000000000:pipe-dlq-source",
                    "Target": "arn:aws:lambda:us-east-1:000000000000:function:nonexistent-fn",
                    "RoleArn": "arn:aws:iam::000000000000:role/pipe-role",
                    "DesiredState": "RUNNING",
                    "SourceParameters": {
                        "SqsQueueParameters": {
                            "DeadLetterConfig": {
                                "Arn": "arn:aws:sqs:us-east-1:000000000000:pipe-dlq-queue"
                            }
                        }
                    }
                }
                """)
        .when()
            .post("/v1/pipes/dlq-pipe")
        .then()
            .statusCode(200)
            .body("CurrentState", equalTo("RUNNING"));
    }

    @Test
    @Order(43)
    void sendMessageForDlqTest() {
        given()
            .contentType(SQS_CONTENT_TYPE)
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-dlq-source")
            .formParam("MessageBody", "should-end-up-in-dlq")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(44)
    void failedDeliveryRoutesToDlq() throws Exception {
        String body = null;
        for (int i = 0; i < 15; i++) {
            Thread.sleep(500);
            body = given()
                .contentType(SQS_CONTENT_TYPE)
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", "http://localhost:4566/000000000000/pipe-dlq-queue")
                .formParam("MaxNumberOfMessages", "1")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().body().asString();

            if (body.contains("should-end-up-in-dlq")) {
                break;
            }
        }
        assertTrue(body.contains("should-end-up-in-dlq"),
                "DLQ should contain the failed message but got: " + body);
    }

    @Test
    @Order(45)
    void cleanupDlqPipe() {
        given()
            .contentType("application/json")
        .when()
            .delete("/v1/pipes/dlq-pipe")
        .then()
            .statusCode(200);
    }
}
