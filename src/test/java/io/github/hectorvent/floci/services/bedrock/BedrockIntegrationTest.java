package io.github.hectorvent.floci.services.bedrock;

import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class BedrockIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request";

    @Inject
    S3Service s3Service;

    @Inject
    BedrockService bedrockService;

    @BeforeEach
    void setUp() {
        bedrockService.clear();
    }

    @Test
    void createModelInvocationJob_validationFailures() {
        // Missing jobName
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "modelId": "anthropic.claude-3-haiku-20240307-v1:0",
                  "roleArn": "arn:aws:iam::000000000000:role/BedrockRole",
                  "inputDataConfig": {"s3InputDataConfig": {"s3Uri": "s3://b/in.jsonl"}},
                  "outputDataConfig": {"s3OutputDataConfig": {"s3Uri": "s3://b/out"}}
                }
                """)
        .when()
            .post("/model-invocation-job")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        // Missing modelId
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "jobName": "test-job",
                  "roleArn": "arn:aws:iam::000000000000:role/BedrockRole",
                  "inputDataConfig": {"s3InputDataConfig": {"s3Uri": "s3://b/in.jsonl"}},
                  "outputDataConfig": {"s3OutputDataConfig": {"s3Uri": "s3://b/out"}}
                }
                """)
        .when()
            .post("/model-invocation-job")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createAndGetModelInvocationJob_happyPath() {
        s3Service.createBucket("rest-input-b", "us-east-1");
        s3Service.createBucket("rest-output-b", "us-east-1");

        String jsonl = """
            {"recordId": "rec-1", "modelInput": {"anthropic_version": "bedrock-2023-05-31", "messages": [{"role": "user", "content": [{"type": "text", "text": "Hello"}]}]}}
            """;
        s3Service.putObject("rest-input-b", "prompts.jsonl", jsonl.getBytes(StandardCharsets.UTF_8), "application/jsonlines", java.util.Map.of());

        String jobArn = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "jobName": "my-rest-batch-job",
                  "modelId": "anthropic.claude-3-haiku-20240307-v1:0",
                  "roleArn": "arn:aws:iam::000000000000:role/BedrockRole",
                  "inputDataConfig": {"s3InputDataConfig": {"s3Uri": "s3://rest-input-b/prompts.jsonl"}},
                  "outputDataConfig": {"s3OutputDataConfig": {"s3Uri": "s3://rest-output-b/results"}}
                }
                """)
        .when()
            .post("/model-invocation-job")
        .then()
            .statusCode(201)
            .body("jobArn", startsWith("arn:aws:bedrock:us-east-1:000000000000:model-invocation-job/"))
            .extract().jsonPath().getString("jobArn");

        // Get by ARN
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            given()
                .header("Authorization", AUTH_HEADER)
            .when()
                .get("/model-invocation-job/" + jobArn)
            .then()
                .statusCode(200)
                .body("jobArn", equalTo(jobArn))
                .body("jobName", equalTo("my-rest-batch-job"))
                .body("status", equalTo("Completed"))
                .body("submitTime", notNullValue())
                .body("endTime", notNullValue())
                .body("inputDataConfig.s3InputDataConfig.s3Uri", equalTo("s3://rest-input-b/prompts.jsonl"))
                .body("outputDataConfig.s3OutputDataConfig.s3Uri", equalTo("s3://rest-output-b/results"));
        });
    }

    @Test
    void getModelInvocationJob_notFound() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/model-invocation-job/non-existent-job")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void stopModelInvocationJob_alreadyCompletedThrows() {
        s3Service.createBucket("stop-input-b", "us-east-1");
        s3Service.createBucket("stop-output-b", "us-east-1");
        String jsonl = """
            {"recordId": "rec-1", "modelInput": {"anthropic_version": "bedrock-2023-05-31", "messages": [{"role": "user", "content": [{"type": "text", "text": "Hello"}]}]}}
            """;
        s3Service.putObject("stop-input-b", "prompts.jsonl", jsonl.getBytes(StandardCharsets.UTF_8), "application/jsonlines", java.util.Map.of());

        String jobArn = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "jobName": "completed-job",
                  "modelId": "anthropic.claude-3-haiku-20240307-v1:0",
                  "roleArn": "arn:aws:iam::000000000000:role/BedrockRole",
                  "inputDataConfig": {"s3InputDataConfig": {"s3Uri": "s3://stop-input-b/prompts.jsonl"}},
                  "outputDataConfig": {"s3OutputDataConfig": {"s3Uri": "s3://stop-output-b/results"}}
                }
                """)
        .when()
            .post("/model-invocation-job")
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("jobArn");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            given()
                .header("Authorization", AUTH_HEADER)
            .when()
                .get("/model-invocation-job/" + jobArn)
            .then()
                .statusCode(200)
                .body("status", equalTo("Completed"));
        });

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/model-invocation-job/" + jobArn + "/stop")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void listModelInvocationJobs_filterAndPagination() {
        s3Service.createBucket("list-input-b", "us-east-1");
        s3Service.createBucket("list-output-b", "us-east-1");
        s3Service.putObject("list-input-b", "p.jsonl", "{}".getBytes(StandardCharsets.UTF_8), "application/jsonlines", java.util.Map.of());

        for (int i = 1; i <= 3; i++) {
            given()
                .contentType("application/json")
                .header("Authorization", AUTH_HEADER)
                .body("""
                    {
                      "jobName": "job-alpha-%d",
                      "modelId": "anthropic.claude-3-haiku-20240307-v1:0",
                      "roleArn": "arn:aws:iam::000000000000:role/BedrockRole",
                      "inputDataConfig": {"s3InputDataConfig": {"s3Uri": "s3://list-input-b/p.jsonl"}},
                      "outputDataConfig": {"s3OutputDataConfig": {"s3Uri": "s3://list-output-b/results"}}
                    }
                    """.formatted(i))
            .when()
                .post("/model-invocation-job")
            .then()
                .statusCode(201);
        }

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                  "jobName": "job-beta-special",
                  "modelId": "anthropic.claude-3-haiku-20240307-v1:0",
                  "roleArn": "arn:aws:iam::000000000000:role/BedrockRole",
                  "inputDataConfig": {"s3InputDataConfig": {"s3Uri": "s3://list-input-b/p.jsonl"}},
                  "outputDataConfig": {"s3OutputDataConfig": {"s3Uri": "s3://list-output-b/results"}}
                }
                """)
        .when()
            .post("/model-invocation-job")
        .then()
            .statusCode(201);

        // List all
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/model-invocation-jobs")
        .then()
            .statusCode(200)
            .body("invocationJobSummaries", hasSize(4));

        // Filter by nameContains
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("nameContains", "special")
        .when()
            .get("/model-invocation-jobs")
        .then()
            .statusCode(200)
            .body("invocationJobSummaries", hasSize(1))
            .body("invocationJobSummaries[0].jobName", equalTo("job-beta-special"));

        // Pagination with maxResults=2
        String nextToken = given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("maxResults", 2)
        .when()
            .get("/model-invocation-jobs")
        .then()
            .statusCode(200)
            .body("invocationJobSummaries", hasSize(2))
            .body("nextToken", notNullValue())
            .extract().jsonPath().getString("nextToken");

        // Next page
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("maxResults", 2)
            .queryParam("nextToken", nextToken)
        .when()
            .get("/model-invocation-jobs")
        .then()
            .statusCode(200)
            .body("invocationJobSummaries", hasSize(2));

        // Invalid submitTimeAfter date format
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("submitTimeAfter", "invalid-date")
        .when()
            .get("/model-invocation-jobs")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }
}
