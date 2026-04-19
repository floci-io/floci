package io.github.hectorvent.floci.services.kinesis;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KinesisIntegrationTest {

    private static final String KINESIS_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createStream() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.CreateStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "list-shards-test", "ShardCount": 2}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void listShardsByStreamName() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.ListShards")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "list-shards-test"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Shards.size()", equalTo(2))
            .body("Shards[0].ShardId", equalTo("shardId-000000000000"))
            .body("Shards[1].ShardId", equalTo("shardId-000000000001"))
            .body("Shards[0].HashKeyRange.StartingHashKey", notNullValue())
            .body("Shards[0].HashKeyRange.EndingHashKey", equalTo("340282366920938463463374607431768211455"))
            .body("Shards[0].SequenceNumberRange.StartingSequenceNumber", notNullValue());
    }

    @Test
    @Order(3)
    void listShardsByStreamArn() {
        String streamArn = given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStreamSummary")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "list-shards-test"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("StreamDescriptionSummary.StreamARN");

        given()
            .header("X-Amz-Target", "Kinesis_20131202.ListShards")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamARN\": \"" + streamArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Shards.size()", equalTo(2));
    }

    @Test
    @Order(20)
    void describeStreamByArn() {
        String streamArn = given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStreamSummary")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "list-shards-test"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("StreamDescriptionSummary.StreamARN");

        given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamARN\": \"" + streamArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("StreamDescription.StreamName", equalTo("list-shards-test"))
            .body("StreamDescription.StreamARN", equalTo(streamArn));
    }

    @Test
    @Order(21)
    void putAndGetRecordsByArn() {
        // Use a dedicated stream to avoid interference from shard splits on list-shards-test
        given()
            .header("X-Amz-Target", "Kinesis_20131202.CreateStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "arn-put-get-test", "ShardCount": 1}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String streamArn = given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStreamSummary")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "arn-put-get-test"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("StreamDescriptionSummary.StreamARN");

        // PutRecord by ARN
        given()
            .header("X-Amz-Target", "Kinesis_20131202.PutRecord")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamARN\": \"" + streamArn + "\", \"Data\": \"dGVzdA==\", \"PartitionKey\": \"pk1\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SequenceNumber", notNullValue());

        // GetShardIterator by ARN
        String iterator = given()
            .header("X-Amz-Target", "Kinesis_20131202.GetShardIterator")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamARN\": \"" + streamArn + "\", \"ShardId\": \"shardId-000000000000\", \"ShardIteratorType\": \"TRIM_HORIZON\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("ShardIterator");

        // GetRecords to verify the put worked
        given()
            .header("X-Amz-Target", "Kinesis_20131202.GetRecords")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"ShardIterator\": \"" + iterator + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Records.size()", equalTo(1))
            .body("Records[0].PartitionKey", equalTo("pk1"))
            .body("Records[0].Data", equalTo("dGVzdA=="));
    }

    @Test
    @Order(22)
    void operationWithoutStreamNameOrArnReturns400() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(23)
    void operationWithMalformedArnReturns400() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamARN": "arn:aws:kinesis:us-east-1:123456789012:not-a-stream"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(4)
    void listShardsAfterSplitReturnsAllShards() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.SplitShard")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {
                    "StreamName": "list-shards-test",
                    "ShardToSplit": "shardId-000000000000",
                    "NewStartingHashKey": "170141183460469231731687303715884105728"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "Kinesis_20131202.ListShards")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "list-shards-test"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Shards.size()", equalTo(4));
    }

    @Test
    @Order(5)
    void listShardsWithShardFilterAtLatestExcludesClosedShards() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.ListShards")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "list-shards-test", "ShardFilter": {"Type": "AT_LATEST"}}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Shards.size()", equalTo(3))
            .body("Shards.findAll { !it.SequenceNumberRange.containsKey('EndingSequenceNumber') }.size()", equalTo(3));
    }

    @Test
    @Order(6)
    void listShardsWithoutStreamNameOrArn() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.ListShards")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(7)
    void increaseStreamRetentionPeriod() {
        // Create a dedicated stream for retention tests
        given()
            .header("X-Amz-Target", "Kinesis_20131202.CreateStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "retention-test", "ShardCount": 1}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Increase from default 24 to 48
        given()
            .header("X-Amz-Target", "Kinesis_20131202.IncreaseStreamRetentionPeriod")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "retention-test", "RetentionPeriodHours": 48}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify via DescribeStream
        given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "retention-test"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("StreamDescription.RetentionPeriodHours", equalTo(48));
    }

    @Test
    @Order(8)
    void decreaseStreamRetentionPeriod() {
        // Decrease from 48 back to 24
        given()
            .header("X-Amz-Target", "Kinesis_20131202.DecreaseStreamRetentionPeriod")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "retention-test", "RetentionPeriodHours": 24}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify
        given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "retention-test"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("StreamDescription.RetentionPeriodHours", equalTo(24));
    }

    @Test
    @Order(9)
    void increaseRetentionPeriodRejectsTooHigh() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.IncreaseStreamRetentionPeriod")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "retention-test", "RetentionPeriodHours": 9999}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
    }

    @Test
    @Order(10)
    void decreaseRetentionPeriodRejectsTooLow() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.DecreaseStreamRetentionPeriod")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "retention-test", "RetentionPeriodHours": 12}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
    }

    @Test
    @Order(11)
    void increaseRetentionPeriodRejectsLowerValue() {
        // First increase to 48
        given()
            .header("X-Amz-Target", "Kinesis_20131202.IncreaseStreamRetentionPeriod")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "retention-test", "RetentionPeriodHours": 48}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Try to "increase" to 24 (lower) - should fail
        given()
            .header("X-Amz-Target", "Kinesis_20131202.IncreaseStreamRetentionPeriod")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "retention-test", "RetentionPeriodHours": 24}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
    }

    @Test
    @Order(12)
    void increaseRetentionPeriodSameValueIsNoOp() {
        // Stream is currently at 48 (from Order 11). Increase to 48 should be a no-op,
        // not an InvalidArgumentException. See #342: real AWS accepts same-value
        // (terraform-provider-aws stream.go Create path calls this unconditionally on
        // stream creation with the configured retention_period, so every default-retention
        // TF stream would fail on first apply if AWS rejected same-value).
        given()
            .header("X-Amz-Target", "Kinesis_20131202.IncreaseStreamRetentionPeriod")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "retention-test", "RetentionPeriodHours": 48}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "retention-test"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("StreamDescription.RetentionPeriodHours", equalTo(48));
    }

    @Test
    @Order(13)
    void decreaseRetentionPeriodSameValueIsNoOp() {
        // Stream is still at 48. Decrease to 48 should also be a no-op.
        given()
            .header("X-Amz-Target", "Kinesis_20131202.DecreaseStreamRetentionPeriod")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "retention-test", "RetentionPeriodHours": 48}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "retention-test"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("StreamDescription.RetentionPeriodHours", equalTo(48));
    }

    @Test
    @Order(14)
    void listShardsForNonExistentStream() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.ListShards")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "non-existent-stream"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(30)
    void updateStreamModeRoundTrip() {
        // Create a dedicated stream so other ordered tests aren't affected by the mode flip.
        given()
            .header("X-Amz-Target", "Kinesis_20131202.CreateStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "stream-mode-test", "ShardCount": 1}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Default mode is PROVISIONED.
        String streamArn = given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStreamSummary")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "stream-mode-test"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("StreamDescriptionSummary.StreamModeDetails.StreamMode", equalTo("PROVISIONED"))
            .extract().jsonPath().getString("StreamDescriptionSummary.StreamARN");

        // Switch to ON_DEMAND.
        given()
            .header("X-Amz-Target", "Kinesis_20131202.UpdateStreamMode")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamARN\": \"" + streamArn + "\", \"StreamModeDetails\": {\"StreamMode\": \"ON_DEMAND\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // DescribeStream now reports ON_DEMAND.
        given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "stream-mode-test"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("StreamDescription.StreamModeDetails.StreamMode", equalTo("ON_DEMAND"));

        // Calling UpdateStreamMode again with the same mode is a no-op (mirrors retention semantics)
        // and is what terraform-provider-aws does on every refresh. See #440.
        given()
            .header("X-Amz-Target", "Kinesis_20131202.UpdateStreamMode")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamARN\": \"" + streamArn + "\", \"StreamModeDetails\": {\"StreamMode\": \"ON_DEMAND\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(31)
    void createStreamWithOnDemandMode() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.CreateStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "on-demand-create-test", "ShardCount": 1, "StreamModeDetails": {"StreamMode": "ON_DEMAND"}}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "on-demand-create-test"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("StreamDescription.StreamModeDetails.StreamMode", equalTo("ON_DEMAND"));
    }

    @Test
    @Order(32)
    void updateStreamModeRequiresStreamArn() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.UpdateStreamMode")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "stream-mode-test", "StreamModeDetails": {"StreamMode": "ON_DEMAND"}}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
    }

    @Test
    @Order(33)
    void updateStreamModeRejectsInvalidMode() {
        String streamArn = given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStreamSummary")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "stream-mode-test"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("StreamDescriptionSummary.StreamARN");

        given()
            .header("X-Amz-Target", "Kinesis_20131202.UpdateStreamMode")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamARN\": \"" + streamArn + "\", \"StreamModeDetails\": {\"StreamMode\": \"BOGUS\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
    }

    @Test
    @Order(40)
    void putRecordReturnsRealShardIdAcrossShards() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.CreateStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "shardid-put-test", "ShardCount": 2}
                """)
        .when().post("/").then().statusCode(200);

        java.util.Set<String> reported = new java.util.HashSet<>();
        // Probe enough partition keys that hash(pk) % 2 hits both shards. With 50 keys the
        // odds of single-shard routing are ~1 in 2^49, so this is effectively deterministic.
        for (int i = 0; i < 50 && reported.size() < 2; i++) {
            String pk = "pk-" + i;
            String shardId = given()
                .header("X-Amz-Target", "Kinesis_20131202.PutRecord")
                .contentType(KINESIS_CONTENT_TYPE)
                .body("{\"StreamName\": \"shardid-put-test\", \"Data\": \"dGVzdA==\", \"PartitionKey\": \"" + pk + "\"}")
            .when().post("/")
            .then().statusCode(200)
                .body("ShardId", startsWith("shardId-"))
                .extract().jsonPath().getString("ShardId");
            reported.add(shardId);
        }
        org.junit.jupiter.api.Assertions.assertEquals(2, reported.size(),
                "PutRecord should report distinct ShardIds across partition keys on a 2-shard stream");
    }

    @Test
    @Order(41)
    void putRecordsReturnsRealShardIdPerEntry() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.CreateStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "shardid-putrecords-test", "ShardCount": 2}
                """)
        .when().post("/").then().statusCode(200);

        StringBuilder body = new StringBuilder("{\"StreamName\": \"shardid-putrecords-test\", \"Records\": [");
        for (int i = 0; i < 10; i++) {
            if (i > 0) body.append(',');
            body.append("{\"Data\": \"dGVzdA==\", \"PartitionKey\": \"batch-pk-").append(i).append("\"}");
        }
        body.append("]}");

        java.util.List<String> shardIds = given()
            .header("X-Amz-Target", "Kinesis_20131202.PutRecords")
            .contentType(KINESIS_CONTENT_TYPE)
            .body(body.toString())
        .when().post("/")
        .then().statusCode(200)
            .body("FailedRecordCount", equalTo(0))
            .body("Records.size()", equalTo(10))
            .extract().jsonPath().getList("Records.ShardId", String.class);

        java.util.Set<String> distinct = new java.util.HashSet<>(shardIds);
        org.junit.jupiter.api.Assertions.assertTrue(distinct.size() >= 2,
                "PutRecords should route 10 mixed partition keys across at least 2 shards, got: " + distinct);
        for (String sid : shardIds) {
            org.junit.jupiter.api.Assertions.assertTrue(sid != null && sid.startsWith("shardId-"),
                    "Each record must report a real shardId, got: " + sid);
        }
    }

    @Test
    @Order(42)
    void putRecordShardIdMatchesGetRecordsShard() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.CreateStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "shardid-roundtrip-test", "ShardCount": 2}
                """)
        .when().post("/").then().statusCode(200);

        String putShardId = given()
            .header("X-Amz-Target", "Kinesis_20131202.PutRecord")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamName\": \"shardid-roundtrip-test\", \"Data\": \"aGVsbG8=\", \"PartitionKey\": \"rt-pk\"}")
        .when().post("/")
        .then().statusCode(200)
            .extract().jsonPath().getString("ShardId");

        String iterator = given()
            .header("X-Amz-Target", "Kinesis_20131202.GetShardIterator")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamName\": \"shardid-roundtrip-test\", \"ShardId\": \"" + putShardId + "\", \"ShardIteratorType\": \"TRIM_HORIZON\"}")
        .when().post("/")
        .then().statusCode(200)
            .extract().jsonPath().getString("ShardIterator");

        given()
            .header("X-Amz-Target", "Kinesis_20131202.GetRecords")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"ShardIterator\": \"" + iterator + "\"}")
        .when().post("/")
        .then().statusCode(200)
            .body("Records.size()", equalTo(1))
            .body("Records[0].PartitionKey", equalTo("rt-pk"));

        String otherShardId = putShardId.endsWith("0") ? "shardId-000000000001" : "shardId-000000000000";
        String otherIterator = given()
            .header("X-Amz-Target", "Kinesis_20131202.GetShardIterator")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamName\": \"shardid-roundtrip-test\", \"ShardId\": \"" + otherShardId + "\", \"ShardIteratorType\": \"TRIM_HORIZON\"}")
        .when().post("/")
        .then().statusCode(200)
            .extract().jsonPath().getString("ShardIterator");

        given()
            .header("X-Amz-Target", "Kinesis_20131202.GetRecords")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"ShardIterator\": \"" + otherIterator + "\"}")
        .when().post("/")
        .then().statusCode(200)
            .body("Records.size()", equalTo(0));
    }

    // --- AT_TIMESTAMP iterator coverage ---

    private String atTimestampCreateStream(String name) {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.CreateStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamName\": \"" + name + "\", \"ShardCount\": 1}")
        .when().post("/").then().statusCode(200);
        return "shardId-000000000000";
    }

    private String atTimestampPutAndGetSequence(String stream, String data) {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.PutRecord")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamName\": \"" + stream + "\", \"Data\": \"" + data + "\", \"PartitionKey\": \"pk\"}")
        .when().post("/").then().statusCode(200);
        return "ok";
    }

    private String atTimestampIterator(String stream, String shardId, double timestampSec) {
        return given()
            .header("X-Amz-Target", "Kinesis_20131202.GetShardIterator")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamName\": \"" + stream + "\", \"ShardId\": \"" + shardId
                + "\", \"ShardIteratorType\": \"AT_TIMESTAMP\", \"Timestamp\": " + timestampSec + "}")
        .when().post("/")
        .then().statusCode(200)
            .extract().jsonPath().getString("ShardIterator");
    }

    @Test
    @Order(50)
    void atTimestampReturnsRecordsAtAndAfter() throws InterruptedException {
        String stream = "at-timestamp-basic";
        String shardId = atTimestampCreateStream(stream);

        long[] tsMillis = new long[5];
        for (int i = 0; i < 5; i++) {
            tsMillis[i] = System.currentTimeMillis();
            atTimestampPutAndGetSequence(stream, "cmVjMA=="); // "rec0" base64
            Thread.sleep(100);
        }

        // tsMillis[i] is captured just before rec[i], so rec[i].arrival >= tsMillis[i]
        // and rec[i-1].arrival < tsMillis[i]. AT_TIMESTAMP at tsMillis[2] returns rec 2,3,4.
        double targetSec = tsMillis[2] / 1000.0;
        String iterator = atTimestampIterator(stream, shardId, targetSec);

        given()
            .header("X-Amz-Target", "Kinesis_20131202.GetRecords")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"ShardIterator\": \"" + iterator + "\"}")
        .when().post("/")
        .then().statusCode(200)
            .body("Records.size()", equalTo(3));
    }

    @Test
    @Order(51)
    void atTimestampBeforeFirstRecordReturnsAll() {
        String stream = "at-timestamp-before";
        String shardId = atTimestampCreateStream(stream);
        for (int i = 0; i < 3; i++) {
            atTimestampPutAndGetSequence(stream, "YWJj");
        }

        // Timestamp at epoch 1s (way before any record).
        String iterator = atTimestampIterator(stream, shardId, 1.0);
        given()
            .header("X-Amz-Target", "Kinesis_20131202.GetRecords")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"ShardIterator\": \"" + iterator + "\"}")
        .when().post("/")
        .then().statusCode(200)
            .body("Records.size()", equalTo(3));
    }

    @Test
    @Order(52)
    void atTimestampFutureReturnsZeroAndValidContinuation() {
        String stream = "at-timestamp-future";
        String shardId = atTimestampCreateStream(stream);
        for (int i = 0; i < 3; i++) {
            atTimestampPutAndGetSequence(stream, "eHl6");
        }

        double futureSec = (System.currentTimeMillis() + 3_600_000) / 1000.0;
        String iterator = atTimestampIterator(stream, shardId, futureSec);

        String nextIter = given()
            .header("X-Amz-Target", "Kinesis_20131202.GetRecords")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"ShardIterator\": \"" + iterator + "\"}")
        .when().post("/")
        .then().statusCode(200)
            .body("Records.size()", equalTo(0))
            .body("NextShardIterator", not(isEmptyOrNullString()))
            .extract().jsonPath().getString("NextShardIterator");

        // NextShardIterator should be a valid (caught-up) iterator — re-use returns 0 records, no error.
        given()
            .header("X-Amz-Target", "Kinesis_20131202.GetRecords")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"ShardIterator\": \"" + nextIter + "\"}")
        .when().post("/")
        .then().statusCode(200)
            .body("Records.size()", equalTo(0));
    }

    @Test
    @Order(53)
    void atTimestampOnEmptyShardReturnsZero() {
        String stream = "at-timestamp-empty";
        String shardId = atTimestampCreateStream(stream);

        String iterator = atTimestampIterator(stream, shardId, System.currentTimeMillis() / 1000.0);
        given()
            .header("X-Amz-Target", "Kinesis_20131202.GetRecords")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"ShardIterator\": \"" + iterator + "\"}")
        .when().post("/")
        .then().statusCode(200)
            .body("Records.size()", equalTo(0))
            .body("NextShardIterator", not(isEmptyOrNullString()));
    }

    @Test
    @Order(54)
    void atTimestampWithoutTimestampParamIs400() {
        String stream = "at-timestamp-missing-param";
        atTimestampCreateStream(stream);

        given()
            .header("X-Amz-Target", "Kinesis_20131202.GetShardIterator")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamName\": \"" + stream
                + "\", \"ShardId\": \"shardId-000000000000\", \"ShardIteratorType\": \"AT_TIMESTAMP\"}")
        .when().post("/")
        .then().statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
    }

    @Test
    @Order(56)
    void atTimestampWithNonNumericTimestampIs400() {
        String stream = "at-timestamp-bad-type";
        atTimestampCreateStream(stream);

        given()
            .header("X-Amz-Target", "Kinesis_20131202.GetShardIterator")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamName\": \"" + stream
                + "\", \"ShardId\": \"shardId-000000000000\", \"ShardIteratorType\": \"AT_TIMESTAMP\", \"Timestamp\": \"not-a-number\"}")
        .when().post("/")
        .then().statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"));
    }

    @Test
    @Order(55)
    void trimHorizonIteratorStillWorksAfterEncodingBump() {
        // Regression: 5-part old iterators should still decode via split(-1) compat,
        // and the new TRIM_HORIZON/LATEST/AT_SEQUENCE_NUMBER paths must not trip over the new 6th slot.
        String stream = "post-bump-trim-horizon";
        String shardId = atTimestampCreateStream(stream);
        atTimestampPutAndGetSequence(stream, "YQ==");
        atTimestampPutAndGetSequence(stream, "Yg==");

        String iterator = given()
            .header("X-Amz-Target", "Kinesis_20131202.GetShardIterator")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamName\": \"" + stream + "\", \"ShardId\": \"" + shardId
                + "\", \"ShardIteratorType\": \"TRIM_HORIZON\"}")
        .when().post("/")
        .then().statusCode(200)
            .extract().jsonPath().getString("ShardIterator");

        given()
            .header("X-Amz-Target", "Kinesis_20131202.GetRecords")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"ShardIterator\": \"" + iterator + "\"}")
        .when().post("/")
        .then().statusCode(200)
            .body("Records.size()", equalTo(2));
    }
}
