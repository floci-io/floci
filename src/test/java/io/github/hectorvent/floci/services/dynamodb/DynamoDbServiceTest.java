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

import java.util.Arrays;
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

    private ObjectNode attributeValue(String type, String value) {
        ObjectNode attrValue = mapper.createObjectNode();
        attrValue.put(type, value);
        return attrValue;
    }

    private ObjectNode item(String... kvPairs) {
        ObjectNode node = mapper.createObjectNode();
        for (int i = 0; i < kvPairs.length; i += 2) {
            node.set(kvPairs[i], attributeValue("S", kvPairs[i + 1]));
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
    void queryWithBetweenOnSortKey() {
        createOrdersTable();
        service.putItem("Orders", item("customerId", "c1", "orderId", "2024-01-01"));
        service.putItem("Orders", item("customerId", "c1", "orderId", "2024-01-15"));
        service.putItem("Orders", item("customerId", "c1", "orderId", "2024-02-01"));

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":pk", attributeValue("S", "c1"));
        exprValues.set(":from", attributeValue("S", "2024-01-10"));
        exprValues.set(":to", attributeValue("S", "2024-01-31"));

        DynamoDbService.QueryResult results = service.query("Orders", null, exprValues,
                "customerId = :pk AND orderId BETWEEN :from AND :to", null, null);

        assertEquals(1, results.items().size());
        assertEquals("2024-01-15", results.items().getFirst().get("orderId").get("S").asText());
    }

    @Test
    void queryWithScanIndexForwardFalseReturnsDescendingOrder() {
        createOrdersTable();
        service.putItem("Orders", item("customerId", "c1", "orderId", "o1"));
        service.putItem("Orders", item("customerId", "c1", "orderId", "o2"));
        service.putItem("Orders", item("customerId", "c1", "orderId", "o3"));

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":pk", attributeValue("S", "c1"));

        DynamoDbService.QueryResult results = service.query("Orders", null, exprValues,
                "customerId = :pk", null, null, false, null, null, null, "us-east-1");

        assertEquals(List.of("o3", "o2", "o1"), results.items().stream()
                .map(result -> result.get("orderId").get("S").asText())
                .toList());
    }

    @Test
    void queryAppliesFilterExpressionAfterKeyCondition() {
        createOrdersTable();

        ObjectNode first = item("customerId", "c1", "orderId", "o1");
        first.set("total", attributeValue("N", "100"));
        service.putItem("Orders", first);

        ObjectNode second = item("customerId", "c1", "orderId", "o2");
        second.set("total", attributeValue("N", "100"));
        service.putItem("Orders", second);

        ObjectNode third = item("customerId", "c1", "orderId", "o3");
        third.set("total", attributeValue("N", "99"));
        service.putItem("Orders", third);

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":pk", attributeValue("S", "c1"));
        exprValues.set(":min", attributeValue("N", "100"));

        DynamoDbService.QueryResult results = service.query("Orders", null, exprValues,
                "customerId = :pk", "total >= :min", null);

        assertEquals(2, results.items().size());
        assertEquals(3, results.scannedCount());
        assertEquals(List.of("o1", "o2"), results.items().stream()
                .map(result -> result.get("orderId").get("S").asText())
                .toList());
    }

    @Test
    void queryWithFilterExpressionAndLimitUsesPreFilterPageState() {
        createOrdersTable();

        ObjectNode first = item("customerId", "c1", "orderId", "o1");
        first.set("total", attributeValue("N", "100"));
        service.putItem("Orders", first);

        ObjectNode second = item("customerId", "c1", "orderId", "o2");
        second.set("total", attributeValue("N", "99"));
        service.putItem("Orders", second);

        ObjectNode third = item("customerId", "c1", "orderId", "o3");
        third.set("total", attributeValue("N", "100"));
        service.putItem("Orders", third);

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":pk", attributeValue("S", "c1"));
        exprValues.set(":min", attributeValue("N", "100"));

        DynamoDbService.QueryResult firstPage = service.query("Orders", null, exprValues,
                "customerId = :pk", "total >= :min", 2, null, null, null, null, "us-east-1");

        assertEquals(1, firstPage.items().size());
        assertEquals("o1", firstPage.items().get(0).get("orderId").get("S").asText());
        assertEquals(2, firstPage.scannedCount());
        assertNotNull(firstPage.lastEvaluatedKey());
        assertEquals("o2", firstPage.lastEvaluatedKey().get("orderId").get("S").asText());

        DynamoDbService.QueryResult secondPage = service.query("Orders", null, exprValues,
                "customerId = :pk", "total >= :min", 2, null, null,
                firstPage.lastEvaluatedKey(), null, "us-east-1");

        assertEquals(1, secondPage.items().size());
        assertEquals("o3", secondPage.items().get(0).get("orderId").get("S").asText());
        assertEquals(1, secondPage.scannedCount());
        assertNull(secondPage.lastEvaluatedKey());
    }

    @Test
    void scan() {
        createUsersTable();
        service.putItem("Users", item("userId", "u1", "name", "Alice"));
        service.putItem("Users", item("userId", "u2", "name", "Bob"));
        service.putItem("Users", item("userId", "u3", "name", "Charlie"));

        DynamoDbService.ScanResult result = service.scan("Users", null, null, null, null, null, null);
        assertEquals(3, result.items().size());
    }

    @Test
    void scanWithScanFilter() {
        createUsersTable();
        service.putItem("Users", item("userId", "u1", "name", "Alice"));
        service.putItem("Users", item("userId", "u2", "name", "Bob"));
        service.putItem("Users", item("userId", "u3", "name", "Charlie"));

        ObjectNode scanFilter = mapper.createObjectNode();
        ObjectNode condition = mapper.createObjectNode();
        condition.put("ComparisonOperator", "EQ");
        var attrList = mapper.createArrayNode();
        ObjectNode val = mapper.createObjectNode();
        val.put("S", "Alice");
        attrList.add(val);
        condition.set("AttributeValueList", attrList);
        scanFilter.set("name", condition);

        DynamoDbService.ScanResult result = service.scan("Users", null, null, null, scanFilter, null, null);
        assertEquals(1, result.items().size());
        assertEquals("Alice", result.items().get(0).get("name").get("S").asText());
    }

    @Test
    void scanWithScanFilterGE() {
        createUsersTable();
        service.putItem("Users", item("userId", "u1", "name", "Alice"));
        service.putItem("Users", item("userId", "u2", "name", "Bob"));
        service.putItem("Users", item("userId", "u3", "name", "Charlie"));

        ObjectNode scanFilter = mapper.createObjectNode();
        ObjectNode condition = mapper.createObjectNode();
        condition.put("ComparisonOperator", "GE");
        var attrList = mapper.createArrayNode();
        ObjectNode val = mapper.createObjectNode();
        val.put("S", "Bob");
        attrList.add(val);
        condition.set("AttributeValueList", attrList);
        scanFilter.set("name", condition);

        DynamoDbService.ScanResult result = service.scan("Users", null, null, null, scanFilter, null, null);
        assertEquals(2, result.items().size());
    }

    @Test
    void scanWithLimit() {
        createUsersTable();
        service.putItem("Users", item("userId", "u1"));
        service.putItem("Users", item("userId", "u2"));
        service.putItem("Users", item("userId", "u3"));

        DynamoDbService.ScanResult result = service.scan("Users", null, null, null, null, 2, null);
        assertEquals(2, result.items().size());
    }

    @Test
    void operationsOnNonExistentTableThrow() {
        assertThrows(AwsException.class, () -> service.putItem("NoTable", item("id", "1")));
        assertThrows(AwsException.class, () -> service.getItem("NoTable", item("id", "1")));
        assertThrows(AwsException.class, () -> service.deleteItem("NoTable", item("id", "1")));
        assertThrows(AwsException.class, () -> service.query("NoTable", null, null, null, null, null));
        assertThrows(AwsException.class, () -> service.scan("NoTable", null, null, null, null, null, null));
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

    @Test
    void updateItemSetIfNotExistsCopiesSourceAttributeWhenAttrNameDiffersFromCheckAttr() {
        // SET a = if_not_exists(b, :v) where b exists → a must be set to b's current value
        createUsersTable();

        // Put an item that has "source" but not "target"
        ObjectNode existing = mapper.createObjectNode();
        ObjectNode userIdVal = mapper.createObjectNode(); userIdVal.put("S", "u-copy");
        ObjectNode sourceVal = mapper.createObjectNode(); sourceVal.put("S", "copied-value");
        existing.set("userId", userIdVal);
        existing.set("source", sourceVal);
        service.putItem("Users", existing);

        ObjectNode key = item("userId", "u-copy");

        ObjectNode exprValues = mapper.createObjectNode();
        ObjectNode fallbackVal = mapper.createObjectNode(); fallbackVal.put("S", "fallback");
        exprValues.set(":v", fallbackVal);

        // target = if_not_exists(source, :v) — source exists, so target should receive source's value
        service.updateItem("Users", key, null,
                "SET target = if_not_exists(source, :v)",
                null, exprValues, null);

        JsonNode stored = service.getItem("Users", key);
        assertNotNull(stored);
        assertTrue(stored.has("target"), "target attribute must be present");
        assertEquals("copied-value", stored.get("target").get("S").asText(),
                "target should receive source's value when source exists");
    }

    @Test
    void updateItemSetIfNotExistsUsesFallbackWhenCheckAttrAbsentAndAttrNameDiffers() {
        // SET a = if_not_exists(b, :v) where b is absent → a must be set to :v
        createUsersTable();

        // Item has no "source" attribute
        service.putItem("Users", item("userId", "u-fallback"));

        ObjectNode key = item("userId", "u-fallback");

        ObjectNode exprValues = mapper.createObjectNode();
        ObjectNode fallbackVal = mapper.createObjectNode(); fallbackVal.put("S", "fallback");
        exprValues.set(":v", fallbackVal);

        service.updateItem("Users", key, null,
                "SET target = if_not_exists(source, :v)",
                null, exprValues, null);

        JsonNode stored = service.getItem("Users", key);
        assertNotNull(stored);
        assertTrue(stored.has("target"), "target attribute must be present");
        assertEquals("fallback", stored.get("target").get("S").asText(),
                "target should receive the fallback value when source is absent");
    }

    @Test
    void scanWithBoolFilterExpression() {
        createUsersTable();
        ObjectNode u1 = item("userId", "u1");
        u1.set("deleted", boolAttributeValue(false));
        service.putItem("Users", u1);

        ObjectNode u2 = item("userId", "u2");
        u2.set("deleted", boolAttributeValue(true));
        service.putItem("Users", u2);

        ObjectNode u3 = item("userId", "u3");
        u3.set("deleted", boolAttributeValue(false));
        service.putItem("Users", u3);

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":d", boolAttributeValue(true));

        DynamoDbService.ScanResult result = service.scan("Users", "deleted <> :d", null, exprValues, null, null, null);
        assertEquals(2, result.items().size());
    }

    @Test
    void scanContainsOnListAttribute() {
        createUsersTable();
        ObjectNode u1 = item("userId", "u1");
        u1.set("tags", listAttributeValue("a", "b"));
        service.putItem("Users", u1);

        ObjectNode u2 = item("userId", "u2");
        u2.set("tags", listAttributeValue("a", "c"));
        service.putItem("Users", u2);

        ObjectNode u3 = item("userId", "u3");
        u3.set("tags", listAttributeValue("b", "c"));
        service.putItem("Users", u3);

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":v", attributeValue("S", "a"));

        DynamoDbService.ScanResult result = service.scan("Users", "contains(tags, :v)", null, exprValues, null, null, null);
        assertEquals(2, result.items().size());
    }

    @Test
    void scanContainsOnStringSetAttribute() {
        createUsersTable();
        ObjectNode u1 = item("userId", "u1");
        u1.set("roles", stringSetAttributeValue("admin", "user"));
        service.putItem("Users", u1);

        ObjectNode u2 = item("userId", "u2");
        u2.set("roles", stringSetAttributeValue("user"));
        service.putItem("Users", u2);

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":r", attributeValue("S", "admin"));

        DynamoDbService.ScanResult result = service.scan("Users", "contains(roles, :r)", null, exprValues, null, null, null);
        assertEquals(1, result.items().size());
    }

    @Test
    void scanAttributeExistsOnNestedMapPath() {
        createUsersTable();
        ObjectNode u1 = item("userId", "u1");
        u1.set("info", mapAttributeValue("name", "Alice"));
        service.putItem("Users", u1);

        ObjectNode u2 = item("userId", "u2");
        ObjectNode emptyMap = mapper.createObjectNode();
        ObjectNode mapWrapper = mapper.createObjectNode();
        mapWrapper.set("M", emptyMap);
        u2.set("info", mapWrapper);
        service.putItem("Users", u2);

        ObjectNode u3 = item("userId", "u3");
        u3.set("info", mapAttributeValue("name", "Bob"));
        service.putItem("Users", u3);

        ObjectNode exprNames = mapper.createObjectNode();
        exprNames.put("#n", "name");

        DynamoDbService.ScanResult result = service.scan("Users", "attribute_exists(info.#n)", exprNames, null, null, null, null);
        assertEquals(2, result.items().size());

        DynamoDbService.ScanResult result2 = service.scan("Users", "attribute_not_exists(info.#n)", exprNames, null, null, null, null);
        assertEquals(1, result2.items().size());
    }

    private ObjectNode boolAttributeValue(boolean value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("BOOL", value);
        return node;
    }

    private ObjectNode listAttributeValue(String... values) {
        ObjectNode node = mapper.createObjectNode();
        var arrayNode = mapper.createArrayNode();
        for (String v : values) {
            arrayNode.add(attributeValue("S", v));
        }
        node.set("L", arrayNode);
        return node;
    }

    private ObjectNode stringSetAttributeValue(String... values) {
        ObjectNode node = mapper.createObjectNode();
        var arrayNode = mapper.createArrayNode();
        for (String v : values) {
            arrayNode.add(v);
        }
        node.set("SS", arrayNode);
        return node;
    }

    private ObjectNode mapAttributeValue(String key, String value) {
        ObjectNode inner = mapper.createObjectNode();
        inner.set(key, attributeValue("S", value));
        ObjectNode node = mapper.createObjectNode();
        node.set("M", inner);
        return node;
    }

    private ObjectNode numberSetAttributeValue(String... values) {
        ObjectNode node = mapper.createObjectNode();
        var arrayNode = mapper.createArrayNode();
        for (String v : values) {
            arrayNode.add(v);
        }
        node.set("NS", arrayNode);
        return node;
    }

    private ObjectNode binarySetAttributeValue(String... base64Values) {
        ObjectNode node = mapper.createObjectNode();
        var arrayNode = mapper.createArrayNode();
        for (String v : base64Values) {
            arrayNode.add(v);
        }
        node.set("BS", arrayNode);
        return node;
    }

    @Test
    void scanContainsOnNumberSetWithNumericNormalization() {
        createUsersTable();
        ObjectNode u1 = item("userId", "u1");
        u1.set("scores", numberSetAttributeValue("1", "2", "3"));
        service.putItem("Users", u1);

        ObjectNode u2 = item("userId", "u2");
        u2.set("scores", numberSetAttributeValue("4", "5"));
        service.putItem("Users", u2);

        // Search for "1.0" — should match "1" via numeric comparison
        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":v", attributeValue("N", "1.0"));

        DynamoDbService.ScanResult result = service.scan("Users", "contains(scores, :v)", null, exprValues, null, null, null);
        assertEquals(1, result.items().size(), "contains() on NS should match 1.0 == 1 numerically");
    }

    @Test
    void scanContainsOnBinarySet() {
        createUsersTable();
        ObjectNode u1 = item("userId", "u1");
        u1.set("bins", binarySetAttributeValue("AQID", "BAUG"));  // base64 for [1,2,3] and [4,5,6]
        service.putItem("Users", u1);

        ObjectNode u2 = item("userId", "u2");
        u2.set("bins", binarySetAttributeValue("BwgJ"));
        service.putItem("Users", u2);

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":v", attributeValue("B", "AQID"));

        DynamoDbService.ScanResult result = service.scan("Users", "contains(bins, :v)", null, exprValues, null, null, null);
        assertEquals(1, result.items().size());
    }

    @Test
    void scanContainsOnListWithNumericElements() {
        createUsersTable();
        ObjectNode u1 = item("userId", "u1");
        var list = mapper.createArrayNode();
        list.add(attributeValue("N", "10"));
        list.add(attributeValue("N", "20"));
        ObjectNode listNode = mapper.createObjectNode();
        listNode.set("L", list);
        u1.set("values", listNode);
        service.putItem("Users", u1);

        ObjectNode u2 = item("userId", "u2");
        var list2 = mapper.createArrayNode();
        list2.add(attributeValue("N", "30"));
        ObjectNode listNode2 = mapper.createObjectNode();
        listNode2.set("L", list2);
        u2.set("values", listNode2);
        service.putItem("Users", u2);

        // Search for N:10.0 — should match N:10 via type-aware comparison
        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":v", attributeValue("N", "10.0"));

        DynamoDbService.ScanResult result = service.scan("Users", "contains(values, :v)", null, exprValues, null, null, null);
        assertEquals(1, result.items().size(), "contains() on List with N elements should use type-aware numeric comparison");
    }

    @Test
    void updateItemSetAddsToStringSet() {
        createOrdersTable();

        ObjectNode key = item("customerId", "1", "orderId", "sort1");

        ObjectNode exprValues = mapper.createObjectNode();
        ObjectNode priceVal = mapper.createObjectNode();
        priceVal.put("N", "100");

        // Use SS (String Set) type for ADD operation
        ObjectNode tagVal = mapper.createObjectNode();
        var tagArray = tagVal.putArray("SS");
        tagArray.add("a");
        exprValues.set(":val", priceVal);
        exprValues.set(":newTag", tagVal);

        service.updateItem("Orders", key, null,
                "SET price = if_not_exists(price, :val) ADD tags :newTag",
                null, exprValues, null);

        // And add another tag to the same item, to verify that the ADD works on existing items as well
        ObjectNode tagVal2 = mapper.createObjectNode();
        var tagArray2 = tagVal2.putArray("SS");
        tagArray2.add("b");
        exprValues.set(":newTag", tagVal2);
        DynamoDbService.UpdateResult updateResult = service.updateItem("Orders", key, null,
                "SET price = if_not_exists(price, :val) ADD tags :newTag",
                null, exprValues, null);

        JsonNode stored = service.getItem("Orders", key);
        assertNotNull(stored, "item should have been created");
        assertTrue(stored.has("tags"), "tags attribute must be present on item after ADD");

        // Verify tags is a String Set (SS) with both values
        JsonNode tagsNode = stored.get("tags");
        assertTrue(tagsNode.has("SS"), "tags should be of type SS (String Set)");
        JsonNode ssArray = tagsNode.get("SS");
        assertEquals(2, ssArray.size(), "tags should have 2 elements");

        // Verify values from the SS array
        java.util.Set<String> tagValues = new java.util.HashSet<>();
        ssArray.forEach(node -> tagValues.add(node.asText()));
        assertEquals(2, tagValues.size());
        assertTrue(tagValues.containsAll(Arrays.asList("a", "b")));
    }

}
