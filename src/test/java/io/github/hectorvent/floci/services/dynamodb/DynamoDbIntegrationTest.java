package io.github.hectorvent.floci.services.dynamodb;

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
class DynamoDbIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(DYNAMODB_CONTENT_TYPE, ContentType.TEXT));
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
    void queryWithNumericExpressionAttributeNames() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "TestTable",
                    "KeyConditionExpression": "#0 = :0 AND begins_with(#1, :prefix)",
                    "ExpressionAttributeNames": {
                        "#0": "pk",
                        "#1": "sk"
                    },
                    "ExpressionAttributeValues": {
                        ":0": {"S": "user-1"},
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
    @Order(12)
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
    @Order(13)
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
    @Order(14)
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
    @Order(15)
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

    @Test
    @Order(16)
    void transactWriteWithNumericExpressionAttributeNames() {
        // PynamoDB-style: attribute_not_exists(#0) where #0 maps to the PK
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.TransactWriteItems")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TransactItems": [{
                        "Put": {
                            "TableName": "TestTable",
                            "Item": {
                                "pk": {"S": "user-transact"},
                                "sk": {"S": "profile"},
                                "name": {"S": "Test User"}
                            },
                            "ConditionExpression": "attribute_not_exists(#0)",
                            "ExpressionAttributeNames": {
                                "#0": "pk"
                            }
                        }
                    }]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Second attempt — item exists now, condition should fail
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.TransactWriteItems")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TransactItems": [{
                        "Put": {
                            "TableName": "TestTable",
                            "Item": {
                                "pk": {"S": "user-transact"},
                                "sk": {"S": "profile"},
                                "name": {"S": "Duplicate"}
                            },
                            "ConditionExpression": "attribute_not_exists(#0)",
                            "ExpressionAttributeNames": {
                                "#0": "pk"
                            }
                        }
                    }]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("TransactionCanceledException"));
    }

    @Test
    @Order(17)
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

    @Test
    @Order(18)
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
}
