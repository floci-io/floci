package io.github.hectorvent.floci.services.bedrock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(BedrockProxyBatchIntegrationTest.ProxyBatchBackendProfile.class)
class BedrockProxyBatchIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request";
    private static final int PORT = 18935;
    private static final String MAPPED_MODEL_ID = "anthropic.claude-3-haiku-20240307-v1:0";

    @Inject
    S3Service s3Service;

    @Inject
    BedrockService bedrockService;

    @Inject
    ObjectMapper objectMapper;

    private HttpServer backend;
    private final AtomicReference<String> nextResponseBody = new AtomicReference<>();
    private final AtomicReference<Integer> nextResponseStatus = new AtomicReference<>(200);

    @BeforeEach
    void setUp() throws IOException {
        bedrockService.clear();

        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        backend.createContext("/v1/chat/completions", exchange -> {
            byte[] resp = nextResponseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(nextResponseStatus.get(), resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        backend.start();
    }

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.stop(0);
        }
    }

    @Test
    void executeBatchJob_withProxyBackend() throws Exception {
        nextResponseBody.set("""
            {
              "choices": [{
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": "Hello from the OpenAI-compatible proxy batch!"}
              }],
              "usage": {"prompt_tokens": 10, "completion_tokens": 8, "total_tokens": 18}
            }
            """);

        String inputBucket = "proxy-batch-input-bucket";
        String outputBucket = "proxy-batch-output-bucket";
        s3Service.createBucket(inputBucket, "us-east-1");
        s3Service.createBucket(outputBucket, "us-east-1");

        String inputJsonl = """
            {"recordId": "rec-proxy-1", "modelInput": {"messages": [{"role": "user", "content": [{"text": "Hello proxy"}]}]}}
            """;
        s3Service.putObject(inputBucket, "prompts.jsonl", inputJsonl.getBytes(StandardCharsets.UTF_8), "application/jsonlines", Map.of());

        String jobArn = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "jobName": "proxy-batch-job",
                  "modelId": "%s",
                  "roleArn": "arn:aws:iam::000000000000:role/BedrockBatchRole",
                  "inputDataConfig": {"s3InputDataConfig": {"s3Uri": "s3://%s/prompts.jsonl"}},
                  "outputDataConfig": {"s3OutputDataConfig": {"s3Uri": "s3://%s/results"}}
                }
                """.formatted(MAPPED_MODEL_ID, inputBucket, outputBucket))
        .when()
            .post("/model-invocation-job")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("jobArn");

        // Verify Job Status
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/model-invocation-job/" + jobArn)
        .then()
            .statusCode(200)
            .body("status", equalTo("Completed"));

        String jobId = jobArn.substring(jobArn.lastIndexOf('/') + 1);

        // Verify Output JSONL file in S3
        String outputJsonlKey = "results/" + jobId + "/prompts.jsonl.out";
        S3Object outputObj = s3Service.getObject(outputBucket, outputJsonlKey);
        assertNotNull(outputObj, "Output JSONL file should exist in S3");
        String outputContent = new String(outputObj.getData(), StandardCharsets.UTF_8);
        JsonNode line = objectMapper.readTree(outputContent.trim());

        assertEquals("rec-proxy-1", line.path("recordId").asText());
        assertEquals("Hello from the OpenAI-compatible proxy batch!",
                line.path("modelOutput").path("output").path("message").path("content").get(0).path("text").asText());

        // Verify Manifest
        String manifestKey = "results/" + jobId + "/manifest.json.out";
        S3Object manifestObj = s3Service.getObject(outputBucket, manifestKey);
        assertNotNull(manifestObj);
        JsonNode manifest = objectMapper.readTree(manifestObj.getData());

        assertEquals(1, manifest.path("totalRecordCount").asLong());
        assertEquals(1, manifest.path("processedRecordCount").asLong());
        assertEquals(1, manifest.path("successRecordCount").asLong());
        assertEquals(0, manifest.path("errorRecordCount").asLong());
    }

    public static final class ProxyBatchBackendProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.bedrock-runtime.backend", "proxy",
                    "floci.services.bedrock-runtime.proxy.url", "http://127.0.0.1:" + PORT + "/v1",
                    "floci.services.bedrock-runtime.proxy.api-key", "test-key",
                    "floci.services.bedrock-runtime.proxy.model-mapping",
                            MAPPED_MODEL_ID + "=claude-3-haiku"
            );
        }
    }
}
