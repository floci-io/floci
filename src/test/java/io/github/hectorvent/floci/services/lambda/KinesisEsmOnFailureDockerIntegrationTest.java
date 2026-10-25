package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.testing.DockerAvailability;
import io.github.hectorvent.floci.testing.SqsTestQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs a real function that throws on a poison record behind a Kinesis mapping with
 * {@code MaximumRetryAttempts = 1}: the batch is invoked twice, its invocation record reaches the
 * SQS OnFailure destination, and the shard goes on with the next record.
 */
@QuarkusTest
class KinesisEsmOnFailureDockerIntegrationTest {

    private static final String BASE_PATH = "/2015-03-31";
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/lambda-role";
    private static final Duration DEADLINE = Duration.ofSeconds(90);

    @Inject
    DockerClient dockerClient;

    @Inject
    SqsService sqsService;

    @Inject
    KinesisService kinesisService;

    @Inject
    EsmStore esmStore;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DockerAvailability.answers(dockerClient),
                "Docker daemon must be available for the Kinesis OnFailure integration test");
    }

    @Test
    void exhaustedBatchReachesTheSqsDestinationAndTheShardGoesOn() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String streamName = "esm-onfailure-" + suffix;
        String functionName = "esm-onfailure-" + suffix;
        String streamArn = RequestScopes.callAs(ACCOUNT, () ->
                kinesisService.createStream(streamName, 1, REGION).getStreamArn());
        SqsTestQueue queue = SqsTestQueue.create(sqsService, ACCOUNT, REGION, "esm-onfailure-");
        createFunction(functionName);
        String uuid = null;
        try {
            String poisonSequence = putRecord(streamName, "poison");
            uuid = given()
                    .contentType("application/json")
                    .body("""
                        {
                          "FunctionName": "%s",
                          "EventSourceArn": "%s",
                          "StartingPosition": "TRIM_HORIZON",
                          "MaximumRetryAttempts": 1,
                          "DestinationConfig": {"OnFailure": {"Destination": "%s"}}
                        }
                        """.formatted(functionName, streamArn, queue.arn()))
            .when()
                    .post(BASE_PATH + "/event-source-mappings/")
            .then()
                    .statusCode(202)
                    .extract().path("UUID");

            JsonNode record = queue.awaitJson(DEADLINE);

            assertEquals("RetryAttemptsExhausted", record.at("/requestContext/condition").asText(), record.toString());
            assertEquals(2, record.at("/requestContext/approximateInvokeCount").asInt());
            assertEquals("Unhandled", record.at("/responseContext/functionError").asText());
            assertEquals(poisonSequence, record.at("/KinesisBatchInfo/startSequenceNumber").asText());
            assertEquals(poisonSequence, record.at("/KinesisBatchInfo/endSequenceNumber").asText());
            assertEquals(1, record.at("/KinesisBatchInfo/batchSize").asInt());
            assertEquals(streamArn, record.at("/KinesisBatchInfo/streamArn").asText());

            String goodSequence = putRecord(streamName, "good");
            awaitCheckpoint(uuid, goodSequence);
            assertTrue(queue.receive().isEmpty(), "the good record must not reach the OnFailure destination");
        } finally {
            if (uuid != null) {
                given().when().delete(BASE_PATH + "/event-source-mappings/" + uuid);
            }
            given().when().delete(BASE_PATH + "/functions/" + functionName);
            RequestScopes.runAs(ACCOUNT, () -> kinesisService.deleteStream(streamName, REGION));
            queue.delete();
        }
    }

    private String putRecord(String streamName, String data) {
        return RequestScopes.callAs(ACCOUNT, () ->
                kinesisService.putRecord(streamName, data.getBytes(StandardCharsets.UTF_8), "pk", REGION));
    }

    private void createFunction(String functionName) throws Exception {
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
                    """.formatted(functionName, ROLE, poisonRaisingZipBase64()))
        .when()
                .post(BASE_PATH + "/functions")
        .then()
                .statusCode(201);
    }

    private void awaitCheckpoint(String uuid, String sequenceNumber) throws InterruptedException {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        Map<String, String> checkpoints = Map.of();
        while (System.nanoTime() < deadline) {
            checkpoints = esmStore.get(uuid).orElseThrow().getShardSequenceNumbers();
            if (checkpoints.containsValue(sequenceNumber)) {
                return;
            }
            Thread.sleep(250);
        }
        fail("the shard was not checkpointed at " + sequenceNumber + " within " + DEADLINE.toSeconds()
                + " s, checkpoints: " + checkpoints);
    }

    private static String poisonRaisingZipBase64() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("""
                    exports.handler = async (event) => {
                      for (const record of event.Records) {
                        if (Buffer.from(record.kinesis.data, 'base64').toString() === 'poison') {
                          throw new Error('poison record');
                        }
                      }
                    };
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
