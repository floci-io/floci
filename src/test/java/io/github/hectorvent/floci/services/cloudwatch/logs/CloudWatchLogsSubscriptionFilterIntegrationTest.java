package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end integration test verifying that PutLogEvents delivers matching log events
 * to a Kinesis data stream destination configured through PutSubscriptionFilter.
 */
@QuarkusTest
class CloudWatchLogsSubscriptionFilterIntegrationTest {

    private static final String KINESIS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String LOGS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response kinesis(String action, String body) {
        return given()
                .contentType(KINESIS_CONTENT_TYPE)
                .header("X-Amz-Target", "Kinesis_20131202." + action)
                .body(body)
                .post("/");
    }

    private static Response logs(String action, String body) {
        return given()
                .contentType(LOGS_CONTENT_TYPE)
                .header("X-Amz-Target", "Logs_20140328." + action)
                .body(body)
                .post("/");
    }

    private static byte[] decompress(byte[] compressed) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            in.transferTo(out);
        }
        return out.toByteArray();
    }

    @Test
    void deliversIngestedLogEventsToKinesisDestination() throws Exception {
        String streamName = "sub-stream-" + System.nanoTime();
        kinesis("CreateStream", "{\"StreamName\":\"" + streamName + "\",\"ShardCount\":1}")
                .then().statusCode(200);

        String streamArn = kinesis("DescribeStreamSummary", "{\"StreamName\":\"" + streamName + "\"}")
                .then().statusCode(200)
                .extract().jsonPath().getString("StreamDescriptionSummary.StreamARN");
        assertNotNull(streamArn);

        String group = "/sub/kinesis-" + System.nanoTime();
        String stream = "app-stream";
        logs("CreateLogGroup", "{\"logGroupName\":\"" + group + "\"}").then().statusCode(200);
        logs("CreateLogStream", "{\"logGroupName\":\"" + group + "\",\"logStreamName\":\"" + stream + "\"}")
                .then().statusCode(200);

        logs("PutSubscriptionFilter", """
                {"logGroupName":"%s","filterName":"kinesis-sink","filterPattern":"ERROR","destinationArn":"%s"}
                """.formatted(group, streamArn))
                .then().statusCode(200);

        long timestamp = 1759406400000L;
        logs("PutLogEvents", """
                {"logGroupName":"%s","logStreamName":"%s","logEvents":[
                  {"timestamp":%d,"message":"ERROR: database connection timed out"},
                  {"timestamp":%d,"message":"INFO: application started"}
                ]}
                """.formatted(group, stream, timestamp, timestamp + 10))
                .then().statusCode(200);

        String iterator = kinesis("GetShardIterator", """
                {"StreamName":"%s","ShardId":"shardId-000000000000","ShardIteratorType":"TRIM_HORIZON"}
                """.formatted(streamName))
                .then().statusCode(200)
                .extract().jsonPath().getString("ShardIterator");
        assertNotNull(iterator);

        Response getRecordsResponse = kinesis("GetRecords", """
                {"ShardIterator":"%s"}
                """.formatted(iterator))
                .then().statusCode(200)
                .extract().response();

        List<Map<String, Object>> records = getRecordsResponse.jsonPath().getList("Records");
        assertEquals(1, records.size());
        assertEquals(stream, records.getFirst().get("PartitionKey"));

        String base64Data = (String) records.getFirst().get("Data");
        assertNotNull(base64Data);

        byte[] compressed = Base64.getDecoder().decode(base64Data);
        byte[] decompressed = decompress(compressed);
        JsonNode root = MAPPER.readTree(decompressed);

        assertEquals("DATA_MESSAGE", root.path("messageType").asText());
        assertEquals(group, root.path("logGroup").asText());
        assertEquals(stream, root.path("logStream").asText());
        assertEquals(1, root.path("subscriptionFilters").size());
        assertEquals("kinesis-sink", root.path("subscriptionFilters").get(0).asText());

        JsonNode logEvents = root.path("logEvents");
        assertEquals(1, logEvents.size());
        assertEquals("ERROR: database connection timed out", logEvents.get(0).path("message").asText());
        assertEquals(timestamp, logEvents.get(0).path("timestamp").asLong());
        assertNotNull(logEvents.get(0).path("id").asText());
    }
}
