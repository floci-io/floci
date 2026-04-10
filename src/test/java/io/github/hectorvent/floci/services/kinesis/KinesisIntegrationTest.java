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
}
