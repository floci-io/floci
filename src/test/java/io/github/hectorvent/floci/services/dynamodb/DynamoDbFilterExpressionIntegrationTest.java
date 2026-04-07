package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for DynamoDB filter expression evaluation via the HTTP API.
 * Covers comparison operators on BOOL types, IN operator, and OR logical operator.
 */
@QuarkusTest
class DynamoDbFilterExpressionIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TABLE_NAME = "FilterExprTestTable";
    private static boolean tableCreated = false;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void ensureTableAndData() {
        if (tableCreated) return;

        // Create table
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}]
                }
                """.formatted(TABLE_NAME))
        .when().post("/").then().statusCode(200);

        // Insert items
        putItem("""
            {"pk": {"S": "u1"}, "deleted": {"BOOL": false}, "status": {"N": "1"}, "category": {"S": "A"}}
            """);
        putItem("""
            {"pk": {"S": "u2"}, "deleted": {"BOOL": true}, "status": {"N": "2"}, "category": {"S": "B"}}
            """);
        putItem("""
            {"pk": {"S": "u3"}, "deleted": {"BOOL": false}, "status": {"N": "1"}, "category": {"S": "A"}}
            """);
        // u4 has no "deleted" attribute — tests attribute_not_exists semantics
        putItem("""
            {"pk": {"S": "u4"}, "status": {"N": "3"}, "category": {"S": "C"}}
            """);

        tableCreated = true;
    }

    // ---- BOOL not-equal (<>) ----

    @Test
    void scanFilterBoolNotEqual_excludesDeletedItems() {
        // deleted <> true should return u1 (false), u3 (false), u4 (missing — <> true is true for missing)
        scanWithFilter(
                "deleted <> :d",
                """
                {":d": {"BOOL": true}}
                """,
                null,
                3);
    }

    @Test
    void scanFilterBoolEqual_matchesFalse() {
        // deleted = false should return u1, u3
        scanWithFilter(
                "deleted = :d",
                """
                {":d": {"BOOL": false}}
                """,
                null,
                2);
    }

    // ---- IN operator ----

    @Test
    void scanFilterInOperator_singleValue() {
        // status IN (:v0) where v0=1 should return u1, u3
        scanWithFilter(
                "status IN (:v0)",
                """
                {":v0": {"N": "1"}}
                """,
                null,
                2);
    }

    @Test
    void scanFilterInOperator_multipleValues() {
        // status IN (:v0, :v1) where v0=1, v1=3 should return u1, u3, u4
        scanWithFilter(
                "status IN (:v0, :v1)",
                """
                {":v0": {"N": "1"}, ":v1": {"N": "3"}}
                """,
                null,
                3);
    }

    @Test
    void scanFilterInOperator_withExpressionAttributeNames() {
        // #cat IN (:v0) where #cat=category, v0="A" should return u1, u3
        scanWithFilter(
                "#cat IN (:v0)",
                """
                {":v0": {"S": "A"}}
                """,
                """
                {"#cat": "category"}
                """,
                2);
    }

    // ---- OR logical operator ----

    @Test
    void scanFilterOrOperator() {
        // status = :v1 OR status = :v2 should return u1, u2, u3 (status 1 or 2)
        scanWithFilter(
                "status = :v1 OR status = :v2",
                """
                {":v1": {"N": "1"}, ":v2": {"N": "2"}}
                """,
                null,
                3);
    }

    @Test
    void scanFilterOrWithAttributeNotExists() {
        // attribute_not_exists(deleted) OR deleted = :false — should match u1, u3, u4
        scanWithFilter(
                "attribute_not_exists(deleted) OR deleted = :notDel",
                """
                {":notDel": {"BOOL": false}}
                """,
                null,
                3);
    }

    // ---- Combined AND + OR ----

    @Test
    void scanFilterAndWithOr() {
        // (status = :v1 OR status = :v3) AND category = :catA
        // status=1 OR status=3 → u1,u3,u4; AND category=A → u1,u3
        scanWithFilter(
                "(status = :v1 OR status = :v3) AND category = :catA",
                """
                {":v1": {"N": "1"}, ":v3": {"N": "3"}, ":catA": {"S": "A"}}
                """,
                null,
                2);
    }

    // ---- NOT operator ----

    @Test
    void scanFilterNotOperator() {
        // NOT deleted = :true should return u1, u3, u4
        scanWithFilter(
                "NOT deleted = :d",
                """
                {":d": {"BOOL": true}}
                """,
                null,
                3);
    }

    // ---- Nested parentheses and complex expressions ----

    @Test
    void scanFilterNestedParentheses() {
        // ((status = :v1 OR status = :v3) AND category = :catA) OR deleted = :del
        // (status 1 or 3) AND category A → u1,u3; OR deleted=true → u2
        // Total: u1, u2, u3
        scanWithFilter(
                "((status = :v1 OR status = :v3) AND category = :catA) OR deleted = :del",
                """
                {":v1": {"N": "1"}, ":v3": {"N": "3"}, ":catA": {"S": "A"}, ":del": {"BOOL": true}}
                """,
                null,
                3);
    }

    @Test
    void scanFilterNotWithParenthesizedAnd() {
        // NOT (deleted = :true AND status = :v2) — negate (u2 only) → u1, u3, u4
        scanWithFilter(
                "NOT (deleted = :d AND status = :v2)",
                """
                {":d": {"BOOL": true}, ":v2": {"N": "2"}}
                """,
                null,
                3);
    }

    @Test
    void scanFilterDoubleNestedParens() {
        // ((category = :a)) should work like category = :a → u1, u3
        scanWithFilter(
                "((category = :a))",
                """
                {":a": {"S": "A"}}
                """,
                null,
                2);
    }

    // ---- Parenthesized BETWEEN in KeyConditionExpression ----

    @Test
    void queryWithParenthesizedBetweenInKeyCondition() {
        // Create a table with partition + sort key for this test
        String tbl = "BetweenTestTable";
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "KeySchema": [
                        {"AttributeName": "pk", "KeyType": "HASH"},
                        {"AttributeName": "sk", "KeyType": "RANGE"}
                    ],
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "sk", "AttributeType": "S"}
                    ]
                }
                """.formatted(tbl))
        .when().post("/").then().statusCode(200);

        // Insert items
        for (String sk : new String[]{"2026-01-01T00:00:00Z#a", "2026-06-15T00:00:00Z#b", "2026-12-31T00:00:00Z#c"}) {
            given()
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName": "%s", "Item": {"pk": {"S": "r1"}, "sk": {"S": "%s"}}}
                    """.formatted(tbl, sk))
            .when().post("/").then().statusCode(200);
        }

        // Query with parenthesized BETWEEN — this is what the AWS SDK commonly generates
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "KeyConditionExpression": "pk = :pk AND (sk BETWEEN :start AND :end)",
                    "ExpressionAttributeValues": {
                        ":pk": {"S": "r1"},
                        ":start": {"S": "2026-01-01T00:00:00Z#"},
                        ":end": {"S": "2026-12-31T23:59:59Z#"}
                    }
                }
                """.formatted(tbl))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(3));
    }

    @Test
    void queryWithCompactAndBetweenInKeyCondition() {
        // EfficientDynamoDb compact format: "(pk = :v0)AND(sk BETWEEN :v1 AND :v2)" — no spaces around AND
        String tbl = "CompactBetweenTestTable";
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "KeySchema": [
                        {"AttributeName": "pk", "KeyType": "HASH"},
                        {"AttributeName": "sk", "KeyType": "RANGE"}
                    ],
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "sk", "AttributeType": "S"}
                    ]
                }
                """.formatted(tbl))
        .when().post("/").then().statusCode(200);

        for (String sk : new String[]{"2026-01-01Z#a", "2026-06-15Z#b", "2026-12-31Z#c"}) {
            given()
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName": "%s", "Item": {"pk": {"S": "r1"}, "sk": {"S": "%s"}}}
                    """.formatted(tbl, sk))
            .when().post("/").then().statusCode(200);
        }

        // Compact format: no spaces around AND, parens wrapping each sub-expression
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "KeyConditionExpression": "(#f0 = :v0)AND(#f1 BETWEEN :v1 AND :v2)",
                    "ExpressionAttributeNames": {"#f0": "pk", "#f1": "sk"},
                    "ExpressionAttributeValues": {
                        ":v0": {"S": "r1"},
                        ":v1": {"S": "2026-01-01Z#"},
                        ":v2": {"S": "2026-12-31Z#z"}
                    }
                }
                """.formatted(tbl))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(3));
    }

    // ---- Helpers ----

    private void putItem(String itemJson) {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s", "Item": %s}
                """.formatted(TABLE_NAME, itemJson))
        .when().post("/").then().statusCode(200);
    }

    private void scanWithFilter(String filterExpression, String exprAttrValuesJson,
                                 String exprAttrNamesJson, int expectedCount) {
        var body = new StringBuilder();
        body.append("{");
        body.append("\"TableName\": \"").append(TABLE_NAME).append("\",");
        body.append("\"FilterExpression\": \"").append(filterExpression).append("\",");
        body.append("\"ExpressionAttributeValues\": ").append(exprAttrValuesJson);
        if (exprAttrNamesJson != null) {
            body.append(",\"ExpressionAttributeNames\": ").append(exprAttrNamesJson);
        }
        body.append("}");

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Scan")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body(body.toString())
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(expectedCount));
    }
}
