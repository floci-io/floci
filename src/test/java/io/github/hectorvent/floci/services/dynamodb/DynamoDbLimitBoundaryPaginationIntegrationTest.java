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
