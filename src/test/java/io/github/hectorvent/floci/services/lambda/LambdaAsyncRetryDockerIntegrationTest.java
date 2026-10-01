package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs a real failing function to prove the asynchronous retries wait as they do on AWS (scaled down by
 * the test config to 1 s then 2 s) and that Invoke's Qualifier selects the alias's asynchronous settings.
 */
@QuarkusTest
class LambdaAsyncRetryDockerIntegrationTest {

    private static final String BASE_PATH = "/2015-03-31";
    private static final String CONFIG_PATH = "/2019-09-25";
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/lambda-role";
    private static final Duration RECEIVE_DEADLINE = Duration.ofSeconds(60);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    DockerClient dockerClient;

    @Inject
    SqsService sqsService;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for Lambda asynchronous retry integration tests");
    }

    @Test
    void anAliasInvocationIsRetriedWithGrowingWaitsUnderTheAliasSettings() throws Exception {
        Fixture fixture = setUp();
        try {
            long started = System.nanoTime();
            given()
                    .header("X-Amz-Invocation-Type", "Event")
                    .body("{\"probe\":\"alias\"}")
            .when()
                    .post(BASE_PATH + "/functions/" + fixture.functionName() + "/invocations?Qualifier=live")
            .then()
                    .statusCode(202);

            JsonNode record = awaitRecord(fixture.aliasQueueUrl());
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertTrue(elapsedMillis >= 3000,
                    "the retries should wait 1 s and then 2 s, but the record arrived after " + elapsedMillis + " ms");
            assertEquals("RetriesExhausted", record.path("requestContext").path("condition").asText());
            assertEquals(3, record.path("requestContext").path("approximateInvokeCount").asInt());
            String functionArn = record.path("requestContext").path("functionArn").asText();
            assertTrue(functionArn.endsWith(":1"), "functionArn should name the executed version: " + functionArn);
            assertEquals("1", record.path("responseContext").path("executedVersion").asText());
            assertEquals("alias", record.path("requestPayload").path("probe").asText());
            assertTrue(receiveOnce(fixture.latestQueueUrl()).isEmpty(),
                    "the unqualified OnFailure destination should receive nothing");
        } finally {
            deleteFunction(fixture.functionName());
        }
    }

    @Test
    void anUnqualifiedInvocationUsesTheFunctionSettings() throws Exception {
        Fixture fixture = setUp();
        try {
            given()
                    .header("X-Amz-Invocation-Type", "Event")
                    .body("{\"probe\":\"latest\"}")
            .when()
                    .post(BASE_PATH + "/functions/" + fixture.functionName() + "/invocations")
            .then()
                    .statusCode(202);

            JsonNode record = awaitRecord(fixture.latestQueueUrl());

            assertEquals("RetriesExhausted", record.path("requestContext").path("condition").asText());
            assertEquals(1, record.path("requestContext").path("approximateInvokeCount").asInt());
            assertEquals("latest", record.path("requestPayload").path("probe").asText());
            assertTrue(receiveOnce(fixture.aliasQueueUrl()).isEmpty(),
                    "the alias OnFailure destination should receive nothing");
        } finally {
            deleteFunction(fixture.functionName());
        }
    }

    private Fixture setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String functionName = "async-retry-" + suffix;
        String latestQueueUrl = createQueue("async-retry-latest-" + suffix);
        String aliasQueueUrl = createQueue("async-retry-alias-" + suffix);

        given()
                .contentType("application/json")
                .body("""
                    {
                      "FunctionName": "%s",
                      "Runtime": "nodejs20.x",
                      "Role": "%s",
                      "Handler": "index.handler",
                      "Timeout": 10,
                      "Code": {"ZipFile": "%s"}
                    }
                    """.formatted(functionName, ROLE, failingZipBase64()))
        .when()
                .post(BASE_PATH + "/functions")
        .then()
                .statusCode(201);

        given()
                .contentType("application/json")
                .body("{}")
        .when()
                .post(BASE_PATH + "/functions/" + functionName + "/versions")
        .then()
                .statusCode(201);

        given()
                .contentType("application/json")
                .body("{\"Name\":\"live\",\"FunctionVersion\":\"1\"}")
        .when()
                .post(BASE_PATH + "/functions/" + functionName + "/aliases")
        .then()
                .statusCode(201);

        given()
                .contentType("application/json")
                .body("{\"MaximumRetryAttempts\":0,\"DestinationConfig\":{\"OnFailure\":{\"Destination\":\"%s\"}}}"
                        .formatted(queueArn(latestQueueUrl)))
        .when()
                .put(CONFIG_PATH + "/functions/" + functionName + "/event-invoke-config")
        .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .body("{\"MaximumRetryAttempts\":2,\"DestinationConfig\":{\"OnFailure\":{\"Destination\":\"%s\"}}}"
                        .formatted(queueArn(aliasQueueUrl)))
        .when()
                .put(CONFIG_PATH + "/functions/" + functionName + "/event-invoke-config?Qualifier=live")
        .then()
                .statusCode(200);

        return new Fixture(functionName, latestQueueUrl, aliasQueueUrl);
    }

    private String createQueue(String queueName) {
        Queue queue = RequestScopes.callAs(ACCOUNT, () -> sqsService.createQueue(queueName, Map.of(), REGION));
        return queue.getQueueUrl();
    }

    private String queueArn(String queueUrl) {
        return RequestScopes.callAs(ACCOUNT, () ->
                sqsService.getQueueAttributes(queueUrl, List.of("QueueArn"), REGION).get("QueueArn"));
    }

    private List<Message> receiveOnce(String queueUrl) {
        return RequestScopes.callAs(ACCOUNT, () -> sqsService.receiveMessage(queueUrl, 1, 30, 0, REGION));
    }

    private JsonNode awaitRecord(String queueUrl) throws Exception {
        long deadline = System.nanoTime() + RECEIVE_DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            List<Message> messages = receiveOnce(queueUrl);
            if (!messages.isEmpty()) {
                return MAPPER.readTree(messages.get(0).getBody());
            }
            Thread.sleep(250);
        }
        return fail("no OnFailure record reached " + queueUrl + " within " + RECEIVE_DEADLINE.toSeconds() + " s");
    }

    private static void deleteFunction(String functionName) {
        given().when().delete(BASE_PATH + "/functions/" + functionName);
    }

    private static String failingZipBase64() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("exports.handler = async () => { throw new Error('boom'); };\n"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception expected) {
            // Docker is unreachable, so requireDocker() skips the test rather than failing it.
            return false;
        }
    }

    private record Fixture(String functionName, String latestQueueUrl, String aliasQueueUrl) {
    }
}
