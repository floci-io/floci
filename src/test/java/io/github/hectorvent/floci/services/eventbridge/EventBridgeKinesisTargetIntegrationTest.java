package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * EventBridge rule → Kinesis: the record body is the input path, and the partition key is read
 * from the original event, so {@code InputPath $.detail} still allows {@code PartitionKeyPath
 * $.detail.id}.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgeKinesisTargetIntegrationTest {

    private static final String STREAM = "eb-kinesis-target";
    private static final String RULE = "eb-kinesis-target-rule";
    private static final String JSON_CT = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void setupStreamAndRule() {
        given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", "Kinesis_20131202.CreateStream")
            .body("""
                    {"StreamName": "%s", "ShardCount": 1}
                    """.formatted(STREAM))
        .when().post("/")
        .then().statusCode(200);

        given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", "AWSEvents.PutRule")
            .body("""
                    {
                      "Name": "%s",
                      "EventPattern": "{\\"detail\\": {\\"type\\": [{\\"anything-but\\": [\\"fault\\"]}]}}"
                    }
                    """.formatted(RULE))
        .when().post("/")
        .then().statusCode(200);

        given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", "AWSEvents.PutTargets")
            .body("""
                    {
                      "Rule": "%s",
                      "Targets": [{
                        "Id": "stream",
                        "Arn": "arn:aws:kinesis:us-east-1:000000000000:stream/%s",
                        "InputPath": "$.detail",
                        "KinesisParameters": {"PartitionKeyPath": "$.detail.id"}
                      }]
                    }
                    """.formatted(RULE, STREAM))
        .when().post("/")
        .then().statusCode(200)
            .body("FailedEntryCount", equalTo(0));

        given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", "AWSEvents.ListTargetsByRule")
            .body("""
                    {"Rule": "%s"}
                    """.formatted(RULE))
        .when().post("/")
        .then().statusCode(200)
            .body("Targets[0].InputPath", equalTo("$.detail"))
            .body("Targets[0].KinesisParameters.PartitionKeyPath", equalTo("$.detail.id"));
    }

    @Test
    @Order(2)
    void putEventLandsOnTheStreamWithTheDetailPartitionKey() {
        given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", "AWSEvents.PutEvents")
            .body("""
                    {
                      "Entries": [{
                        "Source": "authors",
                        "DetailType": "AuthorRegistered",
                        "Detail": "{\\"id\\": \\"evt-1\\", \\"type\\": \\"AuthorRegistered\\"}"
                      }]
                    }
                    """)
        .when().post("/")
        .then().statusCode(200)
            .body("FailedEntryCount", equalTo(0));

        given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", "AWSEvents.PutEvents")
            .body("""
                    {
                      "Entries": [{
                        "Source": "authors",
                        "DetailType": "fault",
                        "Detail": "{\\"id\\": \\"fault-1\\", \\"type\\": \\"fault\\"}"
                      }]
                    }
                    """)
        .when().post("/")
        .then().statusCode(200)
            .body("FailedEntryCount", equalTo(0));

        String shardId = given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStream")
            .body("""
                    {"StreamName": "%s"}
                    """.formatted(STREAM))
        .when().post("/")
        .then().statusCode(200)
            .extract().path("StreamDescription.Shards[0].ShardId");

        String iterator = given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", "Kinesis_20131202.GetShardIterator")
            .body("""
                    {"StreamName": "%s", "ShardId": "%s", "ShardIteratorType": "TRIM_HORIZON"}
                    """.formatted(STREAM, shardId))
        .when().post("/")
        .then().statusCode(200)
            .extract().path("ShardIterator");

        JsonPath records = given()
            .contentType(JSON_CT)
            .header("X-Amz-Target", "Kinesis_20131202.GetRecords")
            .body("""
                    {"ShardIterator": "%s", "Limit": 10}
                    """.formatted(iterator))
        .when().post("/")
        .then().statusCode(200)
            .extract().jsonPath();

        assertEquals(1, records.getList("Records").size());
        assertEquals("evt-1", records.getString("Records[0].PartitionKey"));
        String body = new String(Base64.getDecoder().decode(records.getString("Records[0].Data")),
                StandardCharsets.UTF_8);
        assertEquals("{\"id\":\"evt-1\",\"type\":\"AuthorRegistered\"}", body);
    }
}
