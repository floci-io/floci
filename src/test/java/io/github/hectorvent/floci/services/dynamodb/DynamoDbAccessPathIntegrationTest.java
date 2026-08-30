package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbAccessPathIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TABLE = "access-path-validation";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createTable() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "TableName": "%s",
                  "AttributeDefinitions": [
                    {"AttributeName":"pk","AttributeType":"S"},
                    {"AttributeName":"sk","AttributeType":"S"},
                    {"AttributeName":"status","AttributeType":"S"},
                    {"AttributeName":"createdAt","AttributeType":"S"},
                    {"AttributeName":"alternate","AttributeType":"S"}
                  ],
                  "KeySchema": [
                    {"AttributeName":"pk","KeyType":"HASH"},
                    {"AttributeName":"sk","KeyType":"RANGE"}
                  ],
                  "GlobalSecondaryIndexes": [{
                    "IndexName":"status-index",
                    "KeySchema": [
                      {"AttributeName":"status","KeyType":"HASH"},
                      {"AttributeName":"createdAt","KeyType":"RANGE"}
                    ],
                    "Projection":{"ProjectionType":"INCLUDE","NonKeyAttributes":["summary"]}
                  }],
                  "LocalSecondaryIndexes": [{
                    "IndexName":"alternate-index",
                    "KeySchema": [
                      {"AttributeName":"pk","KeyType":"HASH"},
                      {"AttributeName":"alternate","KeyType":"RANGE"}
                    ],
                    "Projection":{"ProjectionType":"KEYS_ONLY"}
                  }],
                  "BillingMode":"PAY_PER_REQUEST"
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void queryAndScanRejectUnknownIndexOnEmptyTable() {
        request("DynamoDB_20120810.Scan", """
                {"TableName":"%s","IndexName":"missing-index"}
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("The table does not have the specified index: missing-index"));

        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "IndexName":"missing-index",
                  "KeyConditionExpression":"pk = :pk",
                  "ExpressionAttributeValues":{":pk":{"S":"p1"}}
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("The table does not have the specified index: missing-index"));
    }

    @Test
    @Order(3)
    void queryRejectsNonKeyConditionOnEmptyTable() {
        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "KeyConditionExpression":"pk = :pk AND status = :status",
                  "ExpressionAttributeValues":{
                    ":pk":{"S":"p1"},
                    ":status":{"S":"open"}
                  }
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Query key condition not supported"));
    }

    @Test
    @Order(4)
    void tableAndLsiUseTheirSelectedKeySchemas() {
        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "KeyConditionExpression":"sk >= :sk AND pk = :pk",
                  "ExpressionAttributeValues":{
                    ":pk":{"S":"p1"},
                    ":sk":{"S":"a"}
                  }
                }
                """.formatted(TABLE))
            .statusCode(200)
            .body("Count", equalTo(0));

        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "IndexName":"alternate-index",
                  "KeyConditionExpression":"pk = :pk AND sk = :sk",
                  "ExpressionAttributeValues":{
                    ":pk":{"S":"p1"},
                    ":sk":{"S":"s1"}
                  }
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(5)
    void queryRejectsKeyConditionValuesWithWrongSchemaTypes() {
        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "KeyConditionExpression":"pk = :pk AND sk > :sk",
                  "ExpressionAttributeValues":{
                    ":pk":{"S":"p1"},
                    ":sk":{"N":"1"}
                  }
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("One or more parameter values were invalid: "
                    + "Condition parameter type does not match schema type"));

        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "KeyConditionExpression":"pk = :pk",
                  "ExpressionAttributeValues":{":pk":{"S":null}}
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "KeyConditionExpression":"pk = :pk",
                  "ExpressionAttributeValues":{":pk":{"S":"p1","N":"1"}}
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(6)
    void queryRejectsSelectedKeyInFilterExpression() {
        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "IndexName":"status-index",
                  "KeyConditionExpression":"#status = :status",
                  "FilterExpression":"createdAt > :cutoff",
                  "ExpressionAttributeNames":{"#status":"status"},
                  "ExpressionAttributeValues":{
                    ":status":{"S":"open"},
                    ":cutoff":{"S":"2026-01-01"}
                  }
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Filter Expression can only contain non-primary key attributes: "
                    + "Primary key attribute: createdAt"));
    }

    @Test
    @Order(7)
    void queryAndScanRejectNonProjectedGsiAttribute() {
        String query = """
                {
                  "TableName":"%s",
                  "IndexName":"status-index",
                  "KeyConditionExpression":"#status = :status",
                  "ExpressionAttributeNames":{"#status":"status"},
                  "ExpressionAttributeValues":{":status":{"S":"open"}},
                  "ProjectionExpression":"details"
                }
                """.formatted(TABLE);
        request("DynamoDB_20120810.Query", query)
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        request("DynamoDB_20120810.Scan", """
                {
                  "TableName":"%s",
                  "IndexName":"status-index",
                  "ProjectionExpression":"details"
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(8)
    void acceptsProjectedGsiAttributesAndLsiTableFetch() {
        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "IndexName":"status-index",
                  "KeyConditionExpression":"#status = :status",
                  "ExpressionAttributeValues":{":status":{"S":"open"}},
                  "ProjectionExpression":"pk, #status, summary",
                  "ExpressionAttributeNames":{"#status":"status"}
                }
                """.formatted(TABLE))
            .statusCode(200)
            .body("Count", equalTo(0));

        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "IndexName":"alternate-index",
                  "KeyConditionExpression":"pk = :pk",
                  "ExpressionAttributeValues":{":pk":{"S":"p1"}},
                  "Select":"ALL_ATTRIBUTES"
                }
                """.formatted(TABLE))
            .statusCode(200)
            .body("Count", equalTo(0));

        request("DynamoDB_20120810.Scan", """
                {
                  "TableName":"%s",
                  "IndexName":"alternate-index",
                  "ProjectionExpression":"details"
                }
                """.formatted(TABLE))
            .statusCode(200)
            .body("Count", equalTo(0));
    }

    @Test
    @Order(9)
    void gsiStillRejectsConsistentReads() {
        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "IndexName":"status-index",
                  "ConsistentRead":true,
                  "KeyConditionExpression":"#status = :status",
                  "ExpressionAttributeNames":{"#status":"status"},
                  "ExpressionAttributeValues":{":status":{"S":"open"}}
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Consistent reads are not supported on global secondary indexes"));
    }

    @Test
    @Order(9)
    void queryAndScanRejectWrongExclusiveStartKeyTypes() {
        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "KeyConditionExpression":"pk = :pk",
                  "ExpressionAttributeValues":{":pk":{"S":"p1"}},
                  "ExclusiveStartKey":{"pk":{"N":"1"},"sk":{"S":"s1"}}
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("The provided starting key is invalid"));

        request("DynamoDB_20120810.Scan", """
                {
                  "TableName":"%s",
                  "ExclusiveStartKey":{"pk":{"S":"p1"},"sk":{"BOOL":true}}
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("The provided starting key is invalid: "
                    + "The provided key element does not match the schema"));

        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "KeyConditionExpression":"pk = :pk",
                  "ExpressionAttributeValues":{":pk":{"S":"p1"}},
                  "ExclusiveStartKey":{"pk":{"S":null},"sk":{"S":"s1"}}
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("The provided starting key is invalid"));

        request("DynamoDB_20120810.Scan", """
                {
                  "TableName":"%s",
                  "ExclusiveStartKey":{"pk":{"S":123},"sk":{"S":"s1"}}
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(10)
    void queryRejectsMalformedNumericExclusiveStartKey() {
        String numericTable = TABLE + "-numeric";
        request("DynamoDB_20120810.CreateTable", """
                {
                  "TableName":"%s",
                  "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"N"}],
                  "KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}],
                  "BillingMode":"PAY_PER_REQUEST"
                }
                """.formatted(numericTable))
            .statusCode(200);

        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "KeyConditionExpression":"pk = :pk",
                  "ExpressionAttributeValues":{":pk":{"N":"1"}},
                  "ExclusiveStartKey":{"pk":{"N":"not-a-number"}}
                }
                """.formatted(numericTable))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        request("DynamoDB_20120810.DeleteTable", """
                {"TableName":"%s"}
                """.formatted(numericTable))
            .statusCode(200);
    }

    @Test
    @Order(11)
    void queryRejectsWrongIndexExclusiveStartKeyType() {
        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "IndexName":"status-index",
                  "KeyConditionExpression":"#status = :status",
                  "ExpressionAttributeNames":{"#status":"status"},
                  "ExpressionAttributeValues":{":status":{"S":"open"}},
                  "ExclusiveStartKey":{
                    "pk":{"S":"p1"},
                    "sk":{"S":"s1"},
                    "status":{"S":"open"},
                    "createdAt":{"N":"1"}
                  }
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("The provided starting key is invalid"));
    }

    @Test
    @Order(99)
    void deleteTable() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
            .contentType(CONTENT_TYPE)
            .body("{\"TableName\":\"" + TABLE + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static io.restassured.response.ValidatableResponse request(String target, String body) {
        return given()
                .header("X-Amz-Target", target)
                .contentType(CONTENT_TYPE)
                .body(body)
            .when()
                .post("/")
            .then();
    }
}
