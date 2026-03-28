package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DynamoDbServiceTest {

    private DynamoDbService service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        service = new DynamoDbService(new InMemoryStorage<>());
        mapper = new ObjectMapper();
    }

    private TableDefinition createUsersTable() {
        return service.createTable("Users",
                List.of(new KeySchemaElement("userId", "HASH")),
                List.of(new AttributeDefinition("userId", "S")),
                5L, 5L);
    }

    private TableDefinition createOrdersTable() {
        return service.createTable("Orders",
                List.of(
                        new KeySchemaElement("customerId", "HASH"),
                        new KeySchemaElement("orderId", "RANGE")),
                List.of(
                        new AttributeDefinition("customerId", "S"),
                        new AttributeDefinition("orderId", "S")),
                5L, 5L);
    }

    private ObjectNode item(String... kvPairs) {
        ObjectNode node = mapper.createObjectNode();
        for (int i = 0; i < kvPairs.length; i += 2) {
            ObjectNode attrValue = mapper.createObjectNode();
            attrValue.put("S", kvPairs[i + 1]);
            node.set(kvPairs[i], attrValue);
        }
        return node;
    }

    @Test
    void createTable() {
        TableDefinition table = createUsersTable();
        assertEquals("Users", table.getTableName());
        assertEquals("ACTIVE", table.getTableStatus());
        assertNotNull(table.getTableArn());
        assertEquals("userId", table.getPartitionKeyName());
        assertNull(table.getSortKeyName());
    }

    @Test
    void createTableWithSortKey() {
        TableDefinition table = createOrdersTable();
        assertEquals("customerId", table.getPartitionKeyName());
        assertEquals("orderId", table.getSortKeyName());
    }

    @Test
    void createDuplicateTableThrows() {
        createUsersTable();
        assertThrows(AwsException.class, () -> createUsersTable());
    }

    @Test
    void describeTable() {
        createUsersTable();
        TableDefinition table = service.describeTable("Users");
        assertEquals("Users", table.getTableName());
    }

    @Test
    void describeTableNotFound() {
        assertThrows(AwsException.class, () -> service.describeTable("NonExistent"));
    }

    @Test
    void deleteTable() {
        createUsersTable();
        service.deleteTable("Users");
        assertThrows(AwsException.class, () -> service.describeTable("Users"));
    }

    @Test
    void listTables() {
        createUsersTable();
        createOrdersTable();
        List<String> tables = service.listTables();
        assertEquals(2, tables.size());
        assertTrue(tables.contains("Users"));
        assertTrue(tables.contains("Orders"));
    }

    @Test
    void putAndGetItem() {
        createUsersTable();
        ObjectNode userItem = item("userId", "user-1", "name", "Alice", "email", "alice@test.com");
        service.putItem("Users", userItem);

        ObjectNode key = item("userId", "user-1");
        JsonNode retrieved = service.getItem("Users", key);
        assertNotNull(retrieved);
        assertEquals("Alice", retrieved.get("name").get("S").asText());
    }

    @Test
    void getItemNotFound() {
        createUsersTable();
        ObjectNode key = item("userId", "nonexistent");
        JsonNode result = service.getItem("Users", key);
        assertNull(result);
    }

    @Test
    void putItemOverwrites() {
        createUsersTable();
        service.putItem("Users", item("userId", "user-1", "name", "Alice"));
        service.putItem("Users", item("userId", "user-1", "name", "Bob"));

        JsonNode retrieved = service.getItem("Users", item("userId", "user-1"));
        assertEquals("Bob", retrieved.get("name").get("S").asText());
    }

    @Test
    void deleteItem() {
        createUsersTable();
        service.putItem("Users", item("userId", "user-1", "name", "Alice"));
        service.deleteItem("Users", item("userId", "user-1"));

        assertNull(service.getItem("Users", item("userId", "user-1")));
    }

    @Test
    void putAndGetWithCompositeKey() {
        createOrdersTable();
        service.putItem("Orders", item("customerId", "c1", "orderId", "o1", "total", "100"));
        service.putItem("Orders", item("customerId", "c1", "orderId", "o2", "total", "200"));
        service.putItem("Orders", item("customerId", "c2", "orderId", "o1", "total", "50"));

        JsonNode result = service.getItem("Orders", item("customerId", "c1", "orderId", "o1"));
        assertNotNull(result);
        assertEquals("100", result.get("total").get("S").asText());
    }

    @Test
    void queryByPartitionKey() {
        createOrdersTable();
        service.putItem("Orders", item("customerId", "c1", "orderId", "o1", "total", "100"));
        service.putItem("Orders", item("customerId", "c1", "orderId", "o2", "total", "200"));
        service.putItem("Orders", item("customerId", "c2", "orderId", "o1", "total", "50"));

        // Build KeyConditions
        ObjectNode keyConditions = mapper.createObjectNode();
        ObjectNode pkCondition = mapper.createObjectNode();
        pkCondition.put("ComparisonOperator", "EQ");
        var attrList = mapper.createArrayNode();
        ObjectNode pkVal = mapper.createObjectNode();
        pkVal.put("S", "c1");
        attrList.add(pkVal);
        pkCondition.set("AttributeValueList", attrList);
        keyConditions.set("customerId", pkCondition);

        DynamoDbService.QueryResult results = service.query("Orders", keyConditions, null, null, null, null);
        assertEquals(2, results.items().size());
    }

    @Test
    void queryWithKeyConditionExpression() {
        createOrdersTable();
        service.putItem("Orders", item("customerId", "c1", "orderId", "o1"));
        service.putItem("Orders", item("customerId", "c1", "orderId", "o2"));
        service.putItem("Orders", item("customerId", "c2", "orderId", "o1"));

        ObjectNode exprValues = mapper.createObjectNode();
        ObjectNode val = mapper.createObjectNode();
        val.put("S", "c1");
        exprValues.set(":pk", val);

        DynamoDbService.QueryResult results = service.query("Orders", null, exprValues,
                "customerId = :pk", null, null);
        assertEquals(2, results.items().size());
    }

    @Test
    void queryWithBeginsWith() {
        createOrdersTable();
        service.putItem("Orders", item("customerId", "c1", "orderId", "2024-01-01"));
        service.putItem("Orders", item("customerId", "c1", "orderId", "2024-01-15"));
        service.putItem("Orders", item("customerId", "c1", "orderId", "2024-02-01"));

        ObjectNode exprValues = mapper.createObjectNode();
        ObjectNode pkVal = mapper.createObjectNode();
        pkVal.put("S", "c1");
        exprValues.set(":pk", pkVal);
        ObjectNode skVal = mapper.createObjectNode();
        skVal.put("S", "2024-01");
        exprValues.set(":sk", skVal);

        DynamoDbService.QueryResult results = service.query("Orders", null, exprValues,
                "customerId = :pk AND begins_with(orderId, :sk)", null, null);
        assertEquals(2, results.items().size());
    }

    @Test
    void scan() {
        createUsersTable();
        service.putItem("Users", item("userId", "u1", "name", "Alice"));
        service.putItem("Users", item("userId", "u2", "name", "Bob"));
        service.putItem("Users", item("userId", "u3", "name", "Charlie"));

        DynamoDbService.ScanResult result = service.scan("Users", null, null, null, null, null);
        assertEquals(3, result.items().size());
    }

    @Test
    void scanWithLimit() {
        createUsersTable();
        service.putItem("Users", item("userId", "u1"));
        service.putItem("Users", item("userId", "u2"));
        service.putItem("Users", item("userId", "u3"));

        DynamoDbService.ScanResult result = service.scan("Users", null, null, null, 2, null);
        assertEquals(2, result.items().size());
    }

    @Test
    void operationsOnNonExistentTableThrow() {
        assertThrows(AwsException.class, () -> service.putItem("NoTable", item("id", "1")));
        assertThrows(AwsException.class, () -> service.getItem("NoTable", item("id", "1")));
        assertThrows(AwsException.class, () -> service.deleteItem("NoTable", item("id", "1")));
        assertThrows(AwsException.class, () -> service.query("NoTable", null, null, null, null, null));
        assertThrows(AwsException.class, () -> service.scan("NoTable", null, null, null, null, null));
    }

    @Test
    void updateItemSetIfNotExistsOnNonExistentItemCreatesAttribute() {
        createOrdersTable();

        ObjectNode key = item("customerId", "1", "orderId", "sort1");

        ObjectNode exprValues = mapper.createObjectNode();
        ObjectNode priceVal = mapper.createObjectNode();
        priceVal.put("N", "100");
        exprValues.set(":val", priceVal);

        service.updateItem("Orders", key, null,
                "SET price = if_not_exists(price, :val)",
                null, exprValues, null);

        JsonNode stored = service.getItem("Orders", key);
        assertNotNull(stored, "item should have been created");
        assertTrue(stored.has("price"), "price attribute must be present on a newly created item");
        assertEquals("100", stored.get("price").get("N").asText());
    }

    @Test
    void updateItemSetIfNotExistsPreservesExistingValue() {
        createOrdersTable();

        // Put an item that already has price = 200
        ObjectNode existing = mapper.createObjectNode();
        ObjectNode pkVal = mapper.createObjectNode(); pkVal.put("S", "1");
        ObjectNode skVal = mapper.createObjectNode(); skVal.put("S", "sort1");
        ObjectNode priceExisting = mapper.createObjectNode(); priceExisting.put("N", "200");
        existing.set("customerId", pkVal);
        existing.set("orderId", skVal);
        existing.set("price", priceExisting);
        service.putItem("Orders", existing);

        ObjectNode key = item("customerId", "1", "orderId", "sort1");

        ObjectNode exprValues = mapper.createObjectNode();
        ObjectNode fallback = mapper.createObjectNode(); fallback.put("N", "100");
        exprValues.set(":val", fallback);

        service.updateItem("Orders", key, null,
                "SET price = if_not_exists(price, :val)",
                null, exprValues, null);

        JsonNode stored = service.getItem("Orders", key);
        assertNotNull(stored);
        // Existing value must NOT be overwritten
        assertEquals("200", stored.get("price").get("N").asText(),
                "if_not_exists should preserve the existing value");
    }

    @Test
    void updateItemSetIfNotExistsSetsAttributeWhenMissingFromExistingItem() {
        createOrdersTable();

        // Put an item that does NOT have a price attribute
        service.putItem("Orders", item("customerId", "1", "orderId", "sort1"));

        ObjectNode key = item("customerId", "1", "orderId", "sort1");

        ObjectNode exprValues = mapper.createObjectNode();
        ObjectNode fallback = mapper.createObjectNode(); fallback.put("N", "99");
        exprValues.set(":val", fallback);

        service.updateItem("Orders", key, null,
                "SET price = if_not_exists(price, :val)",
                null, exprValues, null);

        JsonNode stored = service.getItem("Orders", key);
        assertNotNull(stored);
        assertTrue(stored.has("price"),
                "price should be set when it was absent from an existing item");
        assertEquals("99", stored.get("price").get("N").asText());
    }

    @Test
    void updateItemSetIfNotExistsMultipleAttributesOnNewItem() {
        createUsersTable();

        ObjectNode key = item("userId", "u-new");

        ObjectNode exprValues = mapper.createObjectNode();
        ObjectNode nameVal = mapper.createObjectNode(); nameVal.put("S", "DefaultName");
        ObjectNode scoreVal = mapper.createObjectNode(); scoreVal.put("N", "0");
        exprValues.set(":name", nameVal);
        exprValues.set(":score", scoreVal);

        service.updateItem("Users", key, null,
                "SET name = if_not_exists(name, :name), score = if_not_exists(score, :score)",
                null, exprValues, null);

        JsonNode stored = service.getItem("Users", key);
        assertNotNull(stored, "item should have been created");
        assertTrue(stored.has("name"), "name attribute must be present");
        assertEquals("DefaultName", stored.get("name").get("S").asText());
        assertTrue(stored.has("score"), "score attribute must be present");
        assertEquals("0", stored.get("score").get("N").asText());
    }
}
