package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Compatibility tests for DynamoDB expression evaluation:
 * - Filter expressions with BOOL, IN, OR, NOT, nested parens
 * - Dotted paths in UpdateExpression SET/REMOVE
 * - ConsumedCapacity in responses
 * - Parenthesized BETWEEN in KeyConditionExpression
 */
@DisplayName("DynamoDB Expression & Capacity Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbExpressionTests {

    private static DynamoDbClient ddb;
    private static final String FILTER_TABLE = "expr-filter-test";
    private static final String BETWEEN_TABLE = "expr-between-test";

    @BeforeAll
    static void setup() {
        ddb = TestFixtures.dynamoDbClient();

        // Table for filter expression tests (hash-only)
        ddb.createTable(CreateTableRequest.builder()
                .tableName(FILTER_TABLE)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());

        // Table for BETWEEN / key-condition tests (hash + range)
        ddb.createTable(CreateTableRequest.builder()
                .tableName(BETWEEN_TABLE)
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());

        // Seed filter table
        ddb.putItem(PutItemRequest.builder().tableName(FILTER_TABLE).item(Map.of(
                "pk", AttributeValue.fromS("u1"),
                "deleted", AttributeValue.fromBool(false),
                "status", AttributeValue.fromN("1"),
                "category", AttributeValue.fromS("A")
        )).build());
        ddb.putItem(PutItemRequest.builder().tableName(FILTER_TABLE).item(Map.of(
                "pk", AttributeValue.fromS("u2"),
                "deleted", AttributeValue.fromBool(true),
                "status", AttributeValue.fromN("2"),
                "category", AttributeValue.fromS("B")
        )).build());
        ddb.putItem(PutItemRequest.builder().tableName(FILTER_TABLE).item(Map.of(
                "pk", AttributeValue.fromS("u3"),
                "deleted", AttributeValue.fromBool(false),
                "status", AttributeValue.fromN("1"),
                "category", AttributeValue.fromS("A")
        )).build());
        // u4 has no "deleted" attribute
        ddb.putItem(PutItemRequest.builder().tableName(FILTER_TABLE).item(Map.of(
                "pk", AttributeValue.fromS("u4"),
                "status", AttributeValue.fromN("3"),
                "category", AttributeValue.fromS("C")
        )).build());

        // Seed between table
        for (String sk : new String[]{"2026-01-01T00:00:00Z#a", "2026-06-15T00:00:00Z#b", "2026-12-31T00:00:00Z#c"}) {
            ddb.putItem(PutItemRequest.builder().tableName(BETWEEN_TABLE).item(Map.of(
                    "pk", AttributeValue.fromS("r1"),
                    "sk", AttributeValue.fromS(sk)
            )).build());
        }
    }

    @AfterAll
    static void cleanup() {
        if (ddb != null) {
            try { ddb.deleteTable(DeleteTableRequest.builder().tableName(FILTER_TABLE).build()); } catch (Exception ignored) {}
            try { ddb.deleteTable(DeleteTableRequest.builder().tableName(BETWEEN_TABLE).build()); } catch (Exception ignored) {}
            ddb.close();
        }
    }

    // ---- BOOL comparison ----

    @Test
    @Order(1)
    @DisplayName("Filter: BOOL not-equal excludes deleted items")
    void filterBoolNotEqual() {
        ScanResponse resp = ddb.scan(ScanRequest.builder()
                .tableName(FILTER_TABLE)
                .filterExpression("deleted <> :d")
                .expressionAttributeValues(Map.of(":d", AttributeValue.fromBool(true)))
                .build());
        // u1 (false), u3 (false), u4 (missing → <> true is true)
        assertThat(resp.count()).isEqualTo(3);
    }

    @Test
    @Order(2)
    @DisplayName("Filter: BOOL equal matches false")
    void filterBoolEqual() {
        ScanResponse resp = ddb.scan(ScanRequest.builder()
                .tableName(FILTER_TABLE)
                .filterExpression("deleted = :d")
                .expressionAttributeValues(Map.of(":d", AttributeValue.fromBool(false)))
                .build());
        assertThat(resp.count()).isEqualTo(2);
    }

    // ---- IN operator ----

    @Test
    @Order(3)
    @DisplayName("Filter: IN operator with single value")
    void filterInSingle() {
        ScanResponse resp = ddb.scan(ScanRequest.builder()
                .tableName(FILTER_TABLE)
                .filterExpression("status IN (:v0)")
                .expressionAttributeValues(Map.of(":v0", AttributeValue.fromN("1")))
                .build());
        assertThat(resp.count()).isEqualTo(2);
    }

    @Test
    @Order(4)
    @DisplayName("Filter: IN operator with multiple values")
    void filterInMultiple() {
        ScanResponse resp = ddb.scan(ScanRequest.builder()
                .tableName(FILTER_TABLE)
                .filterExpression("status IN (:v0, :v1)")
                .expressionAttributeValues(Map.of(
                        ":v0", AttributeValue.fromN("1"),
                        ":v1", AttributeValue.fromN("3")))
                .build());
        assertThat(resp.count()).isEqualTo(3);
    }

    // ---- OR operator ----

    @Test
    @Order(5)
    @DisplayName("Filter: OR operator")
    void filterOr() {
        ScanResponse resp = ddb.scan(ScanRequest.builder()
                .tableName(FILTER_TABLE)
                .filterExpression("status = :v1 OR status = :v2")
                .expressionAttributeValues(Map.of(
                        ":v1", AttributeValue.fromN("1"),
                        ":v2", AttributeValue.fromN("2")))
                .build());
        assertThat(resp.count()).isEqualTo(3);
    }

    // ---- NOT operator ----

    @Test
    @Order(6)
    @DisplayName("Filter: NOT operator")
    void filterNot() {
        ScanResponse resp = ddb.scan(ScanRequest.builder()
                .tableName(FILTER_TABLE)
                .filterExpression("NOT deleted = :d")
                .expressionAttributeValues(Map.of(":d", AttributeValue.fromBool(true)))
                .build());
        assertThat(resp.count()).isEqualTo(3);
    }

    // ---- Nested parentheses with AND + OR ----

    @Test
    @Order(7)
    @DisplayName("Filter: parenthesized AND + OR")
    void filterParenthesizedAndOr() {
        ScanResponse resp = ddb.scan(ScanRequest.builder()
                .tableName(FILTER_TABLE)
                .filterExpression("(status = :v1 OR status = :v3) AND category = :catA")
                .expressionAttributeValues(Map.of(
                        ":v1", AttributeValue.fromN("1"),
                        ":v3", AttributeValue.fromN("3"),
                        ":catA", AttributeValue.fromS("A")))
                .build());
        assertThat(resp.count()).isEqualTo(2);
    }

    // ---- Dotted path in UpdateExpression ----

    @Test
    @Order(8)
    @DisplayName("UpdateExpression: SET with dotted nested path")
    void updateDottedPath() {
        // Put an item with a nested map
        String pk = "dotted-test";
        ddb.putItem(PutItemRequest.builder()
                .tableName(FILTER_TABLE)
                .item(Map.of(
                        "pk", AttributeValue.fromS(pk),
                        "details", AttributeValue.builder().m(Map.of(
                                "name", AttributeValue.fromS("original")
                        )).build()))
                .build());

        // Update nested attribute via dotted path
        ddb.updateItem(UpdateItemRequest.builder()
                .tableName(FILTER_TABLE)
                .key(Map.of("pk", AttributeValue.fromS(pk)))
                .updateExpression("SET details.#sub = :val")
                .expressionAttributeNames(Map.of("#sub", "name"))
                .expressionAttributeValues(Map.of(":val", AttributeValue.fromS("updated")))
                .build());

        GetItemResponse get = ddb.getItem(GetItemRequest.builder()
                .tableName(FILTER_TABLE)
                .key(Map.of("pk", AttributeValue.fromS(pk)))
                .build());

        assertThat(get.item().get("details").m().get("name").s()).isEqualTo("updated");

        // Clean up
        ddb.deleteItem(DeleteItemRequest.builder()
                .tableName(FILTER_TABLE)
                .key(Map.of("pk", AttributeValue.fromS(pk)))
                .build());
    }

    // ---- ConsumedCapacity ----

    @Test
    @Order(9)
    @DisplayName("ConsumedCapacity: TOTAL returns capacity in Scan")
    void consumedCapacityTotal() {
        ScanResponse resp = ddb.scan(ScanRequest.builder()
                .tableName(FILTER_TABLE)
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                .build());

        assertThat(resp.consumedCapacity()).isNotNull();
        assertThat(resp.consumedCapacity().tableName()).isEqualTo(FILTER_TABLE);
        assertThat(resp.consumedCapacity().capacityUnits()).isGreaterThan(0);
    }

    @Test
    @Order(10)
    @DisplayName("ConsumedCapacity: NONE omits capacity")
    void consumedCapacityNone() {
        ScanResponse resp = ddb.scan(ScanRequest.builder()
                .tableName(FILTER_TABLE)
                .returnConsumedCapacity(ReturnConsumedCapacity.NONE)
                .build());

        assertThat(resp.consumedCapacity()).isNull();
    }

    @Test
    @Order(11)
    @DisplayName("ConsumedCapacity: TOTAL in GetItem")
    void consumedCapacityGetItem() {
        GetItemResponse resp = ddb.getItem(GetItemRequest.builder()
                .tableName(FILTER_TABLE)
                .key(Map.of("pk", AttributeValue.fromS("u1")))
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                .build());

        assertThat(resp.consumedCapacity()).isNotNull();
        assertThat(resp.consumedCapacity().capacityUnits()).isGreaterThan(0);
    }

    @Test
    @Order(12)
    @DisplayName("ConsumedCapacity: TOTAL in PutItem")
    void consumedCapacityPutItem() {
        PutItemResponse resp = ddb.putItem(PutItemRequest.builder()
                .tableName(FILTER_TABLE)
                .item(Map.of(
                        "pk", AttributeValue.fromS("cap-test"),
                        "data", AttributeValue.fromS("v")))
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                .build());

        assertThat(resp.consumedCapacity()).isNotNull();
        assertThat(resp.consumedCapacity().capacityUnits()).isGreaterThan(0);

        // Clean up
        ddb.deleteItem(DeleteItemRequest.builder()
                .tableName(FILTER_TABLE)
                .key(Map.of("pk", AttributeValue.fromS("cap-test")))
                .build());
    }

    // ---- Parenthesized BETWEEN in KeyConditionExpression ----

    @Test
    @Order(13)
    @DisplayName("Query: parenthesized BETWEEN in KeyConditionExpression")
    void queryParenthesizedBetween() {
        QueryResponse resp = ddb.query(QueryRequest.builder()
                .tableName(BETWEEN_TABLE)
                .keyConditionExpression("pk = :pk AND (sk BETWEEN :start AND :end)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS("r1"),
                        ":start", AttributeValue.fromS("2026-01-01T00:00:00Z#"),
                        ":end", AttributeValue.fromS("2026-12-31T23:59:59Z#")))
                .build());

        assertThat(resp.count()).isEqualTo(3);
    }

    @Test
    @Order(14)
    @DisplayName("Query: compact format BETWEEN — no spaces around AND")
    void queryCompactBetween() {
        QueryResponse resp = ddb.query(QueryRequest.builder()
                .tableName(BETWEEN_TABLE)
                .keyConditionExpression("(#f0 = :v0)AND(#f1 BETWEEN :v1 AND :v2)")
                .expressionAttributeNames(Map.of("#f0", "pk", "#f1", "sk"))
                .expressionAttributeValues(Map.of(
                        ":v0", AttributeValue.fromS("r1"),
                        ":v1", AttributeValue.fromS("2026-01-01T00:00:00Z#"),
                        ":v2", AttributeValue.fromS("2026-12-31T23:59:59Z#z")))
                .build());

        assertThat(resp.count()).isEqualTo(3);
    }
}
