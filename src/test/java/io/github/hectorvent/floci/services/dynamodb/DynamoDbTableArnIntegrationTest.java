package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class DynamoDbTableArnIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String KINESIS_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeAndGetItemAcceptTableArn() {
        String tableName = tableName("describe-get");
        String tableArn = createTable(tableName);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "Item": {
                        "pk": {"S": "user-1"},
                        "name": {"S": "Alice"}
                    }
                }
                """.formatted(tableArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s"}
                """.formatted(tableArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Table.TableName", equalTo(tableName))
            .body("Table.TableArn", equalTo(tableArn));

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "Key": {"pk": {"S": "user-1"}}
                }
                """.formatted(tableArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Item.name.S", equalTo("Alice"));
    }

    @Test
    void batchAndTransactOperationsAcceptTableArn() {
        String tableName = tableName("batch-transact");
        String tableArn = createTable(tableName);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.BatchWriteItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "RequestItems": {
                        "%s": [
                            {"PutRequest": {"Item": {"pk": {"S": "user-1"}, "name": {"S": "Alice"}}}}
                        ]
                    }
                }
                """.formatted(tableArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.BatchGetItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "RequestItems": {
                        "%s": {
                            "Keys": [{"pk": {"S": "user-1"}}]
                        }
                    }
                }
                """.formatted(tableArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Responses.'%s'".formatted(tableArn), hasSize(1))
            .body("Responses.'%s'[0].name.S".formatted(tableArn), equalTo("Alice"));

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.TransactWriteItems")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TransactItems": [
                        {
                            "Update": {
                                "TableName": "%s",
                                "Key": {"pk": {"S": "user-1"}},
                                "ConditionExpression": "name = :expected",
                                "UpdateExpression": "SET email = :email",
                                "ExpressionAttributeValues": {
                                    ":expected": {"S": "Alice"},
                                    ":email": {"S": "alice@example.com"}
                                }
                            }
                        }
                    ]
                }
                """.formatted(tableArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.TransactGetItems")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TransactItems": [
                        {
                            "Get": {
                                "TableName": "%s",
                                "Key": {"pk": {"S": "user-1"}}
                            }
                        }
                    ]
                }
                """.formatted(tableArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Responses[0].Item.email.S", equalTo("alice@example.com"));
    }

    @Test
    void ttlAndContinuousBackupsAcceptTableArn() {
        String tableName = tableName("ttl-backups");
        String tableArn = createTable(tableName);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateTimeToLive")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "TimeToLiveSpecification": {
                        "AttributeName": "expiresAt",
                        "Enabled": true
                    }
                }
                """.formatted(tableArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TimeToLiveSpecification.AttributeName", equalTo("expiresAt"))
            .body("TimeToLiveSpecification.Enabled", equalTo(true));

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTimeToLive")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s"}
                """.formatted(tableArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TimeToLiveDescription.TimeToLiveStatus", equalTo("ENABLED"))
            .body("TimeToLiveDescription.AttributeName", equalTo("expiresAt"));

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateContinuousBackups")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "PointInTimeRecoverySpecification": {
                        "PointInTimeRecoveryEnabled": true
                    }
                }
                """.formatted(tableArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ContinuousBackupsDescription.PointInTimeRecoveryDescription.PointInTimeRecoveryStatus",
                    equalTo("ENABLED"));

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeContinuousBackups")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s"}
                """.formatted(tableArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ContinuousBackupsDescription.PointInTimeRecoveryDescription.PointInTimeRecoveryStatus",
                    equalTo("ENABLED"));
    }

    @Test
    void describeTableRejectsRegionMismatchAndIndexArn() {
        String tableArn = createTable(tableName("invalid-arn"));

        String mismatchedRegionArn = tableArn.replace(":us-east-1:", ":eu-west-1:");
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s"}
                """.formatted(mismatchedRegionArn))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValue"))
            .body("message", containsString("does not match request region"));

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s/index/by-status"}
                """.formatted(tableArn))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValue"))
            .body("message", containsString("does not accept index or stream ARNs"));
    }

    @Test
    void kinesisStreamingDestinationAcceptsTableArn() {
        String streamName = tableName("ddb-kinesis-stream");
        String tableName = tableName("ddb-kinesis-table");
        String tableArn = createTable(tableName);
        String streamArn = createKinesisStream(streamName);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.EnableKinesisStreamingDestination")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "StreamArn": "%s"
                }
                """.formatted(tableArn, streamArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableName", equalTo(tableName))
            .body("StreamArn", equalTo(streamArn))
            .body("DestinationStatus", equalTo("ACTIVE"));

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeKinesisStreamingDestination")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s"}
                """.formatted(tableArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableName", equalTo(tableName))
            .body("KinesisDataStreamDestinations", hasSize(1))
            .body("KinesisDataStreamDestinations[0].StreamArn", equalTo(streamArn));
    }

    @Test
    void consumedCapacityReturnsCanonicalTableName() {
        String tableName = tableName("consumed-cap");
        String tableArn = createTable(tableName);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "Item": {"pk": {"S": "user-1"}},
                    "ReturnConsumedCapacity": "TOTAL"
                }
                """.formatted(tableArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConsumedCapacity.TableName", equalTo(tableName));

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.BatchWriteItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "RequestItems": {
                        "%s": [
                            {"PutRequest": {"Item": {"pk": {"S": "user-2"}}}}
                        ]
                    },
                    "ReturnConsumedCapacity": "TOTAL"
                }
                """.formatted(tableArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConsumedCapacity[0].TableName", equalTo(tableName));
    }

    @Test
    void createTableRejectsArnInput() {
        String bogusArn = "arn:aws:dynamodb:us-east-1:000000000000:table/bogus-" + UUID.randomUUID().toString().substring(0, 8);
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(bogusArn))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValue"))
            .body("message", containsString("must be a short name, not an ARN"));
    }

    @Test
    void updateTableStreamToggleUsesCanonicalNameWhenCalledViaArn() {
        String tableName = tableName("update-stream");
        String tableArn = createTable(tableName);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "StreamSpecification": {
                        "StreamEnabled": true,
                        "StreamViewType": "NEW_AND_OLD_IMAGES"
                    }
                }
                """.formatted(tableArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableDescription.TableName", equalTo(tableName))
            .body("TableDescription.StreamSpecification.StreamEnabled", equalTo(true));

        // Describe via short name must still see the stream state (i.e. state was
        // keyed under the canonical name, not the ARN used on UpdateTable).
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s"}
                """.formatted(tableName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Table.StreamSpecification.StreamEnabled", equalTo(true))
            .body("Table.LatestStreamArn", containsString(":table/" + tableName + "/stream/"));
    }

    private static String createTable(String tableName) {
        return given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(tableName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("TableDescription.TableArn");
    }

    private static String createKinesisStream(String streamName) {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.CreateStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "%s", "ShardCount": 1}
                """.formatted(streamName))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        return given()
            .header("X-Amz-Target", "Kinesis_20131202.DescribeStreamSummary")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("""
                {"StreamName": "%s"}
                """.formatted(streamName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("StreamDescriptionSummary.StreamARN");
    }

    private static String tableName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
