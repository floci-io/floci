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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-protocol regression for floci-io/floci#2383: when a Query or Scan stops because it
 * reached {@code Limit}, DynamoDB returns a {@code LastEvaluatedKey} even if the stop position
 * happens to be the last item of the result set. DynamoDB does not look ahead — per the
 * developer guide, "the absence of LastEvaluatedKey is the only way to know that you have
 * reached the end of the result set", so the client discovers the end on the follow-up request,
 * which returns an empty page with no LastEvaluatedKey.
 *
 * <p>Covers the exact-Limit boundary for ascending Query, descending Query
 * ({@code ScanIndexForward=false}), GSI Query (cursor must carry index + table keys), and Scan,
 * each followed by the cursor round-trip proving the follow-up page is empty and final.
 * Verified against {@code amazon/dynamodb-local} and LocalStack, which both behave this way.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbLimitBoundaryPaginationIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TABLE = "LimitBoundaryPagination";
    private static final String INDEX = "categoryIndex";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createTableAndItems() throws Exception {
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
                        {"AttributeName": "pk", "AttributeType": "N"},
                        {"AttributeName": "sk", "AttributeType": "N"},
                        {"AttributeName": "category", "AttributeType": "S"}
                    ],
                    "GlobalSecondaryIndexes": [
                        {
                            "IndexName": "%s",
                            "KeySchema": [
                                {"AttributeName": "category", "KeyType": "HASH"},
                                {"AttributeName": "sk", "KeyType": "RANGE"}
                            ],
                            "Projection": {"ProjectionType": "ALL"}
                        }
                    ],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(TABLE, INDEX))
        .when().post("/")
        .then().statusCode(200);

        for (int i = 1; i <= 3; i++) {
            given()
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {
                        "TableName": "%s",
                        "Item": {
                            "pk": {"N": "1"},
                            "sk": {"N": "%d"},
                            "category": {"S": "a"}
                        }
                    }
                    """.formatted(TABLE, i))
            .when().post("/")
            .then().statusCode(200);
        }
    }

    @Test
    @Order(2)
    void queryLimitAtEndReturnsLastEvaluatedKeyAndFollowUpPageIsEmpty() throws Exception {
        // 3 items, Limit=3: the read stops at the Limit boundary, so a cursor must be surfaced.
        JsonNode first = query("""
            {
                "TableName": "%s",
                "KeyConditionExpression": "pk = :p",
                "ExpressionAttributeValues": {":p": {"N": "1"}},
                "Limit": 3
            }
            """.formatted(TABLE));
        assertEquals(3, first.path("Count").asInt());
        JsonNode lek = first.path("LastEvaluatedKey");
        assertTrue(lek.isObject(), "LastEvaluatedKey must be present when Limit stops the read: " + first);
        assertEquals("3", lek.path("sk").path("N").asText(), "cursor must point at the last returned item");

        // The follow-up request is how the client learns it reached the end: empty page, no cursor.
        JsonNode second = query("""
            {
                "TableName": "%s",
                "KeyConditionExpression": "pk = :p",
                "ExpressionAttributeValues": {":p": {"N": "1"}},
                "Limit": 3,
                "ExclusiveStartKey": %s
            }
            """.formatted(TABLE, lek.toString()));
        assertEquals(0, second.path("Count").asInt());
        assertTrue(second.path("LastEvaluatedKey").isMissingNode() || second.path("LastEvaluatedKey").isNull(),
                "follow-up page past the end must not carry a cursor: " + second);
    }

    @Test
    @Order(3)
    void queryLimitBelowEndStillReturnsLastEvaluatedKey() throws Exception {
        // Guard against regressing the mid-result-set case: Limit=2 of 3 must keep its cursor.
        JsonNode first = query("""
            {
                "TableName": "%s",
                "KeyConditionExpression": "pk = :p",
                "ExpressionAttributeValues": {":p": {"N": "1"}},
                "Limit": 2
            }
            """.formatted(TABLE));
        assertEquals(2, first.path("Count").asInt());
        assertEquals("2", first.path("LastEvaluatedKey").path("sk").path("N").asText());

        // And the no-Limit read of the full set must stay cursor-free.
        JsonNode all = query("""
            {
                "TableName": "%s",
                "KeyConditionExpression": "pk = :p",
                "ExpressionAttributeValues": {":p": {"N": "1"}}
            }
            """.formatted(TABLE));
        assertEquals(3, all.path("Count").asInt());
        assertTrue(all.path("LastEvaluatedKey").isMissingNode() || all.path("LastEvaluatedKey").isNull(),
                "a read that exhausts the result set without hitting Limit must not carry a cursor: " + all);
    }

    @Test
    @Order(4)
    void descendingQueryLimitAtEndReturnsLastEvaluatedKey() throws Exception {
        JsonNode first = query("""
            {
                "TableName": "%s",
                "KeyConditionExpression": "pk = :p",
                "ExpressionAttributeValues": {":p": {"N": "1"}},
                "Limit": 3,
                "ScanIndexForward": false
            }
            """.formatted(TABLE));
        assertEquals(3, first.path("Count").asInt());
        JsonNode lek = first.path("LastEvaluatedKey");
        assertTrue(lek.isObject(), "descending Query must also surface a cursor at the Limit boundary: " + first);
        assertEquals("1", lek.path("sk").path("N").asText(),
                "descending cursor must point at the last returned (lowest) item");

        JsonNode second = query("""
            {
                "TableName": "%s",
                "KeyConditionExpression": "pk = :p",
                "ExpressionAttributeValues": {":p": {"N": "1"}},
                "Limit": 3,
                "ScanIndexForward": false,
                "ExclusiveStartKey": %s
            }
            """.formatted(TABLE, lek.toString()));
        assertEquals(0, second.path("Count").asInt());
        assertTrue(second.path("LastEvaluatedKey").isMissingNode() || second.path("LastEvaluatedKey").isNull());
    }

    @Test
    @Order(5)
    void gsiQueryLimitAtEndReturnsLastEvaluatedKeyWithIndexAndTableKeys() throws Exception {
        JsonNode first = query("""
            {
                "TableName": "%s",
                "IndexName": "%s",
                "KeyConditionExpression": "category = :c",
                "ExpressionAttributeValues": {":c": {"S": "a"}},
                "Limit": 3
            }
            """.formatted(TABLE, INDEX));
        assertEquals(3, first.path("Count").asInt());
        JsonNode lek = first.path("LastEvaluatedKey");
        assertTrue(lek.isObject(), "GSI Query must surface a cursor at the Limit boundary: " + first);
        // The index cursor must carry both the index key and the table key.
        assertNotNull(lek.get("category"), "LastEvaluatedKey missing index partition key: " + lek);
        assertNotNull(lek.get("pk"), "LastEvaluatedKey missing table partition key: " + lek);
        assertNotNull(lek.get("sk"), "LastEvaluatedKey missing sort key: " + lek);

        JsonNode second = query("""
            {
                "TableName": "%s",
                "IndexName": "%s",
                "KeyConditionExpression": "category = :c",
                "ExpressionAttributeValues": {":c": {"S": "a"}},
                "Limit": 3,
                "ExclusiveStartKey": %s
            }
            """.formatted(TABLE, INDEX, lek.toString()));
        assertEquals(0, second.path("Count").asInt());
        assertTrue(second.path("LastEvaluatedKey").isMissingNode() || second.path("LastEvaluatedKey").isNull());
    }

    @Test
    @Order(6)
    void scanLimitAtEndReturnsLastEvaluatedKeyAndFollowUpPageIsEmpty() throws Exception {
        JsonNode first = scan("""
            {
                "TableName": "%s",
                "Limit": 3
            }
            """.formatted(TABLE));
        assertEquals(3, first.path("Count").asInt());
        JsonNode lek = first.path("LastEvaluatedKey");
        assertTrue(lek.isObject(), "Scan must surface a cursor at the Limit boundary: " + first);

        JsonNode second = scan("""
            {
                "TableName": "%s",
                "Limit": 3,
                "ExclusiveStartKey": %s
            }
            """.formatted(TABLE, lek.toString()));
        assertEquals(0, second.path("Count").asInt());
        assertTrue(second.path("LastEvaluatedKey").isMissingNode() || second.path("LastEvaluatedKey").isNull(),
                "follow-up Scan page past the end must not carry a cursor: " + second);
    }

    // --- 1 MB cap interaction (floci-io/floci#2389 review): the read stops at whichever
    // boundary comes first. Per the API reference, "if the processed dataset size exceeds
    // 1 MB before DynamoDB reaches this limit, it stops the operation" — so the size cap
    // wins over Limit even when both would trigger on the same item. Note that neither
    // amazon/dynamodb-local nor LocalStack implements the 1 MB page cap (both return the
    // full 1.08 MB result set below in one page), so these expectations come from the
    // documented service contract rather than an emulator cross-check.

    private static final String BIG_TABLE = "LimitBoundarySizeCap";
    private static final String BIG_PAYLOAD = "x".repeat(360_000);

    @Test
    @Order(7)
    void queryStopsAtSizeCapBeforeExactLimit() throws Exception {
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
                        {"AttributeName": "pk", "AttributeType": "N"},
                        {"AttributeName": "sk", "AttributeType": "N"}
                    ],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(BIG_TABLE))
        .when().post("/")
        .then().statusCode(200);
        for (int i = 1; i <= 3; i++) {
            given()
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {
                        "TableName": "%s",
                        "Item": {
                            "pk": {"N": "1"},
                            "sk": {"N": "%d"},
                            "data": {"S": "%s"}
                        }
                    }
                    """.formatted(BIG_TABLE, i, BIG_PAYLOAD))
            .when().post("/")
            .then().statusCode(200);
        }

        // Three ~360 KB items with Limit=3: reading the third item would cross the
        // 1 MB cap, so the read stops after two items — the size cap wins over the
        // Limit boundary that would land on the very same item.
        JsonNode first = query("""
            {
                "TableName": "%s",
                "KeyConditionExpression": "pk = :p",
                "ExpressionAttributeValues": {":p": {"N": "1"}},
                "Limit": 3
            }
            """.formatted(BIG_TABLE));
        assertEquals(2, first.path("Count").asInt(), "size cap must stop the read before the third item: " + summary(first));
        assertEquals(2, first.path("ScannedCount").asInt());
        assertEquals("2", first.path("LastEvaluatedKey").path("sk").path("N").asText(),
                "cursor must anchor to the last item that fit under the cap");

        // The follow-up page returns the remaining item and, having exhausted the
        // result set without hitting a boundary, carries no cursor.
        JsonNode second = query("""
            {
                "TableName": "%s",
                "KeyConditionExpression": "pk = :p",
                "ExpressionAttributeValues": {":p": {"N": "1"}},
                "Limit": 3,
                "ExclusiveStartKey": %s
            }
            """.formatted(BIG_TABLE, first.path("LastEvaluatedKey").toString()));
        assertEquals(1, second.path("Count").asInt());
        assertTrue(second.path("LastEvaluatedKey").isMissingNode() || second.path("LastEvaluatedKey").isNull());
    }

    @Test
    @Order(8)
    void scanStopsAtSizeCapBeforeExactLimit() throws Exception {
        JsonNode first = scan("""
            {
                "TableName": "%s",
                "Limit": 3
            }
            """.formatted(BIG_TABLE));
        assertEquals(2, first.path("Count").asInt(), "size cap must stop the read before the third item: " + summary(first));
        assertEquals(2, first.path("ScannedCount").asInt());
        assertEquals("2", first.path("LastEvaluatedKey").path("sk").path("N").asText());

        JsonNode second = scan("""
            {
                "TableName": "%s",
                "Limit": 3,
                "ExclusiveStartKey": %s
            }
            """.formatted(BIG_TABLE, first.path("LastEvaluatedKey").toString()));
        assertEquals(1, second.path("Count").asInt());
        assertTrue(second.path("LastEvaluatedKey").isMissingNode() || second.path("LastEvaluatedKey").isNull());
    }

    @Test
    @Order(9)
    void scanWithFilterCountsScannedBytesAndAnchorsCursorToScannedItem() throws Exception {
        // The 1 MB cap counts the data the scan READS (pre-filter), and the cursor
        // anchors to the read position — with a filter, a page can legitimately
        // return zero matching items and still carry a LastEvaluatedKey pointing at
        // an item that was not returned.
        String table = "LimitBoundaryFilterSizeCap";
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
                        {"AttributeName": "pk", "AttributeType": "N"},
                        {"AttributeName": "sk", "AttributeType": "N"}
                    ],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(table))
        .when().post("/")
        .then().statusCode(200);
        // Five ~300 KB items; only sk=5 matches the filter.
        String payload = "y".repeat(300_000);
        for (int i = 1; i <= 5; i++) {
            given()
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {
                        "TableName": "%s",
                        "Item": {
                            "pk": {"N": "1"},
                            "sk": {"N": "%d"},
                            "wanted": {"N": "%d"},
                            "data": {"S": "%s"}
                        }
                    }
                    """.formatted(table, i, i == 5 ? 1 : 0, payload))
            .when().post("/")
            .then().statusCode(200);
        }

        // Reading item 4 would cross the cap (900 KB + 300 KB), so the first page
        // scans items 1-3, none of which match: Count=0 but the cursor points at
        // sk=3 — an item the response did not return.
        JsonNode first = scan("""
            {
                "TableName": "%s",
                "FilterExpression": "wanted = :w",
                "ExpressionAttributeValues": {":w": {"N": "1"}}
            }
            """.formatted(table));
        assertEquals(0, first.path("Count").asInt(), summary(first));
        assertEquals(3, first.path("ScannedCount").asInt(), "the cap counts scanned (pre-filter) bytes");
        assertEquals("3", first.path("LastEvaluatedKey").path("sk").path("N").asText(),
                "cursor must anchor to the read position, not the last matched item");

        JsonNode second = scan("""
            {
                "TableName": "%s",
                "FilterExpression": "wanted = :w",
                "ExpressionAttributeValues": {":w": {"N": "1"}},
                "ExclusiveStartKey": %s
            }
            """.formatted(table, first.path("LastEvaluatedKey").toString()));
        assertEquals(1, second.path("Count").asInt(), summary(second));
        assertEquals(2, second.path("ScannedCount").asInt());
        assertTrue(second.path("LastEvaluatedKey").isMissingNode() || second.path("LastEvaluatedKey").isNull());
    }

    // --- Sparse index scans (floci-io/floci#2389 review): items missing any index key
    // attribute do not exist in the index, so an index Scan never reads them, never
    // counts them, and never anchors a cursor to them. Expectations verified against
    // amazon/dynamodb-local (Count/ScannedCount exclude sparse items; the exact-Limit
    // cursor carries the full index + table key set).

    private static final String SPARSE_TABLE = "LimitBoundarySparseIndex";
    private static final String SPARSE_INDEX = "cat-idx";

    @Test
    @Order(10)
    void indexScanExcludesSparseItemsFromResultsAndScannedCount() throws Exception {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "KeySchema": [
                        {"AttributeName": "pk", "KeyType": "HASH"}
                    ],
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "N"},
                        {"AttributeName": "category", "AttributeType": "S"},
                        {"AttributeName": "gsisort", "AttributeType": "N"}
                    ],
                    "GlobalSecondaryIndexes": [
                        {
                            "IndexName": "%s",
                            "KeySchema": [
                                {"AttributeName": "category", "KeyType": "HASH"},
                                {"AttributeName": "gsisort", "KeyType": "RANGE"}
                            ],
                            "Projection": {"ProjectionType": "ALL"}
                        }
                    ],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(SPARSE_TABLE, SPARSE_INDEX))
        .when().post("/")
        .then().statusCode(200);
        // pk=1: full index keys. pk=2: index HASH only (missing the index RANGE —
        // the case a partition-key-only sparse check would miss). pk=3: no index
        // keys at all. pk=4: full index keys.
        String[] items = {
            "{\"pk\": {\"N\": \"1\"}, \"category\": {\"S\": \"a\"}, \"gsisort\": {\"N\": \"10\"}}",
            "{\"pk\": {\"N\": \"2\"}, \"category\": {\"S\": \"a\"}}",
            "{\"pk\": {\"N\": \"3\"}}",
            "{\"pk\": {\"N\": \"4\"}, \"category\": {\"S\": \"a\"}, \"gsisort\": {\"N\": \"20\"}}"
        };
        for (String item : items) {
            given()
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("{\"TableName\": \"%s\", \"Item\": %s}".formatted(SPARSE_TABLE, item))
            .when().post("/")
            .then().statusCode(200);
        }

        JsonNode full = scan("""
            {
                "TableName": "%s",
                "IndexName": "%s"
            }
            """.formatted(SPARSE_TABLE, SPARSE_INDEX));
        assertEquals(2, full.path("Count").asInt(), "sparse items must not appear in an index Scan: " + summary(full));
        assertEquals(2, full.path("ScannedCount").asInt(), "sparse items are never read, so they must not be counted");
        assertTrue(full.path("LastEvaluatedKey").isMissingNode() || full.path("LastEvaluatedKey").isNull());
    }

    @Test
    @Order(11)
    void indexScanLimitAtEndReturnsCursorWithFullIndexAndTableKeys() throws Exception {
        // Limit=2 stops exactly at the last index entry (pk=4); the sparse items in
        // between must neither absorb the Limit nor corrupt the cursor.
        JsonNode first = scan("""
            {
                "TableName": "%s",
                "IndexName": "%s",
                "Limit": 2
            }
            """.formatted(SPARSE_TABLE, SPARSE_INDEX));
        assertEquals(2, first.path("Count").asInt(), summary(first));
        JsonNode lek = first.path("LastEvaluatedKey");
        assertTrue(lek.isObject(), "index Scan must surface a cursor at the Limit boundary: " + summary(first));
        assertNotNull(lek.get("category"), "cursor missing index partition key: " + lek);
        assertNotNull(lek.get("gsisort"), "cursor missing index sort key: " + lek);
        assertNotNull(lek.get("pk"), "cursor missing table key: " + lek);

        JsonNode second = scan("""
            {
                "TableName": "%s",
                "IndexName": "%s",
                "Limit": 2,
                "ExclusiveStartKey": %s
            }
            """.formatted(SPARSE_TABLE, SPARSE_INDEX, lek.toString()));
        assertEquals(0, second.path("Count").asInt());
        assertTrue(second.path("LastEvaluatedKey").isMissingNode() || second.path("LastEvaluatedKey").isNull());
    }

    @Test
    @Order(12)
    void scanLimitAboveRemainingItemsReturnsNoCursor() throws Exception {
        // Scan-side counterpart of the Order(3) Query guard: a Limit larger than the
        // result set must not fabricate a cursor.
        JsonNode result = scan("""
            {
                "TableName": "%s",
                "Limit": 5
            }
            """.formatted(TABLE));
        assertEquals(3, result.path("Count").asInt());
        assertTrue(result.path("LastEvaluatedKey").isMissingNode() || result.path("LastEvaluatedKey").isNull(),
                "a Scan that exhausts the table below Limit must not carry a cursor: " + summary(result));
    }

    /** Response summary without the bulky Items array, for assertion messages. */
    private static String summary(JsonNode response) {
        return "{Count=" + response.path("Count") + ", ScannedCount=" + response.path("ScannedCount")
                + ", LastEvaluatedKey=" + response.path("LastEvaluatedKey") + "}";
    }

    private JsonNode query(String body) throws Exception {
        return call("DynamoDB_20120810.Query", body);
    }

    private JsonNode scan(String body) throws Exception {
        return call("DynamoDB_20120810.Scan", body);
    }

    private JsonNode call(String target, String body) throws Exception {
        String response = given()
            .header("X-Amz-Target", target)
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body(body)
        .when().post("/")
        .then().statusCode(200)
        .extract().body().asString();
        return MAPPER.readTree(response);
    }
}
