package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * A paginated Query over a composite-sort-key index must surface a LastEvaluatedKey that carries
     * EVERY sort-key component, not just the first RANGE attribute. Emitting only {@code state}
     * (identical across these rows) loses composite key identity: the cursor no longer pins the row
     * it stopped on. This is the regression from PR review comment r3710297799 — pre-fix the cursor
     * omitted {@code createdAt}.
     */
    @Test
    @Order(5)
    void paginatedQueryEmitsFullCompositeCursor() {
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
                    "ScanIndexForward": true,
                    "Limit": 1
                }
                """.formatted(TABLE, INDEX, KEY_CONDITION, EXPRESSION_ATTRIBUTE_VALUES))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(1))
            // First ascending row by the full composite key is createdAt=:01 (requestId b).
            .body("Items[0].createdAt.S", equalTo("2026-07-14T00:00:01Z"))
            // Cursor pins that exact row: index keys (both components), plus the base-table key.
            .body("LastEvaluatedKey.memberName.S", equalTo("alice"))
            .body("LastEvaluatedKey.state.S", equalTo("ACTIVE"))
            .body("LastEvaluatedKey.createdAt.S", equalTo("2026-07-14T00:00:01Z"))
            .body("LastEvaluatedKey.requestId.S", notNullValue());
    }

    /**
     * Walking every page with Limit=1 must return all three rows in full composite order, each
     * exactly once, with the cursor advancing on every page (no repeat, no skip).
     */
    @Test
    @Order(6)
    void paginatedQueryWalksAllRowsExactlyOnceInCompositeOrder() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<String> collected = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();
        JsonNode exclusiveStartKey = null;
        int pages = 0;

        do {
            String startClause = exclusiveStartKey == null
                ? ""
                : ",\n\"ExclusiveStartKey\": " + mapper.writeValueAsString(exclusiveStartKey);
            String body = """
                {
                    "TableName": "%s",
                    "IndexName": "%s",
                    "KeyConditionExpression": "%s",
                    "ExpressionAttributeNames": {"#st": "state"},
                    "ExpressionAttributeValues": %s,
                    "ScanIndexForward": true,
                    "Limit": 1%s
                }
                """.formatted(TABLE, INDEX, KEY_CONDITION, EXPRESSION_ATTRIBUTE_VALUES, startClause);

            String responseBody = given()
                .header("X-Amz-Target", "DynamoDB_20120810.Query")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body(body)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().body().asString();

            JsonNode root = mapper.readTree(responseBody);
            pages++;
            for (JsonNode item : root.path("Items")) {
                collected.add(item.path("createdAt").path("S").asText());
            }

            JsonNode lek = root.path("LastEvaluatedKey");
            if (lek.isMissingNode() || lek.isNull()) {
                exclusiveStartKey = null;
            } else {
                assertNotNull(lek.get("createdAt"),
                        "LastEvaluatedKey missing composite component createdAt: " + lek);
                String cursor = lek.toString();
                assertTrue(seenCursors.add(cursor),
                        "LastEvaluatedKey repeated — pagination did not advance: " + cursor);
                exclusiveStartKey = lek;
            }
        } while (exclusiveStartKey != null && pages < 10);

        assertEquals(List.of(
                "2026-07-14T00:00:01Z",
                "2026-07-14T00:00:02Z",
                "2026-07-14T00:00:03Z"), collected,
                "Expected all rows once, in full composite order");
    }

    @Test
    @Order(7)
    void queryRejectsSkippedCompositeSortKey() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "IndexName": "%s",
                    "KeyConditionExpression": "memberName = :pk AND createdAt >= :from",
                    "ExpressionAttributeValues": {
                        ":pk": {"S": "alice"},
                        ":from": {"S": "2026-01-01T00:00:00Z"}
                    }
                }
                """.formatted(TABLE, INDEX))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("RANGE key attributes state must have equality conditions specified "
                    + "in the query because a condition is present on key attribute createdAt"));
    }

    @Test
    @Order(8)
    void queryRejectsCompositeSortKeyConditionAfterInequality() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "IndexName": "%s",
                    "KeyConditionExpression": "memberName = :pk AND #st > :st AND createdAt = :createdAt",
                    "ExpressionAttributeNames": {"#st": "state"},
                    "ExpressionAttributeValues": {
                        ":pk": {"S": "alice"},
                        ":st": {"S": "ACTIVE"},
                        ":createdAt": {"S": "2026-07-14T00:00:01Z"}
                    }
                }
                """.formatted(TABLE, INDEX))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("RANGE key attributes state must have equality conditions specified "
                    + "in the query because a condition is present on key attribute createdAt"));
    }

    @Test
    @Order(9)
    void legacyQueryRejectsInvalidCompositeSortKeyConditions() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Query")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "IndexName": "%s",
                    "KeyConditions": {
                        "memberName": {
                            "ComparisonOperator": "EQ",
                            "AttributeValueList": [{"S": "alice"}]
                        },
                        "createdAt": {
                            "ComparisonOperator": "GE",
                            "AttributeValueList": [{"S": "2026-01-01T00:00:00Z"}]
                        }
                    }
                }
                """.formatted(TABLE, INDEX))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("RANGE key attributes state must have equality conditions specified "
                    + "in the query because a condition is present on key attribute createdAt"));
    }
}
