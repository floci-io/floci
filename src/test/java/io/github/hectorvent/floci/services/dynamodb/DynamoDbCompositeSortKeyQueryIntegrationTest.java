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

/**
 * Wire-protocol regression for floci-io/floci#1675: a Query against an index whose sort key is
 * composite (more than one RANGE attribute, e.g. {@code state RANGE, createdAt RANGE}) must order
 * by ALL sort-key attributes in key-schema order, so {@code ScanIndexForward=false} yields the
 * reverse of the full composite order.
 *
 * <p>Previously only the first RANGE attribute (here {@code state}, identical across the rows) drove
 * ordering: {@code createdAt} was ignored, and the stable sort merely preserved (or, when
 * {@code ScanIndexForward=false}, reversed) base-table storage order. The items below are inserted
 * so their storage order (requestId a, b, c) deliberately disagrees with {@code createdAt} order,
 * so a correct result can only come from sorting on the second composite component.
 *
 * <p>DynamoDB natively supports multi-attribute composite keys on secondary indexes, so emulating
 * full-key ordering is required for parity.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbCompositeSortKeyQueryIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TABLE = "CompositeSortKeyRequests";
    private static final String INDEX = "memberIndex";

    // KeyCondition on the composite GSI: partition (memberName) + both sort components.
    private static final String KEY_CONDITION =
        "memberName = :pk AND #st = :st AND createdAt BETWEEN :from AND :to";
    private static final String EXPRESSION_ATTRIBUTE_VALUES = """
        {
            ":pk": {"S": "alice"},
            ":st": {"S": "ACTIVE"},
            ":from": {"S": "1970-01-01T00:00:00Z"},
            ":to": {"S": "2100-01-01T00:00:00Z"}
        }
        """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createTableWithCompositeSortKeyIndex() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "KeySchema": [
                        {"AttributeName": "requestId", "KeyType": "HASH"}
                    ],
                    "AttributeDefinitions": [
                        {"AttributeName": "requestId", "AttributeType": "S"},
                        {"AttributeName": "memberName", "AttributeType": "S"},
                        {"AttributeName": "state", "AttributeType": "S"},
                        {"AttributeName": "createdAt", "AttributeType": "S"}
                    ],
                    "GlobalSecondaryIndexes": [
                        {
                            "IndexName": "%s",
                            "KeySchema": [
                                {"AttributeName": "memberName", "KeyType": "HASH"},
                                {"AttributeName": "state", "KeyType": "RANGE"},
                                {"AttributeName": "createdAt", "KeyType": "RANGE"}
                            ],
                            "Projection": {"ProjectionType": "ALL"}
                        }
                    ]
                }
                """.formatted(TABLE, INDEX))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableDescription.GlobalSecondaryIndexes[0].IndexName", equalTo(INDEX))
            .body("TableDescription.GlobalSecondaryIndexes[0].KeySchema.size()", equalTo(3));
    }

    @Test
    @Order(2)
    void putItemsWhoseStorageOrderDisagreesWithCreatedAt() {
        // Same memberName + state across all three; requestId (storage) order a, b, c is chosen to
        // DISAGREE with createdAt order (a=:03, b=:01, c=:02).
        String[] items = {
            "{\"requestId\":{\"S\":\"a\"},\"memberName\":{\"S\":\"alice\"},"
                + "\"state\":{\"S\":\"ACTIVE\"},\"createdAt\":{\"S\":\"2026-07-14T00:00:03Z\"}}",
            "{\"requestId\":{\"S\":\"b\"},\"memberName\":{\"S\":\"alice\"},"
                + "\"state\":{\"S\":\"ACTIVE\"},\"createdAt\":{\"S\":\"2026-07-14T00:00:01Z\"}}",
            "{\"requestId\":{\"S\":\"c\"},\"memberName\":{\"S\":\"alice\"},"
                + "\"state\":{\"S\":\"ACTIVE\"},\"createdAt\":{\"S\":\"2026-07-14T00:00:02Z\"}}"
        };
        for (String item : items) {
            given()
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("{\"TableName\":\"" + TABLE + "\",\"Item\":" + item + "}")
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
    }

    @Test
    @Order(3)
    void queryAscendingOrdersByFullCompositeSortKey() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "IndexName": "%s",
                    "KeyConditionExpression": "%s",
                    "ExpressionAttributeNames": {"#st": "state"},
                    "ExpressionAttributeValues": %s,
                    "ScanIndexForward": true
                }
                """.formatted(TABLE, INDEX, KEY_CONDITION, EXPRESSION_ATTRIBUTE_VALUES))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(3))
            .body("Items[0].createdAt.S", equalTo("2026-07-14T00:00:01Z"))
            .body("Items[1].createdAt.S", equalTo("2026-07-14T00:00:02Z"))
            .body("Items[2].createdAt.S", equalTo("2026-07-14T00:00:03Z"));
    }

    @Test
    @Order(4)
    void queryDescendingReversesFullCompositeSortKey() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "IndexName": "%s",
                    "KeyConditionExpression": "%s",
                    "ExpressionAttributeNames": {"#st": "state"},
                    "ExpressionAttributeValues": %s,
                    "ScanIndexForward": false
                }
                """.formatted(TABLE, INDEX, KEY_CONDITION, EXPRESSION_ATTRIBUTE_VALUES))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(3))
            .body("Items[0].createdAt.S", equalTo("2026-07-14T00:00:03Z"))
            .body("Items[1].createdAt.S", equalTo("2026-07-14T00:00:02Z"))
            .body("Items[2].createdAt.S", equalTo("2026-07-14T00:00:01Z"));
    }
}
