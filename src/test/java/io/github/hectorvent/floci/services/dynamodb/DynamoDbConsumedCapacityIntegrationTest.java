package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the ConsumedCapacity magnitudes to the DynamoDB billing model, as measured by
 * the paritysuite dynamodb-conformance tier1 suite. Reads cost half a unit per 4KB,
 * doubled for a strongly consistent read, and Query and Scan are sized on what was
 * read before the filter. Writes charge the table plus each index whose stored view
 * the write changes, and no operation reports a read/write split.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbConsumedCapacityIntegrationTest {

    private static final String CT = "application/x-amz-json-1.0";
    private static final String TABLE = "CapacityParityTable";
    private static int testPort;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createTable() {
        testPort = RestAssured.port;
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(CT)
            .body("""
                {
                    "TableName": "%s",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "gsiPk", "AttributeType": "S"},
                        {"AttributeName": "gsi2Pk", "AttributeType": "S"}
                    ],
                    "GlobalSecondaryIndexes": [{
                        "IndexName": "gsi1",
                        "KeySchema": [{"AttributeName": "gsiPk", "KeyType": "HASH"}],
                        "Projection": {"ProjectionType": "ALL"}
                    }, {
                        "IndexName": "gsi2",
                        "KeySchema": [{"AttributeName": "gsi2Pk", "KeyType": "HASH"}],
                        "Projection": {"ProjectionType": "KEYS_ONLY"}
                    }],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void strongConsistentGetItemCostsOneUnitWithNoSplit() {
        putItem("""
            {"pk": {"S": "cc-get"}, "data": {"S": "x"}}
            """);
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .contentType(CT)
            .body("""
                {
                    "TableName": "%s",
                    "Key": {"pk": {"S": "cc-get"}},
                    "ConsistentRead": true,
                    "ReturnConsumedCapacity": "TOTAL"
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConsumedCapacity.CapacityUnits", equalTo(1.0f))
            .body("ConsumedCapacity.ReadCapacityUnits", nullValue())
            .body("ConsumedCapacity.WriteCapacityUnits", nullValue());
    }

    @Test
    @Order(3)
    void eventualGetItemCostsHalfAUnit() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .contentType(CT)
            .body("""
                {
                    "TableName": "%s",
                    "Key": {"pk": {"S": "cc-get"}},
                    "ReturnConsumedCapacity": "TOTAL"
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConsumedCapacity.CapacityUnits", equalTo(0.5f));
    }

    @Test
    @Order(4)
    void queryUnderIndexesReportsTableArmWithNoSplit() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(CT)
            .body("""
                {
                    "TableName": "%s",
                    "KeyConditionExpression": "pk = :pk",
                    "ExpressionAttributeValues": {":pk": {"S": "cc-get"}},
                    "ConsistentRead": true,
                    "ReturnConsumedCapacity": "INDEXES"
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConsumedCapacity.CapacityUnits", equalTo(1.0f))
            .body("ConsumedCapacity.Table.CapacityUnits", equalTo(1.0f))
            .body("ConsumedCapacity.Table.ReadCapacityUnits", nullValue())
            .body("ConsumedCapacity.ReadCapacityUnits", nullValue());
    }

    /**
     * A Scan is billed on what it read, not on what survived the filter. Six 1KB
     * items make the unfiltered read cost more than the half-unit minimum, so a
     * post-filter or per-returned-item model would report a smaller figure for
     * the filter that matches nothing.
     */
    @Test
    @Order(5)
    void scanWithFilterCostsWhatTheUnfilteredScanCosts() {
        for (var i = 0; i < 6; i++) {
            putItem("""
                {"pk": {"S": "cc-scan-%d"}, "keep": {"S": "no"}, "filler": {"S": "%s"}}
                """.formatted(i, "x".repeat(1000)));
        }
        float plain = scanUnits("""
            {"TableName": "%s", "ReturnConsumedCapacity": "TOTAL"}
            """.formatted(TABLE));
        float filtered = scanUnits("""
            {
                "TableName": "%s",
                "FilterExpression": "keep = :k",
                "ExpressionAttributeValues": {":k": {"S": "yes"}},
                "ReturnConsumedCapacity": "TOTAL"
            }
            """.formatted(TABLE));
        assertTrue(plain >= 1.0f, "six 1KB items must cost more than the half-unit minimum");
        assertEquals(plain, filtered, "a filter matching nothing must not change the cost");
    }

    @Test
    @Order(6)
    void putItemReportsTheGsiWriteBesideTheTableWrite() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(CT)
            .body("""
                {
                    "TableName": "%s",
                    "Item": {"pk": {"S": "cc-idx"}, "gsiPk": {"S": "g1"}},
                    "ReturnConsumedCapacity": "INDEXES"
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConsumedCapacity.CapacityUnits", equalTo(2.0f))
            .body("ConsumedCapacity.Table.CapacityUnits", equalTo(1.0f))
            .body("ConsumedCapacity.GlobalSecondaryIndexes.gsi1.CapacityUnits", equalTo(1.0f))
            .body("ConsumedCapacity.LocalSecondaryIndexes", nullValue());
    }

    @Test
    @Order(7)
    void identicalOverwriteChargesNoIndexArms() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(CT)
            .body("""
                {
                    "TableName": "%s",
                    "Item": {"pk": {"S": "cc-idx"}, "gsiPk": {"S": "g1"}},
                    "ReturnConsumedCapacity": "INDEXES"
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConsumedCapacity.CapacityUnits", equalTo(1.0f))
            .body("ConsumedCapacity.Table.CapacityUnits", equalTo(1.0f))
            .body("ConsumedCapacity.GlobalSecondaryIndexes", nullValue());
    }

    @Test
    @Order(8)
    void putItemUnderTotalFoldsTheIndexCostWithNoBreakdown() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(CT)
            .body("""
                {
                    "TableName": "%s",
                    "Item": {"pk": {"S": "cc-idx-total"}, "gsiPk": {"S": "g2"}},
                    "ReturnConsumedCapacity": "TOTAL"
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConsumedCapacity.CapacityUnits", equalTo(2.0f))
            .body("ConsumedCapacity.Table", nullValue())
            .body("ConsumedCapacity.GlobalSecondaryIndexes", nullValue());
    }

    /**
     * A read served by a secondary index is billed on what the index stores, not on
     * the full base item. On real AWS a 20KB item behind a KEYS_ONLY GSI costs 0.5
     * units to query while the same item through an ALL projection carries its size
     * (measured us-east-1, 2026-09-05).
     */
    @Test
    @Order(9)
    void indexReadsAreBilledOnTheProjectedView() {
        putItem("""
            {"pk": {"S": "cc-big"}, "gsiPk": {"S": "g-big"}, "gsi2Pk": {"S": "g-big"}, "filler": {"S": "%s"}}
            """.formatted("x".repeat(9000)));
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(CT)
            .body("""
                {
                    "TableName": "%s",
                    "IndexName": "gsi2",
                    "KeyConditionExpression": "gsi2Pk = :g",
                    "ExpressionAttributeValues": {":g": {"S": "g-big"}},
                    "ReturnConsumedCapacity": "TOTAL"
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(1))
            .body("ConsumedCapacity.CapacityUnits", equalTo(0.5f));
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(CT)
            .body("""
                {
                    "TableName": "%s",
                    "IndexName": "gsi1",
                    "KeyConditionExpression": "gsiPk = :g",
                    "ExpressionAttributeValues": {":g": {"S": "g-big"}},
                    "ReturnConsumedCapacity": "TOTAL"
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(1))
            .body("ConsumedCapacity.CapacityUnits", equalTo(1.5f));
    }

    @Test
    @Order(10)
    void batchWriteItemReportsPerTableEntryWithTouchedArms() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.BatchWriteItem")
            .contentType(CT)
            .body("""
                {
                    "RequestItems": {
                        "%s": [
                            {"PutRequest": {"Item": {"pk": {"S": "cc-bw-1"}, "gsiPk": {"S": "g-bw"}}}},
                            {"PutRequest": {"Item": {"pk": {"S": "cc-bw-2"}}}}
                        ]
                    },
                    "ReturnConsumedCapacity": "INDEXES"
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConsumedCapacity.size()", equalTo(1))
            .body("ConsumedCapacity[0].TableName", equalTo(TABLE))
            .body("ConsumedCapacity[0].CapacityUnits", equalTo(3.0f))
            .body("ConsumedCapacity[0].Table.CapacityUnits", equalTo(2.0f))
            .body("ConsumedCapacity[0].GlobalSecondaryIndexes.gsi1.CapacityUnits", equalTo(1.0f))
            .body("ConsumedCapacity[0].LocalSecondaryIndexes", nullValue());
    }

    @Test
    @Order(11)
    void batchWriteItemUnderTotalFoldsTheArmsWithNoBreakdown() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.BatchWriteItem")
            .contentType(CT)
            .body("""
                {
                    "RequestItems": {
                        "%s": [
                            {"PutRequest": {"Item": {"pk": {"S": "cc-bw-3"}, "gsiPk": {"S": "g-bw2"}}}},
                            {"PutRequest": {"Item": {"pk": {"S": "cc-bw-4"}}}}
                        ]
                    },
                    "ReturnConsumedCapacity": "TOTAL"
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConsumedCapacity.size()", equalTo(1))
            .body("ConsumedCapacity[0].CapacityUnits", equalTo(3.0f))
            .body("ConsumedCapacity[0].Table", nullValue())
            .body("ConsumedCapacity[0].GlobalSecondaryIndexes", nullValue());
    }

    @Test
    @Order(12)
    void batchWriteItemRejectsAnInvalidReturnConsumedCapacity() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.BatchWriteItem")
            .contentType(CT)
            .body("""
                {
                    "RequestItems": {
                        "%s": [{"PutRequest": {"Item": {"pk": {"S": "cc-bw-bad"}}}}]
                    },
                    "ReturnConsumedCapacity": "BOGUS"
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("message", equalTo("1 validation error detected: Value 'BOGUS' at "
                + "'returnConsumedCapacity' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [INDEXES, TOTAL, NONE]"));
    }

    /**
     * A read served by an LSI reports its units under LocalSecondaryIndexes with a
     * zero Table entry, measured on real DynamoDB (us-east-1, 2026-09-05).
     */
    @Test
    @Order(13)
    void lsiReadReportsItsUnitsUnderLocalSecondaryIndexes() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(CT)
            .body("""
                {
                    "TableName": "CapacityLsiTable",
                    "KeySchema": [
                        {"AttributeName": "pk", "KeyType": "HASH"},
                        {"AttributeName": "sk", "KeyType": "RANGE"}
                    ],
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "sk", "AttributeType": "S"},
                        {"AttributeName": "lsiSk", "AttributeType": "S"}
                    ],
                    "LocalSecondaryIndexes": [{
                        "IndexName": "lsi1",
                        "KeySchema": [
                            {"AttributeName": "pk", "KeyType": "HASH"},
                            {"AttributeName": "lsiSk", "KeyType": "RANGE"}
                        ],
                        "Projection": {"ProjectionType": "ALL"}
                    }],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """)
        .when().post("/").then().statusCode(200);
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(CT)
            .body("""
                {
                    "TableName": "CapacityLsiTable",
                    "Item": {"pk": {"S": "p1"}, "sk": {"S": "1"}, "lsiSk": {"S": "L1"}}
                }
                """)
        .when().post("/").then().statusCode(200);
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(CT)
            .body("""
                {
                    "TableName": "CapacityLsiTable",
                    "IndexName": "lsi1",
                    "KeyConditionExpression": "pk = :p",
                    "ExpressionAttributeValues": {":p": {"S": "p1"}},
                    "ConsistentRead": true,
                    "ReturnConsumedCapacity": "INDEXES"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConsumedCapacity.CapacityUnits", equalTo(1.0f))
            .body("ConsumedCapacity.Table.CapacityUnits", equalTo(0.0f))
            .body("ConsumedCapacity.LocalSecondaryIndexes.lsi1.CapacityUnits", equalTo(1.0f))
            .body("ConsumedCapacity.GlobalSecondaryIndexes", nullValue());
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
            .contentType(CT)
            .body("""
                {"TableName": "CapacityLsiTable"}
                """)
        .when().post("/").then().statusCode(200);
    }

    private static void putItem(String itemJson) {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(CT)
            .body("""
                {"TableName": "%s", "Item": %s}
                """.formatted(TABLE, itemJson))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static float scanUnits(String body) {
        return given()
                .header("X-Amz-Target", "DynamoDB_20120810.Scan")
                .contentType(CT)
                .body(body)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("ConsumedCapacity.CapacityUnits");
    }

    @AfterAll
    static void cleanup() {
        given()
                .port(testPort)
                .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
                .contentType(CT)
                .body("""
                    {"TableName": "%s"}
                    """.formatted(TABLE))
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }
}
