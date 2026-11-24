package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.ITEM;
import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.MAP;
import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.call;
import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.createTable;
import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.deleteTable;
import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DynamoDbAttributeOrderIntegrationTest {

    private static final int CALLS = 30;

    private String table;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createTestTable() {
        table = createTable("attribute-order");
    }

    @AfterEach
    void deleteTestTable() {
        deleteTable(table);
    }

    private void putItem() {
        call("PutItem", "{\"TableName\":\"" + table + "\",\"Item\":" + ITEM + "}");
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
        assertShuffled(orders(() -> call("PutItem", "{\"TableName\":\"" + table + "\",\"Item\":" + ITEM
                + ",\"ReturnValues\":\"ALL_OLD\"}").get("Attributes")));
    }

    @Test
    void updateItemReturnValuesShuffle() {
        putItem();
        for (String mode : List.of("ALL_OLD", "ALL_NEW")) {
            assertShuffled(orders(() -> call("UpdateItem", "{\"TableName\":\"" + table
                    + "\",\"Key\":{\"pk\":{\"S\":\"k\"}},\"UpdateExpression\":\"SET a1 = :v\","
                    + "\"ExpressionAttributeValues\":{\":v\":{\"S\":\"1\"}},\"ReturnValues\":\"" + mode + "\"}")
                    .get("Attributes")));
        }
    }

    @Test
    void updateItemUpdatedValuesShuffleNestedMap() {
        putItem();
        for (String mode : List.of("UPDATED_OLD", "UPDATED_NEW")) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < CALLS; i++) {
                JsonNode attributes = call("UpdateItem", "{\"TableName\":\"" + table
                        + "\",\"Key\":{\"pk\":{\"S\":\"k\"}},\"UpdateExpression\":\"SET m = :v\","
                        + "\"ExpressionAttributeValues\":{\":v\":" + MAP
                        + "},\"ReturnValues\":\"" + mode + "\"}").get("Attributes");
                seen.add(order(attributes.get("m").get("M")));
            }
            assertTrue(seen.size() > 1, mode + " saw " + seen);
        }
    }

    @Test
    void deleteItemAllOldShuffles() {
        assertShuffled(orders(() -> {
            putItem();
            return call("DeleteItem", "{\"TableName\":\"" + table
                    + "\",\"Key\":{\"pk\":{\"S\":\"k\"}},\"ReturnValues\":\"ALL_OLD\"}").get("Attributes");
        }));
    }

    @Test
    void failedConditionItemShuffles() {
        putItem();
        String conditionFails = ",\"ConditionExpression\":\"attribute_not_exists(pk)\","
                + "\"ReturnValuesOnConditionCheckFailure\":\"ALL_OLD\"}";
        assertShuffled(orders(() -> call("PutItem", "{\"TableName\":\"" + table + "\",\"Item\":" + ITEM
                + conditionFails).get("Item")));
        assertShuffled(orders(() -> call("UpdateItem", "{\"TableName\":\"" + table
                + "\",\"Key\":{\"pk\":{\"S\":\"k\"}},\"UpdateExpression\":\"SET a1 = :v\","
                + "\"ExpressionAttributeValues\":{\":v\":{\"S\":\"1\"}}" + conditionFails).get("Item")));
        assertShuffled(orders(() -> call("DeleteItem", "{\"TableName\":\"" + table
                + "\",\"Key\":{\"pk\":{\"S\":\"k\"}}" + conditionFails).get("Item")));
    }

    @Test
    void transactGetItemsShuffles() {
        putItem();
        assertShuffled(orders(() -> call("TransactGetItems", "{\"TransactItems\":[{\"Get\":{\"TableName\":\""
                + table + "\",\"Key\":{\"pk\":{\"S\":\"k\"}}}}]}").get("Responses").get(0).get("Item")));
        assertShuffled(orders(() -> call("TransactGetItems", "{\"TransactItems\":[{\"Get\":{\"TableName\":\""
                + table + "\",\"Key\":{\"pk\":{\"S\":\"k\"}},\"ProjectionExpression\":\"pk, a1, m\"}}]}")
                .get("Responses").get(0).get("Item")));
    }

    @Test
    void transactWriteCancellationItemShuffles() {
        putItem();
        assertShuffled(orders(() -> call("TransactWriteItems", "{\"TransactItems\":[{\"ConditionCheck\":"
                + "{\"TableName\":\"" + table + "\",\"Key\":{\"pk\":{\"S\":\"k\"}},"
                + "\"ConditionExpression\":\"attribute_not_exists(pk)\","
                + "\"ReturnValuesOnConditionCheckFailure\":\"ALL_OLD\"}}]}")
                .get("CancellationReasons").get(0).get("Item")));
    }

    @Test
    void getItemQueryAndPartiQlSelectKeepOneOrder() {
        putItem();
        assertStable(orders(() -> call("GetItem", "{\"TableName\":\"" + table
                + "\",\"Key\":{\"pk\":{\"S\":\"k\"}}}").get("Item")));
        assertStable(orders(() -> call("Query", "{\"TableName\":\"" + table
                + "\",\"KeyConditionExpression\":\"pk = :p\",\"ExpressionAttributeValues\":{\":p\":{\"S\":\"k\"}}}")
                .get("Items").get(0)));
        assertStable(orders(() -> call("Scan", "{\"TableName\":\"" + table + "\"}").get("Items").get(0)));
        assertStable(orders(() -> call("BatchGetItem", "{\"RequestItems\":{\"" + table
                + "\":{\"Keys\":[{\"pk\":{\"S\":\"k\"}}]}}}").get("Responses").get(table).get(0)));
        assertStable(orders(() -> call("ExecuteStatement", "{\"Statement\":\"SELECT * FROM \\\"" + table
                + "\\\" WHERE pk = 'k'\"}").get("Items").get(0)));
    }
}
