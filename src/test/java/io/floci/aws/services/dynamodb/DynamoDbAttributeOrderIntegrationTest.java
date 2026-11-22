package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DynamoDbAttributeOrderIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TABLE = "AttributeOrderTable";
    private static final String ITEM = """
            {"pk":{"S":"k"},"a1":{"S":"1"},"a2":{"S":"2"},"a3":{"S":"3"},"a4":{"S":"4"},"a5":{"S":"5"},
             "m":{"M":{"k1":{"S":"1"},"k2":{"S":"2"},"k3":{"S":"3"},"k4":{"S":"4"},"k5":{"S":"5"},"k6":{"S":"6"}}}}
            """;
    private static final int CALLS = 30;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createTable() {
        call("CreateTable", """
                {"TableName":"%s","BillingMode":"PAY_PER_REQUEST",
                 "KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"}]}
                """.formatted(TABLE));
    }

    private static String call(String action, String body) {
        return given()
                .header("X-Amz-Target", "DynamoDB_20120810." + action)
                .contentType(CONTENT_TYPE)
                .body(body)
                .when().post("/")
                .then().extract().asString();
    }

    private static void putItem() {
        call("PutItem", "{\"TableName\":\"" + TABLE + "\",\"Item\":" + ITEM + "}");
    }

    private static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(body, e);
        }
    }

    private static String order(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return String.join(",", names);
    }

    private static String topAndMapOrder(JsonNode item) {
        return order(item) + "|" + order(item.get("m").get("M"));
    }

    private static Set<String> orders(Supplier<JsonNode> itemSource) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < CALLS; i++) {
            seen.add(topAndMapOrder(itemSource.get()));
        }
        return seen;
    }

    private static void assertShuffled(Set<String> seen) {
        assertTrue(seen.size() > 1, "expected more than one order but saw " + seen);
        Set<String> mapOrders = new HashSet<>();
        Set<String> topOrders = new HashSet<>();
        for (String s : seen) {
            String[] parts = s.split("\\|");
            topOrders.add(parts[0]);
            mapOrders.add(parts[1]);
        }
        assertTrue(topOrders.size() > 1, "top-level order never changed");
        assertTrue(mapOrders.size() > 1, "map key order never changed");
    }

    private static void assertStable(Set<String> seen) {
        assertEquals(1, seen.size(), "expected one order but saw " + seen);
    }

    @Test
    void putItemAllOldShuffles() {
        putItem();
        assertShuffled(orders(() -> parse(call("PutItem", "{\"TableName\":\"" + TABLE + "\",\"Item\":" + ITEM
                + ",\"ReturnValues\":\"ALL_OLD\"}")).get("Attributes")));
    }

    @Test
    void updateItemReturnValuesShuffle() {
        putItem();
        for (String mode : List.of("ALL_OLD", "ALL_NEW")) {
            assertShuffled(orders(() -> parse(call("UpdateItem", "{\"TableName\":\"" + TABLE
                    + "\",\"Key\":{\"pk\":{\"S\":\"k\"}},\"UpdateExpression\":\"SET a1 = :v\","
                    + "\"ExpressionAttributeValues\":{\":v\":{\"S\":\"1\"}},\"ReturnValues\":\"" + mode + "\"}"))
                    .get("Attributes")));
        }
    }

    @Test
    void updateItemUpdatedValuesShuffleNestedMap() {
        putItem();
        for (String mode : List.of("UPDATED_OLD", "UPDATED_NEW")) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < CALLS; i++) {
                JsonNode attributes = parse(call("UpdateItem", "{\"TableName\":\"" + TABLE
                        + "\",\"Key\":{\"pk\":{\"S\":\"k\"}},\"UpdateExpression\":\"SET m = :v\","
                        + "\"ExpressionAttributeValues\":{\":v\":" + parse(ITEM).get("m")
                        + "},\"ReturnValues\":\"" + mode + "\"}")).get("Attributes");
                seen.add(order(attributes.get("m").get("M")));
            }
            assertTrue(seen.size() > 1, mode + " saw " + seen);
        }
    }

    @Test
    void deleteItemAllOldShuffles() {
        assertShuffled(orders(() -> {
            putItem();
            return parse(call("DeleteItem", "{\"TableName\":\"" + TABLE
                    + "\",\"Key\":{\"pk\":{\"S\":\"k\"}},\"ReturnValues\":\"ALL_OLD\"}")).get("Attributes");
        }));
    }

    @Test
    void failedConditionItemShuffles() {
        putItem();
        String conditionFails = ",\"ConditionExpression\":\"attribute_not_exists(pk)\","
                + "\"ReturnValuesOnConditionCheckFailure\":\"ALL_OLD\"}";
        assertShuffled(orders(() -> parse(call("PutItem", "{\"TableName\":\"" + TABLE + "\",\"Item\":" + ITEM
                + conditionFails)).get("Item")));
        assertShuffled(orders(() -> parse(call("UpdateItem", "{\"TableName\":\"" + TABLE
                + "\",\"Key\":{\"pk\":{\"S\":\"k\"}},\"UpdateExpression\":\"SET a1 = :v\","
                + "\"ExpressionAttributeValues\":{\":v\":{\"S\":\"1\"}}" + conditionFails)).get("Item")));
        assertShuffled(orders(() -> parse(call("DeleteItem", "{\"TableName\":\"" + TABLE
                + "\",\"Key\":{\"pk\":{\"S\":\"k\"}}" + conditionFails)).get("Item")));
    }

    @Test
    void transactGetItemsShuffles() {
        putItem();
        assertShuffled(orders(() -> parse(call("TransactGetItems", "{\"TransactItems\":[{\"Get\":{\"TableName\":\""
                + TABLE + "\",\"Key\":{\"pk\":{\"S\":\"k\"}}}}]}")).get("Responses").get(0).get("Item")));
        assertShuffled(orders(() -> parse(call("TransactGetItems", "{\"TransactItems\":[{\"Get\":{\"TableName\":\""
                + TABLE + "\",\"Key\":{\"pk\":{\"S\":\"k\"}},\"ProjectionExpression\":\"pk, a1, m\"}}]}"))
                .get("Responses").get(0).get("Item")));
    }

    @Test
    void transactWriteCancellationItemShuffles() {
        putItem();
        assertShuffled(orders(() -> parse(call("TransactWriteItems", "{\"TransactItems\":[{\"ConditionCheck\":"
                + "{\"TableName\":\"" + TABLE + "\",\"Key\":{\"pk\":{\"S\":\"k\"}},"
                + "\"ConditionExpression\":\"attribute_not_exists(pk)\","
                + "\"ReturnValuesOnConditionCheckFailure\":\"ALL_OLD\"}}]}"))
                .get("CancellationReasons").get(0).get("Item")));
    }

    @Test
    void getItemQueryAndPartiQlSelectKeepOneOrder() {
        putItem();
        assertStable(orders(() -> parse(call("GetItem", "{\"TableName\":\"" + TABLE
                + "\",\"Key\":{\"pk\":{\"S\":\"k\"}}}")).get("Item")));
        assertStable(orders(() -> parse(call("Query", "{\"TableName\":\"" + TABLE
                + "\",\"KeyConditionExpression\":\"pk = :p\",\"ExpressionAttributeValues\":{\":p\":{\"S\":\"k\"}}}"))
                .get("Items").get(0)));
        assertStable(orders(() -> parse(call("Scan", "{\"TableName\":\"" + TABLE + "\"}")).get("Items").get(0)));
        assertStable(orders(() -> parse(call("BatchGetItem", "{\"RequestItems\":{\"" + TABLE
                + "\":{\"Keys\":[{\"pk\":{\"S\":\"k\"}}]}}}")).get("Responses").get(TABLE).get(0)));
        assertStable(orders(() -> parse(call("ExecuteStatement", "{\"Statement\":\"SELECT * FROM " + TABLE
                + " WHERE pk = 'k'\"}")).get("Items").get(0)));
    }
}
