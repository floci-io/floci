package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createTable() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "TestTable",
                    "KeySchema": [
                        {"AttributeName": "pk", "KeyType": "HASH"},
                        {"AttributeName": "sk", "KeyType": "RANGE"}
                    ],
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "sk", "AttributeType": "S"}
                    ],
                    "ProvisionedThroughput": {
                        "ReadCapacityUnits": 5,
                        "WriteCapacityUnits": 5
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableDescription.TableName", equalTo("TestTable"))
            .body("TableDescription.TableStatus", equalTo("ACTIVE"))
            .body("TableDescription.KeySchema.size()", equalTo(2));
    }

    @Test
    @Order(2)
    void createDuplicateTableFails() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "TestTable",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceInUseException"));
    }

    @Test
    void createTableWithGsiAndLsi() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "IndexTable",
                    "KeySchema": [
                        {"AttributeName": "pk", "KeyType": "HASH"},
                        {"AttributeName": "sk", "KeyType": "RANGE"}
                    ],
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "sk", "AttributeType": "S"},
                        {"AttributeName": "gsiPk", "AttributeType": "S"}
                    ],
                    "GlobalSecondaryIndexes": [
                        {
                            "IndexName": "gsi-1",
                            "KeySchema": [
                                {"AttributeName": "gsiPk", "KeyType": "HASH"},
                                {"AttributeName": "sk", "KeyType": "RANGE"}
                            ],
                            "Projection": {"ProjectionType": "ALL"}
                        }
                    ],
                    "LocalSecondaryIndexes": [
                        {
                            "IndexName": "lsi-1",
                            "KeySchema": [
                                {"AttributeName": "pk", "KeyType": "HASH"},
                                {"AttributeName": "gsiPk", "KeyType": "RANGE"}
                            ],
                            "Projection": {"ProjectionType": "KEYS_ONLY"}
                        }
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableDescription.GlobalSecondaryIndexes.size()", equalTo(1))
            .body("TableDescription.GlobalSecondaryIndexes[0].IndexName", equalTo("gsi-1"))
            .body("TableDescription.LocalSecondaryIndexes.size()", equalTo(1))
            .body("TableDescription.LocalSecondaryIndexes[0].IndexName", equalTo("lsi-1"));

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "IndexTable"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
    void describeTable() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "TestTable"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Table.TableName", equalTo("TestTable"))
            .body("Table.TableArn", containsString("TestTable"));
    }

    @Test
    @Order(4)
    void listTables() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.ListTables")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableNames", hasItem("TestTable"));
    }

    @Test
    @Order(5)
    void putItem() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "TestTable",
                    "Item": {
                        "pk": {"S": "user-1"},
                        "sk": {"S": "profile"},
                        "name": {"S": "Alice"},
                        "age": {"N": "30"}
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void putMoreItems() {
        String[] items = {
            """
                {"TableName":"TestTable","Item":{"pk":{"S":"user-1"},"sk":{"S":"order-001"},"total":{"N":"99.99"}}}
            """,
            """
                {"TableName":"TestTable","Item":{"pk":{"S":"user-1"},"sk":{"S":"order-002"},"total":{"N":"49.50"}}}
            """,
            """
                {"TableName":"TestTable","Item":{"pk":{"S":"user-2"},"sk":{"S":"profile"},"name":{"S":"Bob"}}}
            """
        };
        for (String item : items) {
            given()
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body(item)
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
    }

    @Test
    @Order(7)
    void getItem() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "TestTable",
                    "Key": {
                        "pk": {"S": "user-1"},
                        "sk": {"S": "profile"}
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Item.name.S", equalTo("Alice"))
            .body("Item.age.N", equalTo("30"));
    }

    @Test
    @Order(8)
    void getItemNotFound() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "TestTable",
                    "Key": {
                        "pk": {"S": "nonexistent"},
                        "sk": {"S": "x"}
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Item", nullValue());
    }

    @Test
    @Order(9)
    void query() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "TestTable",
                    "KeyConditionExpression": "pk = :pk",
                    "ExpressionAttributeValues": {
                        ":pk": {"S": "user-1"}
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(3))
            .body("Items.size()", equalTo(3));
    }

    @Test
    @Order(10)
    void queryWithBeginsWith() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "TestTable",
                    "KeyConditionExpression": "pk = :pk AND begins_with(sk, :prefix)",
                    "ExpressionAttributeValues": {
                        ":pk": {"S": "user-1"},
                        ":prefix": {"S": "order"}
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(2));
    }

    @Test
    @Order(11)
    void queryWithBetweenOnSortKey() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "TestTable",
                    "KeyConditionExpression": "pk = :pk AND sk BETWEEN :from AND :to",
                    "ExpressionAttributeValues": {
                        ":pk": {"S": "user-1"},
                        ":from": {"S": "order-001"},
                        ":to": {"S": "order-002"}
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(2))
            .body("Items[0].sk.S", equalTo("order-001"))
            .body("Items[1].sk.S", equalTo("order-002"));
    }

    @Test
    @Order(12)
    void queryWithScanIndexForwardFalse() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "TestTable",
                    "KeyConditionExpression": "pk = :pk AND begins_with(sk, :prefix)",
                    "ScanIndexForward": false,
                    "ExpressionAttributeValues": {
                        ":pk": {"S": "user-1"},
                        ":prefix": {"S": "order"}
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(2))
            .body("Items[0].sk.S", equalTo("order-002"))
            .body("Items[1].sk.S", equalTo("order-001"));
    }

    @Test
    @Order(13)
    void queryWithFilterExpression() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "TestTable",
                    "KeyConditionExpression": "pk = :pk",
                    "FilterExpression": "total >= :min",
                    "ExpressionAttributeValues": {
                        ":pk": {"S": "user-1"},
                        ":min": {"N": "50"}
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(1))
            .body("ScannedCount", equalTo(3))
            .body("Items[0].sk.S", equalTo("order-001"));
    }

    @Test
    @Order(14)
    void queryWithFilterExpressionAndLimitReturnsLastEvaluatedKey() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "TestTable",
                    "KeyConditionExpression": "pk = :pk",
                    "FilterExpression": "total >= :min",
                    "Limit": 2,
                    "ExpressionAttributeValues": {
                        ":pk": {"S": "user-1"},
                        ":min": {"N": "50"}
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(1))
            .body("ScannedCount", equalTo(2))
            .body("Items[0].sk.S", equalTo("order-001"))
            .body("LastEvaluatedKey.pk.S", equalTo("user-1"))
            .body("LastEvaluatedKey.sk.S", equalTo("order-002"));
    }

    @Test
    @Order(15)
    void scan() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Scan")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "TestTable"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(4))
            .body("Items.size()", equalTo(4));
    }

    @Test
    @Order(16)
    void scanWithScanFilter() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Scan")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "TestTable",
                    "ScanFilter": {
                        "name": {
                            "AttributeValueList": [{"S": "Alice"}],
                            "ComparisonOperator": "EQ"
                        }
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(1))
            .body("Items[0].name.S", equalTo("Alice"));
    }

    @Test
    @Order(17)
    void scanWithScanFilterGE() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Scan")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "TestTable",
                    "ScanFilter": {
                        "age": {
                            "AttributeValueList": [{"N": "30"}],
                            "ComparisonOperator": "GE"
                        }
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(1))
            .body("Items[0].name.S", equalTo("Alice"));
    }

    @Test
    @Order(18)
    void deleteItem() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "TestTable",
                    "Key": {
                        "pk": {"S": "user-2"},
                        "sk": {"S": "profile"}
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify it's gone
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "TestTable",
                    "Key": {
                        "pk": {"S": "user-2"},
                        "sk": {"S": "profile"}
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Item", nullValue());
    }

    // --- UpdateTable GSI tests (separate table to avoid key schema conflicts) ---

    @Test
    @Order(19)
    void createTableForGsiTests() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "GsiTestTable",
                    "KeySchema": [
                        {"AttributeName": "pk", "KeyType": "HASH"}
                    ],
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"}
                    ],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableDescription.TableName", equalTo("GsiTestTable"))
            .body("TableDescription.GlobalSecondaryIndexes", nullValue());
    }

    @Test
    @Order(20)
    void updateTableAddGsi() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "GsiTestTable",
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "gsiPk", "AttributeType": "S"},
                        {"AttributeName": "gsiSk", "AttributeType": "S"}
                    ],
                    "GlobalSecondaryIndexUpdates": [
                        {
                            "Create": {
                                "IndexName": "TestGsi",
                                "KeySchema": [
                                    {"AttributeName": "gsiPk", "KeyType": "HASH"},
                                    {"AttributeName": "gsiSk", "KeyType": "RANGE"}
                                ],
                                "Projection": {"ProjectionType": "ALL"}
                            }
                        }
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableDescription.GlobalSecondaryIndexes.size()", equalTo(1))
            .body("TableDescription.GlobalSecondaryIndexes[0].IndexName", equalTo("TestGsi"))
            .body("TableDescription.GlobalSecondaryIndexes[0].IndexStatus", equalTo("ACTIVE"))
            .body("TableDescription.GlobalSecondaryIndexes[0].KeySchema.size()", equalTo(2))
            .body("TableDescription.GlobalSecondaryIndexes[0].Projection.ProjectionType", equalTo("ALL"));
    }

    @Test
    @Order(21)
    void describeTableReturnsGsi() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "GsiTestTable"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Table.GlobalSecondaryIndexes.size()", equalTo(1))
            .body("Table.GlobalSecondaryIndexes[0].IndexName", equalTo("TestGsi"))
            .body("Table.GlobalSecondaryIndexes[0].IndexStatus", equalTo("ACTIVE"))
            .body("Table.GlobalSecondaryIndexes[0].Projection.ProjectionType", equalTo("ALL"))
            .body("Table.GlobalSecondaryIndexes[0].IndexArn", containsString("/index/TestGsi"))
            .body("Table.GlobalSecondaryIndexes[0].ProvisionedThroughput", notNullValue())
            .body("Table.GlobalSecondaryIndexes[0].ProvisionedThroughput.ReadCapacityUnits", equalTo(0))
            .body("Table.GlobalSecondaryIndexes[0].ProvisionedThroughput.WriteCapacityUnits", equalTo(0))
            .body("Table.GlobalSecondaryIndexes[0].ProvisionedThroughput.NumberOfDecreasesToday", equalTo(0))
            .body("Table.GlobalSecondaryIndexes[0].IndexSizeBytes", equalTo(0))
            .body("Table.GlobalSecondaryIndexes[0].ItemCount", equalTo(0))
            .body("Table.AttributeDefinitions.size()", equalTo(3));
    }

    @Test
    @Order(22)
    void updateTableAddGsiWithKeysOnlyProjection() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "GsiTestTable",
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "gsiPk", "AttributeType": "S"},
                        {"AttributeName": "gsiSk", "AttributeType": "S"},
                        {"AttributeName": "owner", "AttributeType": "S"}
                    ],
                    "GlobalSecondaryIndexUpdates": [
                        {
                            "Create": {
                                "IndexName": "OwnerIndex",
                                "KeySchema": [
                                    {"AttributeName": "owner", "KeyType": "HASH"},
                                    {"AttributeName": "pk", "KeyType": "RANGE"}
                                ],
                                "Projection": {"ProjectionType": "KEYS_ONLY"}
                            }
                        }
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableDescription.GlobalSecondaryIndexes.size()", equalTo(2))
            .body("TableDescription.GlobalSecondaryIndexes.find { it.IndexName == 'OwnerIndex' }.IndexStatus", equalTo("ACTIVE"))
            .body("TableDescription.GlobalSecondaryIndexes.find { it.IndexName == 'OwnerIndex' }.Projection.ProjectionType", equalTo("KEYS_ONLY"));
    }
    

    @Test
    @Order(23)
    void updateTableAddGsiWithIncludeProjection() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "GsiTestTable",
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "gsiPk", "AttributeType": "S"},
                        {"AttributeName": "gsiSk", "AttributeType": "S"},
                        {"AttributeName": "owner", "AttributeType": "S"}
                    ],
                    "GlobalSecondaryIndexUpdates": [
                        {
                            "Create": {
                                "IndexName": "OwnerIndexProj",
                                "KeySchema": [
                                    {"AttributeName": "owner", "KeyType": "HASH"},
                                    {"AttributeName": "pk", "KeyType": "RANGE"}
                                ],
                                "Projection": {"ProjectionType": "INCLUDE", "NonKeyAttributes":["TestAttr"]}
                            }
                        }
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableDescription.GlobalSecondaryIndexes.size()", equalTo(3))
            .body("TableDescription.GlobalSecondaryIndexes.find { it.IndexName == 'OwnerIndexProj' }.IndexStatus", equalTo("ACTIVE"))
            .body("TableDescription.GlobalSecondaryIndexes.find { it.IndexName == 'OwnerIndexProj' }.Projection.ProjectionType", equalTo("INCLUDE"))
            .body("TableDescription.GlobalSecondaryIndexes.find { it.IndexName == 'OwnerIndexProj' }.Projection.NonKeyAttributes.size()", equalTo(1))
            .body("TableDescription.GlobalSecondaryIndexes.find { it.IndexName == 'OwnerIndexProj' }.Projection.NonKeyAttributes[0]", equalTo("TestAttr"));
    }


    @Test
    @Order(24)
    void updateTableDeleteGsi() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "GsiTestTable",
                    "GlobalSecondaryIndexUpdates": [
                        {
                            "Delete": {
                                "IndexName": "TestGsi"
                            }
                        }
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableDescription.GlobalSecondaryIndexes.size()", equalTo(2))
            .body("TableDescription.GlobalSecondaryIndexes[0].IndexName", equalTo("OwnerIndex"))
            .body("TableDescription.GlobalSecondaryIndexes[1].IndexName", equalTo("OwnerIndexProj"));
    }

    @Test
    @Order(25)
    void updateTableDeleteAllGsis() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "GsiTestTable",
                    "GlobalSecondaryIndexUpdates": [
                        {
                            "Delete": {
                                "IndexName": "OwnerIndex"
                            }
                        },
                        {
                            "Delete": {
                                "IndexName": "OwnerIndexProj"
                            }
                        }
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableDescription.GlobalSecondaryIndexes", nullValue());
    }

    @Test
    @Order(26)
    void describeTableAfterAllGsisDeletion() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "GsiTestTable"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Table.GlobalSecondaryIndexes", nullValue());
    }

    // --- Cleanup ---

    @Test
    @Order(27)
    void deleteTable() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "TestTable"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableDescription.TableStatus", equalTo("DELETING"));

        // Verify it's gone
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "TestTable"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // --- ConsumedCapacity tests ---
    // These use a dedicated table to avoid ordering dependencies.

    @Test
    void getItem_withReturnConsumedCapacityTotal_returnsCapacity() {
        // Create a dedicated table
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "CapacityTest",
                    "KeySchema": [{"AttributeName": "id", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "id", "AttributeType": "S"}],
                    "ProvisionedThroughput": {"ReadCapacityUnits": 5, "WriteCapacityUnits": 5}
                }
                """)
        .when().post("/").then().statusCode(200);

        // Put an item
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "CapacityTest", "Item": {"id": {"S": "a"}, "val": {"S": "hello"}}}
                """)
        .when().post("/").then().statusCode(200);

        // GetItem with TOTAL
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "CapacityTest",
                    "Key": {"id": {"S": "a"}},
                    "ReturnConsumedCapacity": "TOTAL"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Item.val.S", equalTo("hello"))
            .body("ConsumedCapacity.TableName", equalTo("CapacityTest"))
            .body("ConsumedCapacity.CapacityUnits", notNullValue());
    }

    @Test
    void getItem_withoutReturnConsumedCapacity_omitsCapacity() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "CapacityTest",
                    "Key": {"id": {"S": "a"}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConsumedCapacity", nullValue());
    }

    @Test
    void putItem_withReturnConsumedCapacityTotal_returnsCapacity() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "CapacityTest",
                    "Item": {"id": {"S": "b"}, "val": {"S": "world"}},
                    "ReturnConsumedCapacity": "TOTAL"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConsumedCapacity.TableName", equalTo("CapacityTest"))
            .body("ConsumedCapacity.CapacityUnits", notNullValue());
    }

    @Test
    void query_withReturnConsumedCapacityIndexes_returnsTableBreakdown() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "CapacityTest",
                    "KeyConditionExpression": "id = :id",
                    "ExpressionAttributeValues": {":id": {"S": "a"}},
                    "ReturnConsumedCapacity": "INDEXES"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConsumedCapacity.TableName", equalTo("CapacityTest"))
            .body("ConsumedCapacity.CapacityUnits", notNullValue())
            .body("ConsumedCapacity.Table.CapacityUnits", notNullValue());
    }

    @Test
    @Order(28)
    void updateItemListAppend() {
        // Create a table for this test
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ListAppendTable",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Put item with initial list
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ListAppendTable",
                    "Item": {"pk": {"S": "k1"}, "items": {"L": [{"S": "a"}]}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Append to list
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ListAppendTable",
                    "Key": {"pk": {"S": "k1"}},
                    "UpdateExpression": "SET #i = list_append(#i, :val)",
                    "ExpressionAttributeNames": {"#i": "items"},
                    "ExpressionAttributeValues": {":val": {"L": [{"S": "b"}]}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify both elements present
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ListAppendTable",
                    "Key": {"pk": {"S": "k1"}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Item.items.L.size()", equalTo(2))
            .body("Item.items.L[0].S", equalTo("a"))
            .body("Item.items.L[1].S", equalTo("b"));

        // Cleanup
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "ListAppendTable"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(29)
    void deleteElementsFromStringSet() {
        // Create table
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "DeleteSetTable",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Put item with a String Set
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "DeleteSetTable",
                    "Item": {"pk": {"S": "k1"}, "tags": {"SS": ["a", "b", "c"]}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // DELETE "a" from the set
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "DeleteSetTable",
                    "Key": {"pk": {"S": "k1"}},
                    "UpdateExpression": "DELETE tags :val",
                    "ExpressionAttributeValues": {":val": {"SS": ["a"]}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify "a" was removed, "b" and "c" remain
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "DeleteSetTable",
                    "Key": {"pk": {"S": "k1"}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Item.tags.SS.size()", equalTo(2))
            .body("Item.tags.SS", hasItems("b", "c"))
            .body("Item.tags.SS", not(hasItem("a")));

        // DELETE remaining elements to verify attribute removal on empty set
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "DeleteSetTable",
                    "Key": {"pk": {"S": "k1"}},
                    "UpdateExpression": "DELETE tags :val",
                    "ExpressionAttributeValues": {":val": {"SS": ["b", "c"]}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify attribute is removed entirely (DynamoDB doesn't allow empty sets)
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "DeleteSetTable",
                    "Key": {"pk": {"S": "k1"}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Item.tags", nullValue());

        // Cleanup
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "DeleteSetTable"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(30)
    void deleteFromSetWithAddInSameExpression() {
        // Create table
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "DeleteAddComboTable",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Put item with a String Set
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "DeleteAddComboTable",
                    "Item": {"pk": {"S": "k1"}, "tags": {"SS": ["a", "b"]}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Combined: ADD "c" then DELETE "a" in the same expression
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "DeleteAddComboTable",
                    "Key": {"pk": {"S": "k1"}},
                    "UpdateExpression": "ADD tags :toAdd DELETE tags :toRemove",
                    "ExpressionAttributeValues": {
                        ":toAdd": {"SS": ["c"]},
                        ":toRemove": {"SS": ["a"]}
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify: should have "b" and "c", not "a"
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "DeleteAddComboTable",
                    "Key": {"pk": {"S": "k1"}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Item.tags.SS.size()", equalTo(2))
            .body("Item.tags.SS", hasItems("b", "c"))
            .body("Item.tags.SS", not(hasItem("a")));

        // Cleanup
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "DeleteAddComboTable"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void updateItemConditionalCheckFailedNoReturnValues() {
        // Create a table for this test
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ConditionCheckTable1",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    
    given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ConditionCheckTable1",
                    "Item": {"pk": {"S": "k1"}, "testAttr": {"S": "abc"}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ConditionCheckTable1",
                    "Key": {"pk": {"S": "k1"}},
                    "UpdateExpression": "SET testAttr = :val",
                    "ExpressionAttributeValues": {":val": {"S": "123"}},
                    "ConditionExpression": "attribute_exists(nonExistent)"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ConditionalCheckFailedException"))
            .body("message", equalTo("The conditional request failed"))
            .body("Item", is(nullValue()));

        // Cleanup
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "ConditionCheckTable1"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    

    @Test
    void updateItemConditionalCheckFailedAllOldReturnValues() {
                // Create a table for this test
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ConditionCheckTable2",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ConditionCheckTable2",
                    "Item": {"pk": {"S": "k1"}, "testAttr": {"S": "abc"}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ConditionCheckTable2",
                    "Key": {"pk": {"S": "k1"}},
                    "UpdateExpression": "SET testAttr = :val",
                    "ExpressionAttributeValues": {":val": {"S": "123"}},
                    "ConditionExpression": "attribute_exists(nonExistent)",
                    "ReturnValuesOnConditionCheckFailure" : "ALL_OLD"
                }
                """)
        .when()
            .post("/")
        .then()
            .body("__type", equalTo("ConditionalCheckFailedException"))
            .body("message", equalTo("The conditional request failed"))
            .body("Item.testAttr.S", equalTo("abc"));

        // Cleanup
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "ConditionCheckTable2"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void updateAndDescribeContinuousBackups() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ContinuousBackupsTable",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeContinuousBackups")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "ContinuousBackupsTable"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ContinuousBackupsDescription.ContinuousBackupsStatus", equalTo("ENABLED"))
            .body("ContinuousBackupsDescription.PointInTimeRecoveryDescription.PointInTimeRecoveryStatus",
                    equalTo("DISABLED"))
            .body("ContinuousBackupsDescription.PointInTimeRecoveryDescription.RecoveryPeriodInDays",
                    nullValue());

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateContinuousBackups")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ContinuousBackupsTable",
                    "PointInTimeRecoverySpecification": {
                        "PointInTimeRecoveryEnabled": true
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ContinuousBackupsDescription.ContinuousBackupsStatus", equalTo("ENABLED"))
            .body("ContinuousBackupsDescription.PointInTimeRecoveryDescription.PointInTimeRecoveryStatus",
                    equalTo("ENABLED"))
            .body("ContinuousBackupsDescription.PointInTimeRecoveryDescription.RecoveryPeriodInDays",
                    equalTo(35));

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeContinuousBackups")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "ContinuousBackupsTable"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ContinuousBackupsDescription.PointInTimeRecoveryDescription.PointInTimeRecoveryStatus",
                    equalTo("ENABLED"));

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "ContinuousBackupsTable"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void updateContinuousBackupsRejectsOutOfRangeRecoveryPeriod() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ContinuousBackupsValidationTable",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateContinuousBackups")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ContinuousBackupsValidationTable",
                    "PointInTimeRecoverySpecification": {
                        "PointInTimeRecoveryEnabled": true,
                        "RecoveryPeriodInDays": 36
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "ContinuousBackupsValidationTable"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(31)
    void updateItemSetArithmeticIncrement() {
        // Create table
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ArithmeticTable",
                    "KeySchema": [{"AttributeName": "PK", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "PK", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // First call: if_not_exists(counter, :start) + :inc → 60000001
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ArithmeticTable",
                    "Key": {"PK": {"S": "LastId"}},
                    "UpdateExpression": "SET customerId = if_not_exists(customerId, :start) + :inc",
                    "ExpressionAttributeValues": {
                        ":start": {"N": "60000000"},
                        ":inc": {"N": "1"}
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify first increment
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ArithmeticTable",
                    "Key": {"PK": {"S": "LastId"}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Item.customerId.N", equalTo("60000001"));

        // Second call: existing (60000001) + 1 → 60000002
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ArithmeticTable",
                    "Key": {"PK": {"S": "LastId"}},
                    "UpdateExpression": "SET customerId = if_not_exists(customerId, :start) + :inc",
                    "ExpressionAttributeValues": {
                        ":start": {"N": "60000000"},
                        ":inc": {"N": "1"}
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify second increment
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "ArithmeticTable",
                    "Key": {"PK": {"S": "LastId"}}
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Item.customerId.N", equalTo("60000002"));

        // Cleanup
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "ArithmeticTable"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void unsupportedOperation() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateGlobalTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void responseIncludesCorrectXAmzCrc32Header() {
        // The AWS SDK for Go v2 DynamoDB client wraps the response body in a CRC32-verifying
        // reader and emits "failed to close HTTP response body" warnings when the header is
        // missing. Verify floci attaches the header on both success and error responses and
        // that the value matches the CRC32 of the response body bytes.
        Response listResponse = given()
                .header("X-Amz-Target", "DynamoDB_20120810.ListTables")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("{}")
                .when()
                .post("/");

        listResponse.then().statusCode(200);
        String crcHeader = listResponse.getHeader("X-Amz-Crc32");
        assertNotNull(crcHeader, "ListTables response must carry X-Amz-Crc32");
        assertEquals(Long.toString(crc32Of(listResponse.asByteArray())), crcHeader);

        Response errorResponse = given()
                .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("{\"TableName\":\"does-not-exist-crc32-check\"}")
                .when()
                .post("/");

        errorResponse.then().statusCode(400);
        String errorCrc = errorResponse.getHeader("X-Amz-Crc32");
        assertNotNull(errorCrc, "Error response must carry X-Amz-Crc32");
        assertEquals(Long.toString(crc32Of(errorResponse.asByteArray())), errorCrc);
    }

    @Test
    void updateItemWithSamePartitionKeyButDifferentSortKeyCreatesSeparateItems() {
        // Reproduces GitHub issue #498: UpdateItem on a table with a sort key
        // overwrites the existing row instead of creating a new one when the
        // partition key matches but the sort key differs.
        String tableName = "CoordinationTable";

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "KeySchema": [
                        {"AttributeName": "GroupKey", "KeyType": "HASH"},
                        {"AttributeName": "Id", "KeyType": "RANGE"}
                    ],
                    "AttributeDefinitions": [
                        {"AttributeName": "GroupKey", "AttributeType": "S"},
                        {"AttributeName": "Id", "AttributeType": "S"}
                    ],
                    "ProvisionedThroughput": {"ReadCapacityUnits": 1, "WriteCapacityUnits": 1}
                }
                """.formatted(tableName))
        .when().post("/")
        .then().statusCode(200);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "Key": {"GroupKey": {"S": "leader"}, "Id": {"S": "app1"}},
                    "UpdateExpression": "SET #o = :1",
                    "ExpressionAttributeNames": {"#o": "Owner"},
                    "ExpressionAttributeValues": {":1": {"S": "owner-app1"}}
                }
                """.formatted(tableName))
        .when().post("/")
        .then().statusCode(200);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "Key": {"GroupKey": {"S": "leader"}, "Id": {"S": "app2"}},
                    "UpdateExpression": "SET #o = :1",
                    "ExpressionAttributeNames": {"#o": "Owner"},
                    "ExpressionAttributeValues": {":1": {"S": "owner-app2"}}
                }
                """.formatted(tableName))
        .when().post("/")
        .then().statusCode(200);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Scan")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s"}
                """.formatted(tableName))
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(2))
            .body("ScannedCount", equalTo(2));

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s"}
                """.formatted(tableName))
        .when().post("/")
        .then().statusCode(200);
    }

    @Test
    @Order(32)
    void deletionProtectionEnabled() {
        String tableName = "deletion-protection-test";

        // Create table with DeletionProtectionEnabled = true
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST",
                    "DeletionProtectionEnabled": true
                }
                """.formatted(tableName))
        .when().post("/")
        .then().statusCode(200);

        // DescribeTable returns DeletionProtectionEnabled = true
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s"}
                """.formatted(tableName))
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Table.DeletionProtectionEnabled", equalTo(true));

        // DeleteTable is blocked
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s"}
                """.formatted(tableName))
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceInUseException"));

        // UpdateTable to disable deletion protection
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "DeletionProtectionEnabled": false
                }
                """.formatted(tableName))
        .when().post("/")
        .then().statusCode(200);

        // DescribeTable returns DeletionProtectionEnabled = false
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s"}
                """.formatted(tableName))
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Table.DeletionProtectionEnabled", equalTo(false));

        // DeleteTable now succeeds
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s"}
                """.formatted(tableName))
        .when().post("/")
        .then().statusCode(200);
    }

    private static long crc32Of(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    @Test
    @Order(33)
    void gsiQueryPaginationWithSharedSortKey() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String tableName = "gsi-pagination-test";

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "KeySchema": [
                        {"AttributeName": "PK", "KeyType": "HASH"},
                        {"AttributeName": "SK", "KeyType": "RANGE"}
                    ],
                    "AttributeDefinitions": [
                        {"AttributeName": "PK",     "AttributeType": "S"},
                        {"AttributeName": "SK",     "AttributeType": "S"},
                        {"AttributeName": "GSI1PK", "AttributeType": "S"},
                        {"AttributeName": "GSI1SK", "AttributeType": "S"}
                    ],
                    "BillingMode": "PAY_PER_REQUEST",
                    "GlobalSecondaryIndexes": [{
                        "IndexName": "GSI1",
                        "KeySchema": [
                            {"AttributeName": "GSI1PK", "KeyType": "HASH"},
                            {"AttributeName": "GSI1SK", "KeyType": "RANGE"}
                        ],
                        "Projection": {"ProjectionType": "ALL"}
                    }]
                }
                """.formatted(tableName))
        .when().post("/")
        .then().statusCode(200);

        // 5 items all sharing (GSI1PK="ITEM", GSI1SK="SAME"), unique base-table PK/SK
        for (String id : new String[]{"ITEM_a", "ITEM_b", "ITEM_c", "ITEM_d", "ITEM_e"}) {
            given()
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {
                        "TableName": "%s",
                        "Item": {
                            "PK":     {"S": "%s"},
                            "SK":     {"S": "DETAIL"},
                            "GSI1PK": {"S": "ITEM"},
                            "GSI1SK": {"S": "SAME"}
                        }
                    }
                    """.formatted(tableName, id))
            .when().post("/")
            .then().statusCode(200);
        }

        List<String> allCollected = new ArrayList<>();
        Set<String> seenLeks = new HashSet<>();
        JsonNode exclusiveStartKey = null;
        int pages = 0;

        do {
            String body;
            if (exclusiveStartKey == null) {
                body = """
                    {
                        "TableName": "%s",
                        "IndexName": "GSI1",
                        "KeyConditionExpression": "GSI1PK = :pk",
                        "ExpressionAttributeValues": {":pk": {"S": "ITEM"}},
                        "Limit": 2
                    }
                    """.formatted(tableName);
            } else {
                body = """
                    {
                        "TableName": "%s",
                        "IndexName": "GSI1",
                        "KeyConditionExpression": "GSI1PK = :pk",
                        "ExpressionAttributeValues": {":pk": {"S": "ITEM"}},
                        "Limit": 2,
                        "ExclusiveStartKey": %s
                    }
                    """.formatted(tableName, mapper.writeValueAsString(exclusiveStartKey));
            }

            String responseBody = given()
                .header("X-Amz-Target", "DynamoDB_20120810.Query")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body(body)
            .when().post("/")
            .then().statusCode(200).extract().body().asString();

            JsonNode root = mapper.readTree(responseBody);
            pages++;

            for (JsonNode item : root.path("Items")) {
                allCollected.add(item.path("PK").path("S").asText());
            }

            JsonNode lek = root.path("LastEvaluatedKey");
            if (lek.isMissingNode() || lek.isNull()) {
                exclusiveStartKey = null;
            } else {
                // LEK must contain all four keys
                assertNotNull(lek.get("GSI1PK"), "LastEvaluatedKey missing GSI1PK");
                assertNotNull(lek.get("GSI1SK"), "LastEvaluatedKey missing GSI1SK");
                assertNotNull(lek.get("PK"),     "LastEvaluatedKey missing PK");
                assertNotNull(lek.get("SK"),     "LastEvaluatedKey missing SK");

                // LEK must be unique across pages — cursor must advance
                String lekStr = lek.toString();
                assertEquals(false, seenLeks.contains(lekStr),
                        "LastEvaluatedKey repeated — infinite pagination loop: " + lekStr);
                seenLeks.add(lekStr);
                exclusiveStartKey = lek;
            }
        } while (exclusiveStartKey != null && pages < 10);

        // All 5 distinct items returned exactly once
        assertEquals(5, allCollected.size(), "Expected 5 items total, got: " + allCollected);
        assertEquals(Set.of("ITEM_a", "ITEM_b", "ITEM_c", "ITEM_d", "ITEM_e"), new HashSet<>(allCollected));
        assertEquals(3, pages, "Expected ceil(5/2)=3 pages");
    }
}
