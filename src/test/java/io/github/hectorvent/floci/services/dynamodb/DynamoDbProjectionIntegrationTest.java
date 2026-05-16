package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Regression tests for DynamoDB ProjectionExpression with nested map paths.
 * <p>
 * Covers <a href="https://github.com/floci-io/floci/issues/852">#852</a>:
 * multiple nested paths sharing the same parent map must all be returned,
 * not just the last one.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbProjectionIntegrationTest {

    private static final String CT = "application/x-amz-json-1.0";
    private static final String TABLE = "ProjectionBugTable";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createTable() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(CT)
            .body("""
                {
                    "TableName": "%s",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableDescription.TableName", equalTo(TABLE));
    }

    @Test
    @Order(2)
    void putItemWithNestedMap() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(CT)
            .body("""
                {
                    "TableName": "%s",
                    "Item": {
                        "pk": {"S": "item1"},
                        "data": {"M": {
                            "title": {"S": "Hello"},
                            "answer": {"S": "World"},
                            "sources": {"L": [{"M": {"id": {"S": "1"}}}]}
                        }}
                    }
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    /**
     * Regression test for #852 — Query with multiple nested paths on the same map.
     * Before the fix, only "sources" (the last path) was returned;
     * "title" and "answer" were silently dropped.
     */
    @Test
    @Order(3)
    void queryWithMultipleNestedPathsReturnAllProjected() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(CT)
            .body("""
                {
                    "TableName": "%s",
                    "KeyConditionExpression": "pk = :pk",
                    "ExpressionAttributeValues": {":pk": {"S": "item1"}},
                    "ExpressionAttributeNames": {"#d": "data"},
                    "ProjectionExpression": "pk, #d.title, #d.answer, #d.sources"
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(1))
            .body("Items[0].pk.S", equalTo("item1"))
            .body("Items[0].data.M.title.S", equalTo("Hello"))
            .body("Items[0].data.M.answer.S", equalTo("World"))
            .body("Items[0].data.M.sources.L[0].M.id.S", equalTo("1"));
    }

    /**
     * Same regression test via Scan to verify both code paths.
     */
    @Test
    @Order(4)
    void scanWithMultipleNestedPathsReturnAllProjected() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Scan")
            .contentType(CT)
            .body("""
                {
                    "TableName": "%s",
                    "ExpressionAttributeNames": {"#d": "data"},
                    "ProjectionExpression": "pk, #d.title, #d.answer, #d.sources"
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(1))
            .body("Items[0].pk.S", equalTo("item1"))
            .body("Items[0].data.M.title.S", equalTo("Hello"))
            .body("Items[0].data.M.answer.S", equalTo("World"))
            .body("Items[0].data.M.sources.L[0].M.id.S", equalTo("1"));
    }

    /**
     * Verify that projecting a single nested path (no merge needed) still works.
     */
    @Test
    @Order(5)
    void queryWithSingleNestedPathStillWorks() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(CT)
            .body("""
                {
                    "TableName": "%s",
                    "KeyConditionExpression": "pk = :pk",
                    "ExpressionAttributeValues": {":pk": {"S": "item1"}},
                    "ExpressionAttributeNames": {"#d": "data"},
                    "ProjectionExpression": "pk, #d.title"
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(1))
            .body("Items[0].pk.S", equalTo("item1"))
            .body("Items[0].data.M.title.S", equalTo("Hello"))
            .body("Items[0].data.M.answer", nullValue())
            .body("Items[0].data.M.sources", nullValue());
    }

    @Test
    @Order(100)
    void cleanup() {
        given()
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
