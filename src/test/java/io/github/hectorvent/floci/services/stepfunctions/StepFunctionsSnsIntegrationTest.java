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

/**
 * The {@code arn:aws:states:::sns:publish} and {@code arn:aws:states:::aws-sdk:sns:publish}
 * integrations, observed through an SQS queue subscribed to the topic with raw delivery so the
 * queue body is exactly the published {@code Message}.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StepFunctionsSnsIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String SQS_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String SNS_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final String MISSING_TOPIC_ARN = "arn:aws:sns:us-east-1:000000000000:sfn-sns-does-not-exist";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static String queueUrl;
    private static String topicArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(0)
    void setup_createTopicAndSubscribedQueue() {
        queueUrl = createQueue("sfn-sns-integration-queue");
        topicArn = createTopic("sfn-sns-integration-topic");
        String subscriptionArn = subscribeQueue(topicArn, queueUrl);
        setSubscriptionAttribute(subscriptionArn, "RawMessageDelivery", "true");
    }

    @Test
    @Order(1)
    void optimized_publish() throws Exception {
        String output = executeSfn("optimized-publish", "arn:aws:states:::sns:publish", """
                {
                    "TopicArn": "%s",
                    "Message": "hello optimized"
                }
                """.formatted(topicArn));

        JsonNode result = mapper.readTree(output);
        assertFalse(result.path("MessageId").asText().isBlank());

        JsonNode message = receiveSingleMessage(queueUrl);
        assertEquals("hello optimized", message.path("Body").asText());
        deleteMessage(queueUrl, message.path("ReceiptHandle").asText());
    }

    @Test
    @Order(2)
    void awsSdk_publish_withMessageAttributes() throws Exception {
        String output = executeSfn("aws-sdk-publish", "arn:aws:states:::aws-sdk:sns:publish", """
                {
                    "TopicArn": "%s",
                    "Message": "hello aws-sdk",
                    "Subject": "greeting",
                    "MessageAttributes": {
                        "my_attribute_no_1": {
                            "DataType": "String",
                            "StringValue": "value of my_attribute_no_1"
                        }
                    }
                }
                """.formatted(topicArn));

        JsonNode result = mapper.readTree(output);
        assertFalse(result.path("MessageId").asText().isBlank());

        JsonNode message = receiveSingleMessage(queueUrl);
        assertEquals("hello aws-sdk", message.path("Body").asText());
        assertEquals("value of my_attribute_no_1",
                message.path("MessageAttributes").path("my_attribute_no_1").path("StringValue").asText());
        deleteMessage(queueUrl, message.path("ReceiptHandle").asText());
    }

    @Test
    @Order(3)
    void optimized_waitForTaskToken_serializesMessageObject() throws Exception {
        String definition = buildStateMachineDefinition("arn:aws:states:::sns:publish.waitForTaskToken", """
                {
                    "TopicArn": "%s",
                    "Message": {
                        "Input": "callback requested",
                        "TaskToken.$": "$$.Task.Token"
                    }
                }
                """.formatted(topicArn));

        String smArn = createStateMachine("sns-wait-token-" + System.currentTimeMillis(), definition);
        String execArn = startExecution(smArn, "{}");

        JsonNode message = receiveSingleMessage(queueUrl);
        JsonNode body = mapper.readTree(message.path("Body").asText());
        assertEquals("callback requested", body.path("Input").asText());
        String taskToken = body.path("TaskToken").asText();
        assertFalse(taskToken.isBlank());

        sendTaskSuccess(taskToken, "{\"delivered\":true}");
        String output = waitForExecution(execArn);
        assertTrue(mapper.readTree(output).path("delivered").asBoolean());

        deleteMessage(queueUrl, message.path("ReceiptHandle").asText());
    }

    @Test
    @Order(4)
    void jsonata_publish_resolvesArguments() throws Exception {
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "Publish",
                    "States": {
                        "Publish": {
                            "Type": "Task",
                            "Resource": "arn:aws:states:::sns:publish",
                            "Arguments": {
                                "TopicArn": "%s",
                                "Message": "{%% $states.input.message %%}"
                            },
                            "End": true
                        }
                    }
                }
                """.formatted(topicArn);

        String smArn = createStateMachine("sns-jsonata-" + System.currentTimeMillis(), definition);
        String execArn = startExecution(smArn, "{\"message\":\"hello jsonata\"}");
        String output = waitForExecution(execArn);
        assertFalse(mapper.readTree(output).path("MessageId").asText().isBlank());

        JsonNode message = receiveSingleMessage(queueUrl);
        assertEquals("hello jsonata", message.path("Body").asText());
        deleteMessage(queueUrl, message.path("ReceiptHandle").asText());
    }

    @Test
    @Order(5)
    void optimized_missingTopic_failsWithSnsErrorName() throws Exception {
        String definition = buildStateMachineDefinition("arn:aws:states:::sns:publish", """
                {
                    "TopicArn": "%s",
                    "Message": "noop"
                }
                """.formatted(MISSING_TOPIC_ARN));

        String smArn = createStateMachine("sns-missing-topic-" + System.currentTimeMillis(), definition);
        Response failed = waitForFailedExecution(startExecution(smArn, "{}"));
        assertEquals("SNS.NotFoundException", failed.jsonPath().getString("error"));
        assertEquals("Topic does not exist.", failed.jsonPath().getString("cause"));
    }

    @Test
    @Order(6)
    void awsSdk_missingTopic_failsWithSdkStyleErrorName() throws Exception {
        String definition = buildStateMachineDefinition("arn:aws:states:::aws-sdk:sns:publish", """
                {
                    "TopicArn": "%s",
                    "Message": "noop"
                }
                """.formatted(MISSING_TOPIC_ARN));

        String smArn = createStateMachine("aws-sdk-sns-missing-topic-" + System.currentTimeMillis(), definition);
        Response failed = waitForFailedExecution(startExecution(smArn, "{}"));
        assertEquals("Sns.NotFoundException", failed.jsonPath().getString("error"));
    }

    @Test
    @Order(7)
    void optimized_missingMessage_isCatchable() throws Exception {
        String definition = """
                {
                    "StartAt": "Publish",
                    "States": {
                        "Publish": {
                            "Type": "Task",
                            "Resource": "arn:aws:states:::sns:publish",
                            "Parameters": {"TopicArn": "%s"},
                            "Catch": [{"ErrorEquals": ["SNS.InvalidParameterException"], "Next": "Recovered"}],
                            "End": true
                        },
                        "Recovered": {"Type": "Pass", "Result": {"recovered": true}, "End": true}
                    }
                }
                """.formatted(topicArn);

        String smArn = createStateMachine("sns-catch-" + System.currentTimeMillis(), definition);
        String output = waitForExecution(startExecution(smArn, "{}"));
        assertTrue(mapper.readTree(output).path("recovered").asBoolean());
    }

    @Test
    @Order(8)
    void cleanup() {
        deleteTopic(topicArn);
        deleteQueue(queueUrl);
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

    private static String createTopic(String name) {
        Response resp = given()
                .header("X-Amz-Target", "SNS_20100331.CreateTopic")
                .contentType(SNS_CONTENT_TYPE)
                .body("""
                        {"Name":"%s"}
                        """.formatted(name))
                .when()
                .post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("TopicArn");
    }

    private static String subscribeQueue(String topic, String queue) {
        Response resp = given()
                .header("X-Amz-Target", "SNS_20100331.Subscribe")
                .contentType(SNS_CONTENT_TYPE)
                .body("""
                        {"TopicArn":"%s","Protocol":"sqs","Endpoint":"%s"}
                        """.formatted(topic, queue))
                .when()
                .post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("SubscriptionArn");
    }

    private static void setSubscriptionAttribute(String subscriptionArn, String name, String value) {
        given()
                .header("X-Amz-Target", "SNS_20100331.SetSubscriptionAttributes")
                .contentType(SNS_CONTENT_TYPE)
                .body("""
                        {"SubscriptionArn":"%s","AttributeName":"%s","AttributeValue":"%s"}
                        """.formatted(subscriptionArn, name, value))
                .when()
                .post("/")
                .then()
                .statusCode(200);
    }

    private static void deleteTopic(String topic) {
        if (topic == null) {
            return;
        }
        given()
                .header("X-Amz-Target", "SNS_20100331.DeleteTopic")
                .contentType(SNS_CONTENT_TYPE)
                .body("""
                        {"TopicArn":"%s"}
                        """.formatted(topic))
                .when()
                .post("/")
                .then()
                .statusCode(200);
    }

    private JsonNode receiveSingleMessage(String queue) throws Exception {
        Response resp = given()
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .contentType(SQS_CONTENT_TYPE)
                .body("""
                        {"QueueUrl":"%s","MaxNumberOfMessages":1,"WaitTimeSeconds":1,"MessageAttributeNames":["All"]}
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
