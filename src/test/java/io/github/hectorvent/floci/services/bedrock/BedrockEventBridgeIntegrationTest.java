package io.github.hectorvent.floci.services.bedrock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class BedrockEventBridgeIntegrationTest {

    private static final String SQS_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String EVENT_BRIDGE_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request";

    @Inject
    S3Service s3Service;

    @Inject
    BedrockService bedrockService;

    @Inject
    ObjectMapper objectMapper;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void setUp() {
        bedrockService.clear();
    }

    @Test
    void batchJob_emitsEventBridgeStateChangeEvents() throws Exception {
        // 1. Create SQS Queue
        String queueUrl = given()
                .contentType(SQS_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"bedrock-eb-queue\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("QueueUrl");

        String queueArn = given()
                .contentType(SQS_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"AttributeNames\":[\"QueueArn\"]}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");

        // 2. Create EventBridge Rule for Bedrock state change
        String ruleName = "bedrock-state-change-rule";
        String eventPattern = "{\"source\":[\"aws.bedrock\"],\"detail-type\":[\"Batch Inference Job State Change\"]}";
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .body("{\"Name\":\"" + ruleName + "\",\"EventPattern\":\"" + eventPattern.replace("\"", "\\\"") + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200);

        // 3. Put Target to Rule
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("""
                    {
                      "Rule": "%s",
                      "Targets": [{"Id": "target-sqs", "Arn": "%s"}]
                    }
                    """.formatted(ruleName, queueArn))
                .when()
                .post("/")
                .then()
                .statusCode(200);

        // 4. Create S3 input data and execute batch job
        s3Service.createBucket("eb-test-input", "us-east-1");
        s3Service.createBucket("eb-test-output", "us-east-1");
        String jsonl = """
            {"recordId": "rec-1", "modelInput": {"messages": [{"role": "user", "content": [{"text": "Ping"}]}]}}
            """;
        s3Service.putObject("eb-test-input", "prompts.jsonl", jsonl.getBytes(StandardCharsets.UTF_8), "application/jsonlines", Map.of());

        String jobArn = given()
                .contentType("application/json")
                .header("Authorization", AUTH_HEADER)
                .body("""
                    {
                      "jobName": "eb-test-batch-job",
                      "modelId": "anthropic.claude-3-haiku-20240307-v1:0",
                      "roleArn": "arn:aws:iam::000000000000:role/BedrockRole",
                      "inputDataConfig": {"s3InputDataConfig": {"s3Uri": "s3://eb-test-input/prompts.jsonl"}},
                      "outputDataConfig": {"s3OutputDataConfig": {"s3Uri": "s3://eb-test-output/results"}}
                    }
                    """)
                .when()
                .post("/model-invocation-job")
                .then()
                .statusCode(201)
                .extract().jsonPath().getString("jobArn");

        // 5. Receive messages from SQS
        List<JsonNode> receivedEvents = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            var resp = given()
                    .contentType(SQS_CONTENT_TYPE)
                    .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                    .body("{\"QueueUrl\":\"" + queueUrl + "\",\"MaxNumberOfMessages\":10,\"WaitTimeSeconds\":1}")
                    .when()
                    .post("/")
                    .then()
                    .statusCode(200)
                    .extract().jsonPath();

            List<Map<String, Object>> messages = resp.getList("Messages");
            if (messages != null) {
                for (Map<String, Object> msg : messages) {
                    String body = (String) msg.get("Body");
                    JsonNode eventNode = objectMapper.readTree(body);
                    receivedEvents.add(eventNode);
                }
            }
            if (receivedEvents.size() >= 5) {
                break;
            }
        }

        assertTrue(receivedEvents.size() >= 5, "Expected at least 5 events (Submitted, Validating, Scheduled, InProgress, Completed), but got: " + receivedEvents.size());

        List<String> statuses = new ArrayList<>();
        for (JsonNode event : receivedEvents) {
            assertEquals("aws.bedrock", event.path("source").asText());
            assertEquals("Batch Inference Job State Change", event.path("detail-type").asText());
            assertTrue(event.path("resources").isArray());
            assertEquals(jobArn, event.path("resources").get(0).asText());

            JsonNode detail = event.path("detail");
            assertEquals(jobArn, detail.path("batchJobArn").asText());
            assertEquals("eb-test-batch-job", detail.path("batchJobName").asText());
            statuses.add(detail.path("status").asText());
        }

        assertTrue(statuses.contains("Submitted"), "Event list should contain Submitted state");
        assertTrue(statuses.contains("Validating"), "Event list should contain Validating state");
        assertTrue(statuses.contains("Scheduled"), "Event list should contain Scheduled state");
        assertTrue(statuses.contains("InProgress"), "Event list should contain InProgress state");
        assertTrue(statuses.contains("Completed"), "Event list should contain Completed state");
    }
}
