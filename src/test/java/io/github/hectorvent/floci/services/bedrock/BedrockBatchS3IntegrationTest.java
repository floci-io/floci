package io.github.hectorvent.floci.services.bedrock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class BedrockBatchS3IntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request";

    @Inject
    S3Service s3Service;

    @Inject
    BedrockService bedrockService;

    @Inject
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        bedrockService.clear();
    }

    @Test
    void executeBatchJob_endToEndS3InputAndOutputWithManifest() throws Exception {
        String inputBucket = "batch-e2e-input-bucket";
        String outputBucket = "batch-e2e-output-bucket";
        s3Service.createBucket(inputBucket, "us-east-1");
        s3Service.createBucket(outputBucket, "us-east-1");

        // Input JSONL with 2 records (1 Converse style, 1 Anthropic style)
        String inputJsonl = """
            {"recordId": "rec-001", "modelInput": {"messages": [{"role": "user", "content": [{"text": "Hello world"}]}]}}
            {"recordId": "rec-002", "modelInput": {"anthropic_version": "bedrock-2023-05-31", "messages": [{"role": "user", "content": [{"type": "text", "text": "What is AI?"}]}]}}
            """;
        s3Service.putObject(inputBucket, "data/prompts.jsonl", inputJsonl.getBytes(StandardCharsets.UTF_8), "application/jsonlines", Map.of());

        String jobArn = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "jobName": "e2e-batch-job",
                  "modelId": "anthropic.claude-3-haiku-20240307-v1:0",
                  "roleArn": "arn:aws:iam::000000000000:role/BedrockBatchRole",
                  "inputDataConfig": {"s3InputDataConfig": {"s3Uri": "s3://%s/data/prompts.jsonl"}},
                  "outputDataConfig": {"s3OutputDataConfig": {"s3Uri": "s3://%s/batch-results"}}
                }
                """.formatted(inputBucket, outputBucket))
        .when()
            .post("/model-invocation-job")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("jobArn");

        // Check Job Status
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/model-invocation-job/" + jobArn)
        .then()
            .statusCode(200)
            .body("status", equalTo("Completed"))
            .body("endTime", notNullValue());

        String jobId = jobArn.substring(jobArn.lastIndexOf('/') + 1);

        // Verify Output JSONL file in S3
        String outputJsonlKey = "batch-results/" + jobId + "/prompts.jsonl.out";
        S3Object outputObj = s3Service.getObject(outputBucket, outputJsonlKey);
        assertNotNull(outputObj, "Output JSONL file should exist in S3");
        String outputContent = new String(outputObj.getData(), StandardCharsets.UTF_8);
        String[] outputLines = outputContent.trim().split("\n");
        assertEquals(2, outputLines.length);

        JsonNode line1 = objectMapper.readTree(outputLines[0]);
        assertEquals("rec-001", line1.path("recordId").asText());
        assertTrue(line1.has("modelInput"));
        assertTrue(line1.has("modelOutput"));
        assertTrue(line1.path("modelOutput").has("output") || line1.path("modelOutput").has("content"));

        JsonNode line2 = objectMapper.readTree(outputLines[1]);
        assertEquals("rec-002", line2.path("recordId").asText());
        assertTrue(line2.has("modelInput"));
        assertTrue(line2.has("modelOutput"));

        // Verify Manifest File in S3
        String manifestKey = "batch-results/" + jobId + "/manifest.json.out";
        S3Object manifestObj = s3Service.getObject(outputBucket, manifestKey);
        assertNotNull(manifestObj, "Manifest file should exist in S3");
        JsonNode manifest = objectMapper.readTree(manifestObj.getData());

        assertEquals(2, manifest.path("totalRecordCount").asLong());
        assertEquals(2, manifest.path("processedRecordCount").asLong());
        assertEquals(2, manifest.path("successRecordCount").asLong());
        assertEquals(0, manifest.path("errorRecordCount").asLong());
    }

    @Test
    void executeBatchJob_partiallyCompletedWithErrors() throws Exception {
        String inputBucket = "batch-partial-input-bucket";
        String outputBucket = "batch-partial-output-bucket";
        s3Service.createBucket(inputBucket, "us-east-1");
        s3Service.createBucket(outputBucket, "us-east-1");

        // Input JSONL with 1 valid record and 1 malformed record
        String inputJsonl = """
            {"recordId": "rec-valid", "modelInput": {"messages": [{"role": "user", "content": [{"text": "Hello"}]}]}}
            {"recordId": "rec-broken"}
            """;
        s3Service.putObject(inputBucket, "mixed.jsonl", inputJsonl.getBytes(StandardCharsets.UTF_8), "application/jsonlines", Map.of());

        String jobArn = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "jobName": "partial-batch-job",
                  "modelId": "anthropic.claude-3-haiku-20240307-v1:0",
                  "roleArn": "arn:aws:iam::000000000000:role/BedrockBatchRole",
                  "inputDataConfig": {"s3InputDataConfig": {"s3Uri": "s3://%s/mixed.jsonl"}},
                  "outputDataConfig": {"s3OutputDataConfig": {"s3Uri": "s3://%s/results"}}
                }
                """.formatted(inputBucket, outputBucket))
        .when()
            .post("/model-invocation-job")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("jobArn");

        // Check Job Status
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/model-invocation-job/" + jobArn)
        .then()
            .statusCode(200)
            .body("status", equalTo("PartiallyCompleted"))
            .body("message", equalTo("Batch job partially completed with 1 successes and 1 errors."));

        String jobId = jobArn.substring(jobArn.lastIndexOf('/') + 1);

        // Verify Output JSONL file
        String outputJsonlKey = "results/" + jobId + "/mixed.jsonl.out";
        S3Object outputObj = s3Service.getObject(outputBucket, outputJsonlKey);
        assertNotNull(outputObj);
        String outputContent = new String(outputObj.getData(), StandardCharsets.UTF_8);
        String[] outputLines = outputContent.trim().split("\n");
        assertEquals(2, outputLines.length);

        JsonNode validLine = objectMapper.readTree(outputLines[0]);
        assertEquals("rec-valid", validLine.path("recordId").asText());
        assertTrue(validLine.has("modelOutput"));

        JsonNode brokenLine = objectMapper.readTree(outputLines[1]);
        assertEquals("rec-broken", brokenLine.path("recordId").asText());
        assertTrue(brokenLine.has("error"));
        assertEquals("400", brokenLine.path("error").path("errorCode").asText());

        // Verify Manifest
        String manifestKey = "results/" + jobId + "/manifest.json.out";
        S3Object manifestObj = s3Service.getObject(outputBucket, manifestKey);
        assertNotNull(manifestObj);
        JsonNode manifest = objectMapper.readTree(manifestObj.getData());

        assertEquals(2, manifest.path("totalRecordCount").asLong());
        assertEquals(2, manifest.path("processedRecordCount").asLong());
        assertEquals(1, manifest.path("successRecordCount").asLong());
        assertEquals(1, manifest.path("errorRecordCount").asLong());
    }

    @Test
    void executeBatchJob_missingInputS3ObjectFails() {
        String outputBucket = "batch-fail-output-bucket";
        s3Service.createBucket(outputBucket, "us-east-1");

        String jobArn = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "jobName": "missing-file-job",
                  "modelId": "anthropic.claude-3-haiku-20240307-v1:0",
                  "roleArn": "arn:aws:iam::000000000000:role/BedrockBatchRole",
                  "inputDataConfig": {"s3InputDataConfig": {"s3Uri": "s3://non-existent-bucket/non-existent.jsonl"}},
                  "outputDataConfig": {"s3OutputDataConfig": {"s3Uri": "s3://%s/results"}}
                }
                """.formatted(outputBucket))
        .when()
            .post("/model-invocation-job")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("jobArn");

        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/model-invocation-job/" + jobArn)
        .then()
            .statusCode(200)
            .body("status", equalTo("Failed"))
            .body("endTime", notNullValue());
    }
}
