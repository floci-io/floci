package io.github.hectorvent.floci.services.kinesis;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
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
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(KINESIS_CONTENT_TYPE, ContentType.TEXT));
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
