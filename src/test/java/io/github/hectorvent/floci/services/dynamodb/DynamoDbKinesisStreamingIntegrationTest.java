package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbKinesisStreamingIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String KINESIS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static String kinesisStreamArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void setupKinesisStream() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.CreateStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "ddb-streaming-test", "ShardCount": 1}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        kinesisStreamArn = given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStreamSummary")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "ddb-streaming-test"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("StreamDescriptionSummary.StreamARN");

        assertNotNull(kinesisStreamArn);
    }

    @Test
    @Order(2)
    void setupDynamoDbTable() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "StreamingTable",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST",
                    "StreamSpecification": {"StreamEnabled": true, "StreamViewType": "NEW_AND_OLD_IMAGES"}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableDescription.TableName", equalTo("StreamingTable"));
    }

    @Test
    @Order(3)
    void enableKinesisStreamingDestination() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.EnableKinesisStreamingDestination")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"TableName\": \"StreamingTable\", \"StreamArn\": \"" + kinesisStreamArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableName", equalTo("StreamingTable"))
            .body("StreamArn", equalTo(kinesisStreamArn))
            .body("DestinationStatus", equalTo("ACTIVE"));
    }

    @Test
    @Order(4)
    void describeKinesisStreamingDestination() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeKinesisStreamingDestination")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "StreamingTable"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableName", equalTo("StreamingTable"))
            .body("KinesisDataStreamDestinations.size()", equalTo(1))
            .body("KinesisDataStreamDestinations[0].StreamArn", equalTo(kinesisStreamArn))
            .body("KinesisDataStreamDestinations[0].DestinationStatus", equalTo("ACTIVE"))
            .body("KinesisDataStreamDestinations[0].ApproximateCreationDateTimePrecision", equalTo("MILLISECOND"));
    }

    @Test
    @Order(5)
    void enableDuplicateDestinationFails() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.EnableKinesisStreamingDestination")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"TableName\": \"StreamingTable\", \"StreamArn\": \"" + kinesisStreamArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(6)
    void enableWithNonExistentStreamFails() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.EnableKinesisStreamingDestination")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "StreamingTable", "StreamArn": "arn:aws:kinesis:us-east-1:000000000000:stream/no-such-stream"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(7)
    void enableWithNonExistentTableFails() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.EnableKinesisStreamingDestination")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"TableName\": \"NoSuchTable\", \"StreamArn\": \"" + kinesisStreamArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(10)
    void putItemForwardsToKinesis() throws Exception {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "StreamingTable",
                    "Item": {"pk": {"S": "k1"}, "data": {"S": "hello"}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String shardIterator = given()
            .header("X-Amz-Target", "Kinesis_20131202.GetShardIterator")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "ddb-streaming-test", "ShardId": "shardId-000000000000", "ShardIteratorType": "TRIM_HORIZON"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("ShardIterator");

        Response recordsResponse = given()
            .header("X-Amz-Target", "Kinesis_20131202.GetRecords")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"ShardIterator\": \"" + shardIterator + "\", \"Limit\": 10}")
        .when()
            .post("/");

        recordsResponse.then().statusCode(200);

        int recordCount = recordsResponse.jsonPath().getInt("Records.size()");
        assertTrue(recordCount >= 1, "Expected at least 1 Kinesis record, got " + recordCount);

        String encodedData = recordsResponse.jsonPath().getString("Records[0].Data");
        String decoded = new String(Base64.getDecoder().decode(encodedData));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode payload = mapper.readTree(decoded);
        assertEquals("INSERT", payload.get("eventName").asText());
        assertEquals("StreamingTable", payload.get("tableName").asText());
        assertEquals("aws:dynamodb", payload.get("eventSource").asText());

        JsonNode dynamodb = payload.get("dynamodb");
        assertNotNull(dynamodb, "dynamodb node must be present");
        assertNotNull(dynamodb.get("Keys"), "Keys must be present");
        assertNotNull(dynamodb.get("NewImage"), "NewImage must be present");
        assertNotNull(dynamodb.get("SizeBytes"), "SizeBytes must be present");
        assertNotNull(dynamodb.get("ApproximateCreationDateTimePrecision"),
                "ApproximateCreationDateTimePrecision must be present in dynamodb node");

        long timestamp = dynamodb.get("ApproximateCreationDateTime").asLong();
        long nowMillis = System.currentTimeMillis();
        assertTrue(timestamp > nowMillis - 60_000 && timestamp <= nowMillis + 5_000,
                "ApproximateCreationDateTime should be in milliseconds (recent), got: " + timestamp);

        assertFalse(dynamodb.has("SequenceNumber"), "SequenceNumber should not be in Kinesis payload");
        assertFalse(dynamodb.has("StreamViewType"), "StreamViewType should not be in Kinesis payload");
    }

    @Test
    @Order(11)
    void updateItemForwardsModifyEvent() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "StreamingTable",
                    "Key": {"pk": {"S": "k1"}},
                    "UpdateExpression": "SET #d = :v",
                    "ExpressionAttributeNames": {"#d": "data"},
                    "ExpressionAttributeValues": {":v": {"S": "updated"}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String shardIterator = given()
            .header("X-Amz-Target", "Kinesis_20131202.GetShardIterator")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "ddb-streaming-test", "ShardId": "shardId-000000000000", "ShardIteratorType": "TRIM_HORIZON"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("ShardIterator");

        Response recordsResponse = given()
            .header("X-Amz-Target", "Kinesis_20131202.GetRecords")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"ShardIterator\": \"" + shardIterator + "\", \"Limit\": 10}")
        .when()
            .post("/");

        int recordCount = recordsResponse.jsonPath().getInt("Records.size()");
        assertTrue(recordCount >= 2, "Expected at least 2 records (INSERT + MODIFY), got " + recordCount);

        String lastEncoded = recordsResponse.jsonPath().getString("Records[" + (recordCount - 1) + "].Data");
        String lastDecoded = new String(Base64.getDecoder().decode(lastEncoded));
        assertTrue(lastDecoded.contains("\"eventName\":\"MODIFY\""), "Expected MODIFY event, got: " + lastDecoded);
    }

    @Test
    @Order(12)
    void deleteItemForwardsRemoveEvent() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "StreamingTable",
                    "Key": {"pk": {"S": "k1"}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String shardIterator = given()
            .header("X-Amz-Target", "Kinesis_20131202.GetShardIterator")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "ddb-streaming-test", "ShardId": "shardId-000000000000", "ShardIteratorType": "TRIM_HORIZON"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("ShardIterator");

        Response recordsResponse = given()
            .header("X-Amz-Target", "Kinesis_20131202.GetRecords")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"ShardIterator\": \"" + shardIterator + "\", \"Limit\": 10}")
        .when()
            .post("/");

        int recordCount = recordsResponse.jsonPath().getInt("Records.size()");
        assertTrue(recordCount >= 3, "Expected at least 3 records (INSERT + MODIFY + REMOVE), got " + recordCount);

        String lastEncoded = recordsResponse.jsonPath().getString("Records[" + (recordCount - 1) + "].Data");
        String lastDecoded = new String(Base64.getDecoder().decode(lastEncoded));
        assertTrue(lastDecoded.contains("\"eventName\":\"REMOVE\""), "Expected REMOVE event, got: " + lastDecoded);
    }

    @Test
    @Order(20)
    void disableKinesisStreamingDestination() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DisableKinesisStreamingDestination")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"TableName\": \"StreamingTable\", \"StreamArn\": \"" + kinesisStreamArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DestinationStatus", equalTo("DISABLED"));
    }

    @Test
    @Order(21)
    void describeAfterDisable() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeKinesisStreamingDestination")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "StreamingTable"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("KinesisDataStreamDestinations[0].DestinationStatus", equalTo("DISABLED"));
    }

    @Test
    @Order(22)
    void putItemAfterDisableDoesNotForward() {
        String beforeIterator = given()
            .header("X-Amz-Target", "Kinesis_20131202.GetShardIterator")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "ddb-streaming-test", "ShardId": "shardId-000000000000", "ShardIteratorType": "TRIM_HORIZON"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("ShardIterator");

        int beforeCount = given()
            .header("X-Amz-Target", "Kinesis_20131202.GetRecords")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"ShardIterator\": \"" + beforeIterator + "\", \"Limit\": 100}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getInt("Records.size()");

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "StreamingTable",
                    "Item": {"pk": {"S": "k-after-disable"}, "data": {"S": "should not appear"}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String afterIterator = given()
            .header("X-Amz-Target", "Kinesis_20131202.GetShardIterator")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "ddb-streaming-test", "ShardId": "shardId-000000000000", "ShardIteratorType": "TRIM_HORIZON"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("ShardIterator");

        int afterCount = given()
            .header("X-Amz-Target", "Kinesis_20131202.GetRecords")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"ShardIterator\": \"" + afterIterator + "\", \"Limit\": 100}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getInt("Records.size()");

        assertEquals(beforeCount, afterCount,
                "No new Kinesis records should be added after destination is disabled");
    }

    @Test
    @Order(23)
    void disableAlreadyDisabledFails() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DisableKinesisStreamingDestination")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"TableName\": \"StreamingTable\", \"StreamArn\": \"" + kinesisStreamArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(24)
    void disableNonExistentDestinationFails() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DisableKinesisStreamingDestination")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "StreamingTable", "StreamArn": "arn:aws:kinesis:us-east-1:000000000000:stream/no-such"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(30)
    void reEnableAfterDisable() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.EnableKinesisStreamingDestination")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"TableName\": \"StreamingTable\", \"StreamArn\": \"" + kinesisStreamArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DestinationStatus", equalTo("ACTIVE"));
    }

    @Test
    @Order(40)
    void enableAutoEnablesStreamsIfDisabled() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "NoStreamTable",
                    "KeySchema": [{"AttributeName": "id", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "id", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.EnableKinesisStreamingDestination")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"TableName\": \"NoStreamTable\", \"StreamArn\": \"" + kinesisStreamArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DestinationStatus", equalTo("ACTIVE"));

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "NoStreamTable"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Table.StreamSpecification.StreamEnabled", equalTo(true));
    }
}
