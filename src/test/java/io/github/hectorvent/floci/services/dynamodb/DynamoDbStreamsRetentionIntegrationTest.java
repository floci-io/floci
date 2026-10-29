package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** floci.services.dynamodb.streams-max-records sets how many records each stream retains. */
@QuarkusTest
@TestProfile(DynamoDbStreamsRetentionIntegrationTest.RetentionProfile.class)
class DynamoDbStreamsRetentionIntegrationTest {

    public static final class RetentionProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.dynamodb.streams-max-records", "1500");
        }
    }

    private static final String TABLE = "StreamsRetention";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aStreamRetainsTheConfiguredNumberOfRecords() {
        String streamArn = dynamoDb("DynamoDB_20120810.CreateTable", """
                {
                    "TableName": "%s",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST",
                    "StreamSpecification": {"StreamEnabled": true, "StreamViewType": "KEYS_ONLY"}
                }
                """.formatted(TABLE)).getString("TableDescription.LatestStreamArn");
        for (int batch = 0; batch < 60; batch++) {
            List<String> puts = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                puts.add("{\"PutRequest\": {\"Item\": {\"pk\": {\"S\": \"item-%d\"}}}}".formatted(batch * 25 + i));
            }
            dynamoDb("DynamoDB_20120810.BatchWriteItem",
                    "{\"RequestItems\": {\"%s\": [%s]}}".formatted(TABLE, String.join(",", puts)));
        }

        String iterator = dynamoDb("DynamoDBStreams_20120810.GetShardIterator", """
                {"StreamArn": "%s", "ShardId": "%s", "ShardIteratorType": "TRIM_HORIZON"}
                """.formatted(streamArn, DynamoDbStreamService.SHARD_ID)).getString("ShardIterator");
        List<String> sequenceNumbers = new ArrayList<>();
        while (true) {
            JsonPath page = dynamoDb("DynamoDBStreams_20120810.GetRecords",
                    "{\"ShardIterator\": \"%s\", \"Limit\": 1000}".formatted(iterator));
            List<String> pageSequences = page.getList("Records.dynamodb.SequenceNumber");
            if (pageSequences.isEmpty()) {
                break;
            }
            sequenceNumbers.addAll(pageSequences);
            iterator = page.getString("NextShardIterator");
        }

        assertEquals(1500, sequenceNumbers.size(), "the stream must retain every record up to the configured cap");
        assertEquals("000000000000000000001", sequenceNumbers.getFirst(),
                "TRIM_HORIZON must start at the first record written");
    }

    private static JsonPath dynamoDb(String target, String body) {
        return given()
            .header("X-Amz-Target", target)
            .contentType("application/x-amz-json-1.0")
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath();
    }
}
