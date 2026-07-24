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

        given()
            .header("X-Amz-Target", "Kinesis_20131202.PutRecord")
            .contentType(CONTENT_TYPE)
            .body("""
                {"StreamName": "ui-inspection-records", "Data": "aGVsbG8=", "PartitionKey": "pk"}
                """)
        .when().post("/")
        .then().statusCode(200);

        given()
            .queryParam("StreamName", streamName)
        .when().get("/_aws/kinesis/records")
        .then()
            .statusCode(200)
            .body("records", hasSize(1))
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
}
