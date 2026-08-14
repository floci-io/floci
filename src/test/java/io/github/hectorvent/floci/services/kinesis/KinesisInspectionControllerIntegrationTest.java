package io.github.hectorvent.floci.services.kinesis;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class KinesisInspectionControllerIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void streamsEndpointReturnsKinesisStreamsForUiInspection() {
        String streamName = "ui-inspection-stream";
        createStream(streamName, 2);

        given()
        .when().get("/_aws/kinesis/streams")
        .then()
            .statusCode(200)
            .body("streams.StreamName", hasItem(streamName))
            .body("streams.find { it.StreamName == '" + streamName + "' }.StreamStatus", equalTo("ACTIVE"))
            .body("streams.find { it.StreamName == '" + streamName + "' }.OpenShardCount", equalTo(2))
            .body("streams.find { it.StreamName == '" + streamName + "' }.Shards", hasSize(2));
    }

    @Test
    void recordsEndpointReturnsRecordsWithoutConsumingThem() {
        String streamName = "ui-inspection-records";
        createStream(streamName, 1);

        putRecord(streamName, "aGVsbG8=", "pk");

        given()
            .queryParam("StreamName", streamName)
        .when().get("/_aws/kinesis/records")
        .then()
            .statusCode(200)
            .body("records", hasSize(1))
            .body("records[0].ShardId", equalTo("shardId-000000000000"))
            .body("records[0].PartitionKey", equalTo("pk"))
            .body("records[0].Data", equalTo("aGVsbG8="));

        given()
            .queryParam("StreamName", streamName)
        .when().get("/_aws/kinesis/records")
        .then()
            .statusCode(200)
            .body("records", hasSize(1));
    }

    @Test
    void recordsEndpointHonorsLimit() {
        String streamName = "ui-inspection-record-limit";
        createStream(streamName, 1);
        putRecord(streamName, "b25l", "pk-1");    // oldest
        putRecord(streamName, "dHdv", "pk-2");    // middle
        putRecord(streamName, "dGhyZWU=", "pk-3"); // newest

        // Limit=2 should return the two NEWEST records (pk-2, pk-3) in
        // ascending arrival-timestamp order, not the two oldest.
        given()
            .queryParam("StreamName", streamName)
            .queryParam("Limit", 2)
        .when().get("/_aws/kinesis/records")
        .then()
            .statusCode(200)
            .body("records", hasSize(2))
            .body("records[0].PartitionKey", equalTo("pk-2"))
            .body("records[1].PartitionKey", equalTo("pk-3"));
    }

    @Test
    void recordsEndpointRejectsNonPositiveLimit() {
        String streamName = "ui-inspection-record-limit-zero";
        createStream(streamName, 1);

        given()
            .queryParam("StreamName", streamName)
            .queryParam("Limit", 0)
        .when().get("/_aws/kinesis/records")
        .then()
            .statusCode(400)
            .body("message", equalTo("Limit must be greater than 0"));
    }

    @Test
    void recordsEndpointRejectsUnknownShard() {
        String streamName = "ui-inspection-unknown-shard";
        createStream(streamName, 1);

        given()
            .queryParam("StreamName", streamName)
            .queryParam("ShardId", "shardId-999999999999")
        .when().get("/_aws/kinesis/records")
        .then()
            .statusCode(400);
    }

    @Test
    void recordsEndpointRequiresStreamName() {
        given()
        .when().get("/_aws/kinesis/records")
        .then()
            .statusCode(400)
            .body("message", equalTo("StreamName query parameter is required"));
    }

    private static void createStream(String streamName, int shardCount) {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.CreateStream")
            .contentType(CONTENT_TYPE)
            .body("""
                {"StreamName": "%s", "ShardCount": %d}
                """.formatted(streamName, shardCount))
        .when().post("/")
        .then().statusCode(200);
    }

    private static void putRecord(String streamName, String data, String partitionKey) {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.PutRecord")
            .contentType(CONTENT_TYPE)
            .body("""
                {"StreamName": "%s", "Data": "%s", "PartitionKey": "%s"}
                """.formatted(streamName, data, partitionKey))
        .when().post("/")
        .then().statusCode(200);
    }
}
