package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StepFunctionsSqsIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String SQS_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static String queueUrl;
    private static String callbackQueueUrl;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(0)
    void setup_createQueues() {
        queueUrl = createQueue("sfn-sqs-integration-queue");
        callbackQueueUrl = createQueue("sfn-sqs-callback-queue");
    }

    @Test
    @Order(1)
    void optimized_sendMessage() throws Exception {
        String output = executeSfn("optimized-send", "arn:aws:states:::sqs:sendMessage", """
                {
                    "QueueUrl": "%s",
                    "MessageBody": "hello optimized"
                }
                """.formatted(queueUrl));

        JsonNode result = mapper.readTree(output);
        assertTrue(result.has("MessageId"));
        assertTrue(result.has("MD5OfMessageBody"));

        JsonNode message = receiveSingleMessage(queueUrl);
        assertEquals("hello optimized", message.path("Body").asText());
        deleteMessage(queueUrl, message.path("ReceiptHandle").asText());
    }

    @Test
    @Order(2)
    void awsSdk_sendMessage() throws Exception {
        String output = executeSfn("aws-sdk-send", "arn:aws:states:::aws-sdk:sqs:sendMessage", """
                {
                    "QueueUrl": "%s",
                    "MessageBody": "hello aws-sdk"
                }
                """.formatted(queueUrl));

        JsonNode result = mapper.readTree(output);
        assertTrue(result.has("MessageId"));
        assertTrue(result.has("MD5OfMessageBody"));

        JsonNode message = receiveSingleMessage(queueUrl);
        assertEquals("hello aws-sdk", message.path("Body").asText());
        deleteMessage(queueUrl, message.path("ReceiptHandle").asText());
    }

    @Test
    @Order(3)
    void optimized_waitForTaskToken_serializesMessageBodyObject() throws Exception {
        String definition = buildStateMachineDefinition("arn:aws:states:::sqs:sendMessage.waitForTaskToken", """
                {
                    "QueueUrl": "%s",
                    "MessageBody": {
                        "Message": "callback requested",
                        "TaskToken.$": "$$.Task.Token"
                    }
                }
                """.formatted(callbackQueueUrl));

        String smArn = createStateMachine("optimized-wait-token-" + System.currentTimeMillis(), definition);
        String execArn = startExecution(smArn, "{}");

        JsonNode message = receiveSingleMessage(callbackQueueUrl);
        JsonNode body = mapper.readTree(message.path("Body").asText());
        assertEquals("callback requested", body.path("Message").asText());
        String taskToken = body.path("TaskToken").asText();
        assertFalse(taskToken.isBlank());

        sendTaskSuccess(taskToken, "{\"delivered\":true}");
        String output = waitForExecution(execArn);
        assertTrue(mapper.readTree(output).path("delivered").asBoolean());

        deleteMessage(callbackQueueUrl, message.path("ReceiptHandle").asText());
    }

    @Test
    @Order(4)
    void awsSdk_nonExistentQueue_fails_withSdkStyleErrorName() throws Exception {
        String definition = buildStateMachineDefinition("arn:aws:states:::aws-sdk:sqs:sendMessage", """
                {
                    "QueueUrl": "http://localhost:4566/000000000000/does-not-exist",
                    "MessageBody": "noop"
                }
                """);

        String smArn = createStateMachine("aws-sdk-sqs-missing-queue-" + System.currentTimeMillis(), definition);
        String execArn = startExecution(smArn, "{}");
        Response failed = waitForFailedExecution(execArn);
        assertEquals("Sqs.QueueDoesNotExistException", failed.jsonPath().getString("error"));
    }

    @Test
    @Order(5)
    void cleanup_deleteQueues() {
        deleteQueue(queueUrl);
        deleteQueue(callbackQueueUrl);
    }

    private static String createQueue(String queueName) {
        Response resp = given()
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .contentType(SQS_CONTENT_TYPE)
                .body("""
                        {"QueueName":"%s"}
                        """.formatted(queueName))
                .when()
                .post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("QueueUrl");
    }

    private JsonNode receiveSingleMessage(String queue) throws Exception {
        Response resp = given()
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .contentType(SQS_CONTENT_TYPE)
                .body("""
                        {"QueueUrl":"%s","MaxNumberOfMessages":1,"WaitTimeSeconds":1}
                        """.formatted(queue))
                .when()
                .post("/");
        resp.then().statusCode(200);
        JsonNode messages = mapper.readTree(resp.body().asString()).path("Messages");
        assertEquals(1, messages.size(), "Expected one message");
        return messages.get(0);
    }

    private static void deleteMessage(String queue, String receiptHandle) {
        given()
                .header("X-Amz-Target", "AmazonSQS.DeleteMessage")
                .contentType(SQS_CONTENT_TYPE)
                .body("""
                        {"QueueUrl":"%s","ReceiptHandle":"%s"}
                        """.formatted(queue, receiptHandle))
                .when()
                .post("/")
                .then()
                .statusCode(200);
    }

    private static void deleteQueue(String queue) {
        if (queue == null) {
            return;
        }
        given()
                .header("X-Amz-Target", "AmazonSQS.DeleteQueue")
                .contentType(SQS_CONTENT_TYPE)
                .body("""
                        {"QueueUrl":"%s"}
                        """.formatted(queue))
                .when()
                .post("/")
                .then()
                .statusCode(200);
    }

    private String executeSfn(String nameSuffix, String resource, String parameters) throws Exception {
        String definition = buildStateMachineDefinition(resource, parameters);
        String smArn = createStateMachine(nameSuffix + "-" + System.currentTimeMillis(), definition);
        String execArn = startExecution(smArn, "{}");
        return waitForExecution(execArn);
    }

    private String buildStateMachineDefinition(String resource, String parameters) {
        return """
                {
                    "StartAt": "Action",
                    "States": {
                        "Action": {
                            "Type": "Task",
                            "Resource": "%s",
                            "Parameters": %s,
                            "End": true
                        }
                    }
                }
                """.formatted(resource, parameters.strip());
    }

    private String createStateMachine(String name, String definition) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {
                            "name": "%s",
                            "definition": %s,
                            "roleArn": "%s"
                        }
                        """.formatted(name, quote(definition), ROLE_ARN))
                .when()
                .post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("stateMachineArn");
    }

    private String startExecution(String smArn, String input) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {
                            "stateMachineArn": "%s",
                            "input": %s
                        }
                        """.formatted(smArn, quote(input)))
                .when()
                .post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("executionArn");
    }

    private void sendTaskSuccess(String taskToken, String output) {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.SendTaskSuccess")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {
                            "taskToken": %s,
                            "output": %s
                        }
                        """.formatted(quote(taskToken), quote(output)))
                .when()
                .post("/")
                .then()
                .statusCode(200);
    }

    private String waitForExecution(String execArn) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Response resp = describeExecution(execArn);
            String status = resp.jsonPath().getString("status");
            if ("SUCCEEDED".equals(status)) {
                return resp.jsonPath().getString("output");
            }
            if ("FAILED".equals(status) || "ABORTED".equals(status)) {
                fail("Execution " + status + ": " + resp.body().asString());
            }
            Thread.sleep(100);
        }
        fail("Execution did not complete within timeout");
        return null;
    }

    private Response waitForFailedExecution(String execArn) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Response resp = describeExecution(execArn);
            String status = resp.jsonPath().getString("status");
            if ("FAILED".equals(status)) {
                return resp;
            }
            if ("SUCCEEDED".equals(status)) {
                fail("Execution should have failed but succeeded: " + resp.body().asString());
            }
            Thread.sleep(100);
        }
        fail("Execution did not complete within timeout");
        return null;
    }

    private Response describeExecution(String execArn) {
        return given()
                .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"executionArn": "%s"}
                        """.formatted(execArn))
                .when()
                .post("/");
    }

    private static String quote(String raw) {
        return "\"" + raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
