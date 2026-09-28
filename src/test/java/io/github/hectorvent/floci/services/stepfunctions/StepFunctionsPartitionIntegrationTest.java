package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A state machine created under a China signing scope carries {@code arn:aws-cn:states:...}
 * and, as the CDK generates it, Task resources of the form {@code arn:aws-cn:states:::sqs:sendMessage}.
 * Those must dispatch exactly like their commercial spelling.
 */
@QuarkusTest
class StepFunctionsPartitionIntegrationTest {

    private static final String REGION = "cn-north-1";
    private static final String JSON_1_0 = "application/x-amz-json-1.0";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void chinaStateMachineDispatchesItsOwnPartitionsIntegrationIds() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String queueUrl = createQueue("sfn-partition-" + suffix);

        for (String resource : new String[] {
                "arn:aws-cn:states:::sqs:sendMessage",
                "arn:aws-cn:states:::aws-sdk:sqs:sendMessage"}) {
            String definition = """
                    {
                      "StartAt": "Send",
                      "States": {
                        "Send": {
                          "Type": "Task",
                          "Resource": "%s",
                          "Parameters": {"QueueUrl": "%s", "MessageBody": "from %s"},
                          "End": true
                        }
                      }
                    }
                    """.formatted(resource, queueUrl, resource);
            String stateMachineArn = createStateMachine("partition-" + suffix + "-" + (resource.contains("aws-sdk") ? "sdk" : "opt"), definition);
            assertTrue(stateMachineArn.startsWith("arn:aws-cn:states:" + REGION + ":"), stateMachineArn);

            String executionArn = startExecution(stateMachineArn);
            JsonNode execution = awaitTerminal(executionArn);
            assertEquals("SUCCEEDED", execution.path("status").asText(),
                    resource + " -> " + execution.path("error").asText() + ": " + execution.path("cause").asText());
            assertTrue(MAPPER.readTree(execution.path("output").asText()).has("MessageId"), execution.toString());

            JsonNode message = receiveSingleMessage(queueUrl);
            assertEquals("from " + resource, message.path("Body").asText());
        }
    }

    private static String auth(String service) {
        return PartitionMatrix.sigV4Auth(REGION, service);
    }

    private String createQueue(String name) {
        Response response = given()
                .header("Authorization", auth("sqs"))
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .contentType(JSON_1_0)
                .body("{\"QueueName\":\"" + name + "\"}")
                .when().post("/");
        response.then().statusCode(200);
        String queueUrl = response.jsonPath().getString("QueueUrl");
        cleanup.register(() -> given()
                .header("Authorization", auth("sqs"))
                .header("X-Amz-Target", "AmazonSQS.DeleteQueue")
                .contentType(JSON_1_0)
                .body("{\"QueueUrl\":\"" + queueUrl + "\"}")
                .when().post("/"));
        return queueUrl;
    }

    private JsonNode receiveSingleMessage(String queueUrl) throws Exception {
        Response response = given()
                .header("Authorization", auth("sqs"))
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .contentType(JSON_1_0)
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"MaxNumberOfMessages\":1,\"WaitTimeSeconds\":1}")
                .when().post("/");
        response.then().statusCode(200);
        JsonNode messages = MAPPER.readTree(response.body().asString()).path("Messages");
        assertEquals(1, messages.size(), "expected one message: " + messages);
        given()
                .header("Authorization", auth("sqs"))
                .header("X-Amz-Target", "AmazonSQS.DeleteMessage")
                .contentType(JSON_1_0)
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"ReceiptHandle\":\""
                        + messages.get(0).path("ReceiptHandle").asText() + "\"}")
                .when().post("/").then().statusCode(200);
        return messages.get(0);
    }

    private String createStateMachine(String name, String definition) throws Exception {
        String body = MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("name", name)
                .put("definition", definition)
                .put("roleArn", "arn:aws-cn:iam::000000000000:role/test-role"));
        Response response = given()
                .header("Authorization", auth("states"))
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(JSON_1_0)
                .body(body)
                .when().post("/");
        response.then().statusCode(200);
        String arn = response.jsonPath().getString("stateMachineArn");
        cleanup.register(() -> given()
                .header("Authorization", auth("states"))
                .header("X-Amz-Target", "AWSStepFunctions.DeleteStateMachine")
                .contentType(JSON_1_0)
                .body("{\"stateMachineArn\":\"" + arn + "\"}")
                .when().post("/"));
        return arn;
    }

    private String startExecution(String stateMachineArn) {
        Response response = given()
                .header("Authorization", auth("states"))
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(JSON_1_0)
                .body("{\"stateMachineArn\":\"" + stateMachineArn + "\",\"input\":\"{}\"}")
                .when().post("/");
        response.then().statusCode(200);
        return response.jsonPath().getString("executionArn");
    }

    private JsonNode awaitTerminal(String executionArn) {
        return await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).until(() -> {
            Response response = given()
                    .header("Authorization", auth("states"))
                    .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                    .contentType(JSON_1_0)
                    .body("{\"executionArn\":\"" + executionArn + "\"}")
                    .when().post("/");
            response.then().statusCode(200);
            return MAPPER.readTree(response.body().asString());
        }, execution -> !"RUNNING".equals(execution.path("status").asText()));
    }
}
