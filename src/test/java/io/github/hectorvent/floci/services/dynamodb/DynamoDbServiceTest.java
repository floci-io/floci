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
    void listAppendIfNotExistsCreatesListWhenAttributeMissing() {
        createUsersTable();

        ObjectNode key = item("userId", "u-list-new");

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":e", listAttributeValue());
        exprValues.set(":val", listAttributeValue("a"));

        service.updateItem("Users", key, null,
                "SET items = list_append(if_not_exists(items, :e), :val)",
                null, exprValues, null);

        JsonNode stored = service.getItem("Users", key);
        assertNotNull(stored);
        assertTrue(stored.has("items"), "items attribute must be created");
        assertEquals(1, stored.get("items").get("L").size());
        assertEquals("a", stored.get("items").get("L").get(0).get("S").asText());
    }

    @Test
    void listAppendIfNotExistsAppendsWhenAttributePresent() {
        createUsersTable();

        ObjectNode existing = item("userId", "u-list-existing");
        existing.set("items", listAttributeValue("a"));
        service.putItem("Users", existing);

        ObjectNode key = item("userId", "u-list-existing");
        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":e", listAttributeValue());
        exprValues.set(":val", listAttributeValue("b"));

        service.updateItem("Users", key, null,
                "SET items = list_append(if_not_exists(items, :e), :val)",
                null, exprValues, null);

        JsonNode stored = service.getItem("Users", key);
        assertNotNull(stored);
        assertEquals(2, stored.get("items").get("L").size());
        assertEquals("a", stored.get("items").get("L").get(0).get("S").asText());
        assertEquals("b", stored.get("items").get("L").get(1).get("S").asText());
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

    /**
     * Test update with SET and REMOVE in the same expression.
     * This mimics how the DynamoDB Enhanced Client generates expressions
     * when ignoreNulls is false - it sets non-null fields and removes null fields.
     *
     * Using Spring Boot 4.0.5, AWS SDK v2 2.42.24, setting a boolean field to true after it was not created at row-creation time, would not set the value to true.
     */
    @Test
    void testUpdateWithSetAndRemoveCombined() {
        createUsersTable();

        // Put initial item WITHOUT the boolean field
        ObjectNode initialItem = mapper.createObjectNode();
        initialItem.set("userId", attributeValue("S", "user-123"));
        initialItem.set("created", attributeValue("N", "1234567890"));
        initialItem.set("entries", attributeValue("S", "initial"));
        initialItem.set("tempField", attributeValue("S", "to be removed"));
        service.putItem("Users", initialItem);

        // Verify initial state - isActive doesn't exist
        ObjectNode key = mapper.createObjectNode();
        key.set("userId", attributeValue("S", "user-123"));
        JsonNode beforeUpdate = service.getItem("Users", key);
        assertFalse(beforeUpdate.has("isActive"), "isActive should not exist initially");
        assertTrue(beforeUpdate.has("tempField"), "tempField should exist initially");

        // Update with SET and REMOVE - like Enhanced Client does
        ObjectNode exprAttrNames = mapper.createObjectNode();
        exprAttrNames.put("#entries", "entries");
        exprAttrNames.put("#isActive", "isActive");
        exprAttrNames.put("#tempField", "tempField");
        exprAttrNames.put("#created", "created");

        ObjectNode exprAttrValues = mapper.createObjectNode();
        exprAttrValues.set(":entries", attributeValue("S", "updated entries"));
        exprAttrValues.set(":isActive", boolAttributeValue(true));

        // This is the key expression: SET multiple fields, then REMOVE multiple fields
        String updateExpr = "SET #entries = :entries, #isActive = :isActive REMOVE #tempField, #created";

        DynamoDbService.UpdateResult result = service.updateItem("Users", key, null,
                updateExpr, exprAttrNames, exprAttrValues, "ALL_NEW");

        // Verify the result
        JsonNode newItem = result.newItem();
        assertNotNull(newItem, "result should have newItem");

        // Boolean should be set to true
        assertTrue(newItem.has("isActive"), "isActive should exist");
        assertTrue(newItem.get("isActive").has("BOOL"), "isActive should be BOOL type");
        assertTrue(newItem.get("isActive").get("BOOL").asBoolean(), "isActive should be true");

        // entries should be updated
        assertEquals("updated entries", newItem.get("entries").get("S").asText());

        // tempField and created should be removed
        assertFalse(newItem.has("tempField"), "tempField should be removed");
        assertFalse(newItem.has("created"), "created should be removed");

        // Get item to double-check persistence
        JsonNode stored = service.getItem("Users", key);
        assertTrue(stored.get("isActive").get("BOOL").asBoolean(),
                "isActive should still be true after get");
    }

    /**
     * Test REMOVE with nested map paths (e.g. "ratings.foo").
     * Reproduces GitHub issue #402: REMOVE on a map key succeeds but data is unchanged.
     */
    @Test
    void testRemoveNestedMapKey() {
        createUsersTable();

        // Put item with a map attribute containing two keys
        ObjectNode initialItem = mapper.createObjectNode();
        initialItem.set("userId", attributeValue("S", "user-1"));
        ObjectNode ratingsInner = mapper.createObjectNode();
        ratingsInner.set("foo", attributeValue("S", "5"));
        ratingsInner.set("bar", attributeValue("S", "3"));
        ObjectNode ratingsMap = mapper.createObjectNode();
        ratingsMap.set("M", ratingsInner);
        initialItem.set("ratings", ratingsMap);
        service.putItem("Users", initialItem);

        ObjectNode key = mapper.createObjectNode();
        key.set("userId", attributeValue("S", "user-1"));

        // Verify both keys exist
        JsonNode before = service.getItem("Users", key);
        assertTrue(before.get("ratings").get("M").has("foo"));
        assertTrue(before.get("ratings").get("M").has("bar"));

        // REMOVE ratings.foo
        DynamoDbService.UpdateResult result = service.updateItem("Users", key, null,
                "REMOVE ratings.foo", null, null, "ALL_NEW");

        JsonNode updated = result.newItem();
        assertFalse(updated.get("ratings").get("M").has("foo"),
                "foo should be removed from ratings map");
        assertTrue(updated.get("ratings").get("M").has("bar"),
                "bar should still exist in ratings map");
        assertEquals("3", updated.get("ratings").get("M").get("bar").get("S").asText());
    }

    /**
     * Test REMOVE with nested map paths using expression attribute names.
     */
    @Test
    void testRemoveNestedMapKeyWithExpressionNames() {
        createUsersTable();

        ObjectNode initialItem = mapper.createObjectNode();
        initialItem.set("userId", attributeValue("S", "user-2"));
        ObjectNode metaInner = mapper.createObjectNode();
        metaInner.set("temp", attributeValue("S", "value"));
        metaInner.set("keep", attributeValue("S", "important"));
        ObjectNode metaMap = mapper.createObjectNode();
        metaMap.set("M", metaInner);
        initialItem.set("metadata", metaMap);
        service.putItem("Users", initialItem);

        ObjectNode key = mapper.createObjectNode();
        key.set("userId", attributeValue("S", "user-2"));

        // REMOVE #meta.#tmp using expression attribute names
        ObjectNode exprAttrNames = mapper.createObjectNode();
        exprAttrNames.put("#meta", "metadata");
        exprAttrNames.put("#tmp", "temp");

        DynamoDbService.UpdateResult result = service.updateItem("Users", key, null,
                "REMOVE #meta.#tmp", exprAttrNames, null, "ALL_NEW");

        JsonNode updated = result.newItem();
        assertFalse(updated.get("metadata").get("M").has("temp"),
                "temp should be removed from metadata map");
        assertTrue(updated.get("metadata").get("M").has("keep"),
                "keep should still exist in metadata map");
    }

    /**
     * Test REMOVE on a non-existent nested path does not fail.
     */
    @Test
    void testRemoveNonExistentNestedPath() {
        createUsersTable();

        ObjectNode initialItem = mapper.createObjectNode();
        initialItem.set("userId", attributeValue("S", "user-3"));
        initialItem.set("name", attributeValue("S", "Alice"));
        service.putItem("Users", initialItem);

        ObjectNode key = mapper.createObjectNode();
        key.set("userId", attributeValue("S", "user-3"));

        // REMOVE on a path where the parent map doesn't exist - should not fail
        DynamoDbService.UpdateResult result = service.updateItem("Users", key, null,
                "REMOVE nonexistent.child", null, null, "ALL_NEW");

        JsonNode updated = result.newItem();
        assertEquals("Alice", updated.get("name").get("S").asText(),
                "existing attributes should be unchanged");
    }

    /**
     * Test REMOVE with deeply nested map paths (3 levels).
     */
    @Test
    void testRemoveDeeplyNestedMapKey() {
        createUsersTable();

        // Build: settings.notifications.email = "on", settings.notifications.sms = "off"
        ObjectNode initialItem = mapper.createObjectNode();
        initialItem.set("userId", attributeValue("S", "user-4"));

        ObjectNode notifInner = mapper.createObjectNode();
        notifInner.set("email", attributeValue("S", "on"));
        notifInner.set("sms", attributeValue("S", "off"));
        ObjectNode notifMap = mapper.createObjectNode();
        notifMap.set("M", notifInner);

        ObjectNode settingsInner = mapper.createObjectNode();
        settingsInner.set("notifications", notifMap);
        ObjectNode settingsMap = mapper.createObjectNode();
        settingsMap.set("M", settingsInner);

        initialItem.set("settings", settingsMap);
        service.putItem("Users", initialItem);

        ObjectNode key = mapper.createObjectNode();
        key.set("userId", attributeValue("S", "user-4"));

        // REMOVE settings.notifications.sms
        DynamoDbService.UpdateResult result = service.updateItem("Users", key, null,
                "REMOVE settings.notifications.sms", null, null, "ALL_NEW");

        JsonNode updated = result.newItem();
        JsonNode notifs = updated.get("settings").get("M").get("notifications").get("M");
        assertTrue(notifs.has("email"), "email should still exist");
        assertFalse(notifs.has("sms"), "sms should be removed");
    }

    // --- UpdateExpression clause separator tests ---
    //
    // The Go AWS SDK v2 expression.Builder joins top-level clauses with '\n',
    // emitting expressions like "SET #a = :a\nADD #b :b". Each of the cases
    // below hits a different edge of the clause-boundary / clause-advancement
    // logic. See GitHub issue #430 for the full repro.

    private void seedCounterItem(String id, long counterValue, String nameValue) {
        ObjectNode initialItem = mapper.createObjectNode();
        initialItem.set("userId", attributeValue("S", id));
        initialItem.set("counter", attributeValue("N", Long.toString(counterValue)));
        initialItem.set("name", attributeValue("S", nameValue));
        service.putItem("Users", initialItem);
    }

    private ObjectNode userIdKey(String id) {
        ObjectNode key = mapper.createObjectNode();
        key.set("userId", attributeValue("S", id));
        return key;
    }

    @Test
    void updateExpressionAcceptsNewlineBetweenSetAndAdd() {
        // "SET ... \n ADD ..." — previously both clauses were silently dropped:
        // applySetClause greedily consumed ":newName\nADD counter :inc" as the
        // value and failed the lookup, so neither SET nor ADD ran.
        createUsersTable();
        seedCounterItem("u1", 1L, "old");

        ObjectNode names = mapper.createObjectNode();
        names.put("#n", "name");
        names.put("#c", "counter");
        ObjectNode values = mapper.createObjectNode();
        values.set(":newName", attributeValue("S", "new"));
        values.set(":inc", attributeValue("N", "5"));

        service.updateItem("Users", userIdKey("u1"), null,
                "SET #n = :newName\nADD #c :inc", names, values, "ALL_NEW");

        JsonNode stored = service.getItem("Users", userIdKey("u1"));
        assertEquals("new", stored.get("name").get("S").asText(),
                "SET clause must apply across a newline boundary");
        assertEquals("6", stored.get("counter").get("N").asText(),
                "ADD clause must apply across a newline boundary");
    }

    @Test
    void updateExpressionAcceptsNewlineBetweenAddAndSet() {
        createUsersTable();
        seedCounterItem("u2", 10L, "old");

        ObjectNode names = mapper.createObjectNode();
        names.put("#n", "name");
        names.put("#c", "counter");
        ObjectNode values = mapper.createObjectNode();
        values.set(":newName", attributeValue("S", "new"));
        values.set(":inc", attributeValue("N", "3"));

        service.updateItem("Users", userIdKey("u2"), null,
                "ADD #c :inc\nSET #n = :newName", names, values, "ALL_NEW");

        JsonNode stored = service.getItem("Users", userIdKey("u2"));
        assertEquals("new", stored.get("name").get("S").asText());
        assertEquals("13", stored.get("counter").get("N").asText());
    }

    @Test
    void updateExpressionAcceptsTabBetweenClauses() {
        createUsersTable();
        seedCounterItem("u3", 0L, "old");

        ObjectNode names = mapper.createObjectNode();
        names.put("#n", "name");
        names.put("#c", "counter");
        ObjectNode values = mapper.createObjectNode();
        values.set(":newName", attributeValue("S", "new"));
        values.set(":inc", attributeValue("N", "1"));

        service.updateItem("Users", userIdKey("u3"), null,
                "SET #n = :newName\tADD #c :inc", names, values, "ALL_NEW");

        JsonNode stored = service.getItem("Users", userIdKey("u3"));
        assertEquals("new", stored.get("name").get("S").asText());
        assertEquals("1", stored.get("counter").get("N").asText());
    }

    @Test
    void updateExpressionAcceptsCrlfBetweenClauses() {
        createUsersTable();
        seedCounterItem("u4", 100L, "old");

        ObjectNode names = mapper.createObjectNode();
        names.put("#n", "name");
        names.put("#c", "counter");
        ObjectNode values = mapper.createObjectNode();
        values.set(":newName", attributeValue("S", "new"));
        values.set(":inc", attributeValue("N", "7"));

        service.updateItem("Users", userIdKey("u4"), null,
                "SET #n = :newName\r\nADD #c :inc", names, values, "ALL_NEW");

        JsonNode stored = service.getItem("Users", userIdKey("u4"));
        assertEquals("new", stored.get("name").get("S").asText());
        assertEquals("107", stored.get("counter").get("N").asText());
    }

    @Test
    void updateExpressionAcceptsThreeNewlineSeparatedClauses() {
        // Canonical Go SDK shape: SET + ADD + DELETE joined by '\n'.
        createUsersTable();

        ObjectNode initialItem = mapper.createObjectNode();
        initialItem.set("userId", attributeValue("S", "u5"));
        initialItem.set("counter", attributeValue("N", "2"));
        ObjectNode ss = mapper.createObjectNode();
        ss.putArray("SS").add("keep").add("drop");
        initialItem.set("tagsToClear", ss);
        service.putItem("Users", initialItem);

        ObjectNode names = mapper.createObjectNode();
        names.put("#a", "alpha");
        names.put("#b", "beta");
        names.put("#c", "counter");
        names.put("#d", "tagsToClear");
        ObjectNode values = mapper.createObjectNode();
        values.set(":a", attributeValue("S", "A"));
        values.set(":b", attributeValue("S", "B"));
        values.set(":inc", attributeValue("N", "4"));
        ObjectNode dropSet = mapper.createObjectNode();
        dropSet.putArray("SS").add("drop");
        values.set(":d", dropSet);

        service.updateItem("Users", userIdKey("u5"), null,
                "SET #a = :a, #b = :b\nADD #c :inc\nDELETE #d :d",
                names, values, "ALL_NEW");

        JsonNode stored = service.getItem("Users", userIdKey("u5"));
        assertEquals("A", stored.get("alpha").get("S").asText());
        assertEquals("B", stored.get("beta").get("S").asText());
        assertEquals("6", stored.get("counter").get("N").asText());
        assertTrue(stored.has("tagsToClear"), "tagsToClear should still exist");
        JsonNode remaining = stored.get("tagsToClear").get("SS");
        assertEquals(1, remaining.size());
        assertEquals("keep", remaining.get(0).asText());
    }

    @Test
    void updateExpressionAcceptsNewlineBetweenRemoveAndSet() {
        createUsersTable();

        ObjectNode initialItem = mapper.createObjectNode();
        initialItem.set("userId", attributeValue("S", "u6"));
        initialItem.set("tempField", attributeValue("S", "bye"));
        initialItem.set("name", attributeValue("S", "old"));
        service.putItem("Users", initialItem);

        ObjectNode names = mapper.createObjectNode();
        names.put("#t", "tempField");
        names.put("#n", "name");
        ObjectNode values = mapper.createObjectNode();
        values.set(":newName", attributeValue("S", "new"));

        service.updateItem("Users", userIdKey("u6"), null,
                "REMOVE #t\nSET #n = :newName", names, values, "ALL_NEW");

        JsonNode stored = service.getItem("Users", userIdKey("u6"));
        assertFalse(stored.has("tempField"), "tempField should be removed");
        assertEquals("new", stored.get("name").get("S").asText());
    }

    @Test
    void updateExpressionAcceptsNewlineBetweenDeleteAndAdd() {
        createUsersTable();

        ObjectNode initialItem = mapper.createObjectNode();
        initialItem.set("userId", attributeValue("S", "u7"));
        initialItem.set("counter", attributeValue("N", "10"));
        ObjectNode ss = mapper.createObjectNode();
        ss.putArray("SS").add("keep").add("drop");
        initialItem.set("tags", ss);
        service.putItem("Users", initialItem);

        ObjectNode names = mapper.createObjectNode();
        names.put("#c", "counter");
        names.put("#tag", "tags");
        ObjectNode values = mapper.createObjectNode();
        values.set(":inc", attributeValue("N", "2"));
        ObjectNode dropSet = mapper.createObjectNode();
        dropSet.putArray("SS").add("drop");
        values.set(":d", dropSet);

        service.updateItem("Users", userIdKey("u7"), null,
                "DELETE #tag :d\nADD #c :inc", names, values, "ALL_NEW");

        JsonNode stored = service.getItem("Users", userIdKey("u7"));
        assertEquals("12", stored.get("counter").get("N").asText());
        JsonNode remaining = stored.get("tags").get("SS");
        assertEquals(1, remaining.size());
        assertEquals("keep", remaining.get(0).asText());
    }

    @Test
    void updateExpressionAddBeforeSetDoesNotSwallowSetKeywordAtIntraSetComma() {
        // Regression for Bug 2: before the advancement alignment fix,
        // applyAddClause preferred the next comma (inside the SET clause's
        // "b = :b, c = :c") over the SET keyword, consuming the keyword and
        // dropping the SET entirely.
        createUsersTable();
        seedCounterItem("u8", 0L, "old");

        ObjectNode names = mapper.createObjectNode();
        names.put("#c", "counter");
        names.put("#n", "name");
        ObjectNode values = mapper.createObjectNode();
        values.set(":inc", attributeValue("N", "1"));
        values.set(":newName", attributeValue("S", "new"));
        values.set(":other", attributeValue("S", "x"));

        service.updateItem("Users", userIdKey("u8"), null,
                "ADD #c :inc SET #n = :newName, extra = :other", names, values, "ALL_NEW");

        JsonNode stored = service.getItem("Users", userIdKey("u8"));
        assertEquals("1", stored.get("counter").get("N").asText(), "ADD must apply");
        assertEquals("new", stored.get("name").get("S").asText(), "SET must apply");
        assertEquals("x", stored.get("extra").get("S").asText(), "second SET assignment must apply");
    }

    @Test
    void updateExpressionRemoveBeforeSetDoesNotSwallowSetKeywordAtIntraSetComma() {
        createUsersTable();

        ObjectNode initialItem = mapper.createObjectNode();
        initialItem.set("userId", attributeValue("S", "u9"));
        initialItem.set("tempField", attributeValue("S", "bye"));
        initialItem.set("name", attributeValue("S", "old"));
        service.putItem("Users", initialItem);

        ObjectNode names = mapper.createObjectNode();
        names.put("#t", "tempField");
        names.put("#n", "name");
        ObjectNode values = mapper.createObjectNode();
        values.set(":newName", attributeValue("S", "new"));
        values.set(":other", attributeValue("S", "x"));

        service.updateItem("Users", userIdKey("u9"), null,
                "REMOVE #t SET #n = :newName, extra = :other", names, values, "ALL_NEW");

        JsonNode stored = service.getItem("Users", userIdKey("u9"));
        assertFalse(stored.has("tempField"), "REMOVE must apply");
        assertEquals("new", stored.get("name").get("S").asText(), "SET must apply");
        assertEquals("x", stored.get("extra").get("S").asText(), "second SET assignment must apply");
    }

    @Test
    void updateExpressionDeleteBeforeSetDoesNotSwallowSetKeywordAtIntraSetComma() {
        createUsersTable();

        ObjectNode initialItem = mapper.createObjectNode();
        initialItem.set("userId", attributeValue("S", "u10"));
        initialItem.set("name", attributeValue("S", "old"));
        ObjectNode ss = mapper.createObjectNode();
        ss.putArray("SS").add("keep").add("drop");
        initialItem.set("tags", ss);
        service.putItem("Users", initialItem);

        ObjectNode names = mapper.createObjectNode();
        names.put("#tag", "tags");
        names.put("#n", "name");
        ObjectNode values = mapper.createObjectNode();
        ObjectNode dropSet = mapper.createObjectNode();
        dropSet.putArray("SS").add("drop");
        values.set(":d", dropSet);
        values.set(":newName", attributeValue("S", "new"));
        values.set(":other", attributeValue("S", "x"));

        service.updateItem("Users", userIdKey("u10"), null,
                "DELETE #tag :d SET #n = :newName, extra = :other", names, values, "ALL_NEW");

        JsonNode stored = service.getItem("Users", userIdKey("u10"));
        JsonNode remaining = stored.get("tags").get("SS");
        assertEquals(1, remaining.size());
        assertEquals("keep", remaining.get(0).asText());
        assertEquals("new", stored.get("name").get("S").asText());
        assertEquals("x", stored.get("extra").get("S").asText());
    }

    @Test
    void updateExpressionFindsValidKeywordAfterAttributeNameSuffix() {
        // Regression for the indexOfKeyword loop: an attribute name ending in
        // a keyword substring ("oldSET") must not mask a following real clause.
        createUsersTable();

        ObjectNode initialItem = mapper.createObjectNode();
        initialItem.set("userId", attributeValue("S", "u12"));
        initialItem.set("oldSET", attributeValue("S", "bye"));
        service.putItem("Users", initialItem);

        ObjectNode values = mapper.createObjectNode();
        values.set(":v", attributeValue("S", "hi"));

        service.updateItem("Users", userIdKey("u12"), null,
                "REMOVE oldSET SET newAttr = :v", null, values, "ALL_NEW");

        JsonNode stored = service.getItem("Users", userIdKey("u12"));
        assertFalse(stored.has("oldSET"), "oldSET should be removed");
        assertEquals("hi", stored.get("newAttr").get("S").asText(),
                "SET must still be recognised past the oldSET attribute name");
    }

    @Test
    void updateExpressionDoesNotMatchKeywordInsideAttributeName() {
        // False-positive guard for the indexOfKeyword boundary relaxation.
        // An attribute literally named "prefixSET" must not be treated as a
        // clause keyword, and a following comma must still split the SET clause.
        createUsersTable();

        ObjectNode initialItem = mapper.createObjectNode();
        initialItem.set("userId", attributeValue("S", "u11"));
        service.putItem("Users", initialItem);

        ObjectNode values = mapper.createObjectNode();
        values.set(":v1", attributeValue("S", "one"));
        values.set(":v2", attributeValue("S", "two"));

        service.updateItem("Users", userIdKey("u11"), null,
                "SET prefixSET = :v1, other = :v2", null, values, "ALL_NEW");

        JsonNode stored = service.getItem("Users", userIdKey("u11"));
        assertEquals("one", stored.get("prefixSET").get("S").asText());
        assertEquals("two", stored.get("other").get("S").asText());
    }

    @Test
    void queryWithParenthesizedBetweenKeyCondition() {
        createOrdersTable();
        service.putItem("Orders", item("customerId", "c1", "orderId", "2026-01-01Z#a"));
        service.putItem("Orders", item("customerId", "c1", "orderId", "2026-06-15Z#b"));
        service.putItem("Orders", item("customerId", "c1", "orderId", "2026-12-31Z#c"));

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":pk", attributeValue("S", "c1"));
        exprValues.set(":start", attributeValue("S", "2026-01-01Z#"));
        exprValues.set(":end", attributeValue("S", "2026-12-31Z#z"));

        var result = service.query("Orders", null, exprValues,
                "customerId = :pk AND (orderId BETWEEN :start AND :end)", null, null);
        assertEquals(3, result.items().size(), "parenthesized BETWEEN should work");
    }

    @Test
    void queryWithCompactAndBetweenKeyCondition() {
        createOrdersTable();
        service.putItem("Orders", item("customerId", "c1", "orderId", "2026-01-01Z#a"));
        service.putItem("Orders", item("customerId", "c1", "orderId", "2026-06-15Z#b"));

        ObjectNode exprNames = mapper.createObjectNode();
        exprNames.put("#f0", "customerId");
        exprNames.put("#f1", "orderId");
        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":v0", attributeValue("S", "c1"));
        exprValues.set(":v1", attributeValue("S", "2026-01-01Z#"));
        exprValues.set(":v2", attributeValue("S", "2026-12-31Z#z"));

        var result = service.query("Orders", null, exprValues,
                "(#f0 = :v0)AND(#f1 BETWEEN :v1 AND :v2)", null, null, null, null, null, exprNames, "us-east-1");
        assertEquals(2, result.items().size(), "compact AND with BETWEEN should work");
    }

    @Test
    void updateItemWithNestedDottedPathSetAndRemove() {
        createOrdersTable();
        service.putItem("Orders", item("customerId", "c1", "orderId", "o1"));

        ObjectNode exprNames = mapper.createObjectNode();
        exprNames.put("#details", "details");
        exprNames.put("#status", "status");

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":val", attributeValue("S", "hello"));
        exprValues.set(":s", attributeValue("S", "active"));

        // SET a nested map field via dotted path: #details.subkey = :val, #status = :s
        ObjectNode key = mapper.createObjectNode();
        key.set("customerId", attributeValue("S", "c1"));
        key.set("orderId", attributeValue("S", "o1"));

        var result = service.updateItem("Orders", key, null,
                "SET #details.subkey = :val, #status = :s", exprNames, exprValues, "ALL_NEW", null, "us-east-1", "NONE");

        JsonNode updated = result.newItem();
        assertNotNull(updated);
        // status should be set at top level
        assertEquals("active", updated.get("status").get("S").asText());
        // details.subkey should be set in a nested map
        assertNotNull(updated.get("details"), "details map should exist");
        assertTrue(updated.get("details").has("M"), "details should be a DynamoDB Map");
        assertEquals("hello", updated.get("details").get("M").get("subkey").get("S").asText());

        // Now REMOVE the nested field
        result = service.updateItem("Orders", key, null,
                "REMOVE #details.subkey", exprNames, null, "ALL_NEW", null, "us-east-1", "NONE");

        updated = result.newItem();
        // The subkey should be removed from the nested map
        assertFalse(updated.get("details").get("M").has("subkey"), "subkey should be removed");
        // status should still be there
        assertEquals("active", updated.get("status").get("S").asText());
    }

    @Test
    void updateItemSetFollowedByRemovePreservesAllAssignments() {
        // Reproduces the bug where SET's last assignment was lost because findNextComma
        // consumed into the REMOVE clause's comma-separated list.
        createOrdersTable();
        service.putItem("Orders", item("customerId", "c1", "orderId", "o1"));

        ObjectNode exprNames = mapper.createObjectNode();
        exprNames.put("#a", "fieldA");
        exprNames.put("#b", "fieldB");
        exprNames.put("#c", "fieldC");
        exprNames.put("#d", "fieldD");

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":v1", attributeValue("S", "val1"));
        exprValues.set(":v2", attributeValue("S", "val2"));

        ObjectNode key = mapper.createObjectNode();
        key.set("customerId", attributeValue("S", "c1"));
        key.set("orderId", attributeValue("S", "o1"));

        // First, set all four fields
        service.updateItem("Orders", key, null,
                "SET #a = :v1, #b = :v1, #c = :v1, #d = :v1", exprNames, exprValues, "NONE", null, "us-east-1", "NONE");

        // SET last two assignments, then REMOVE two fields (comma-separated)
        var result = service.updateItem("Orders", key, null,
                "SET #a = :v1, #b = :v2 REMOVE #c, #d", exprNames, exprValues, "ALL_NEW", null, "us-east-1", "NONE");

        JsonNode updated = result.newItem();
        assertNotNull(updated);
        assertEquals("val1", updated.get("fieldA").get("S").asText(), "fieldA should be set");
        assertEquals("val2", updated.get("fieldB").get("S").asText(), "fieldB should be set (last SET before REMOVE)");
        assertNull(updated.get("fieldC"), "fieldC should be removed");
        assertNull(updated.get("fieldD"), "fieldD should be removed");
    }
}
