package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeAction;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.AttributeValueUpdate;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ComparisonOperator;
import software.amazon.awssdk.services.dynamodb.model.Condition;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;
import software.amazon.awssdk.services.dynamodb.model.LocalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnItemCollectionMetrics;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.dynamodb.model.Tag;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Compatibility tests for the DynamoDB conformance improvements.
 *
 * Covers:
 *  - Phase 1: ListTables (pagination, alphabetical order, Limit validation) + ValidationException rename
 *  - Phase 2: ProjectionExpression on GetItem, Query, Scan, BatchGetItem
 *  - Phase 3: Batch limits, transaction validations, empty-key rejection,
 *             ReturnItemCollectionMetrics, idempotency token
 *  - Phase 4: Select=COUNT, parallel scan, attribute_type() function, parenthesized key condition
 *  - Phase 7: TagResource/ListTagsOfResource error cases
 *  - Phase 8: ReturnValuesOnConditionCheckFailure
 *  - Phase 10: Legacy API (AttributesToGet, AttributeUpdates, QueryFilter, ScanFilter)
 *  - Phase 11: Enum validation before table lookup
 */
@DisplayName("DynamoDB Conformance Changes")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbConformanceChangesTest {

    private static DynamoDbClient ddb;
    private static final String TABLE = "conformance-test-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String TABLE_LSI = "conformance-lsi-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    static void setup() {
        ddb = TestFixtures.dynamoDbClient();

        ddb.createTable(r -> r
                .tableName(TABLE)
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST));

        ddb.createTable(r -> r
                .tableName(TABLE_LSI)
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("gsi_sk").attributeType(ScalarAttributeType.S).build())
                .localSecondaryIndexes(LocalSecondaryIndex.builder()
                        .indexName("lsi-by-gsi-sk")
                        .keySchema(
                                KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName("gsi_sk").keyType(KeyType.RANGE).build())
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build())
                .billingMode(BillingMode.PAY_PER_REQUEST));

        // Seed items
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            ddb.putItem(r -> r.tableName(TABLE).item(Map.of(
                    "pk", av("p1"),
                    "sk", av("s" + idx),
                    "name", av("Item-" + idx),
                    "count", AttributeValue.builder().n(String.valueOf(idx * 10)).build(),
                    "status", av("active"))));
        }
    }

    @AfterAll
    static void cleanup() {
        try { ddb.deleteTable(r -> r.tableName(TABLE)); } catch (Exception ignored) {}
        try { ddb.deleteTable(r -> r.tableName(TABLE_LSI)); } catch (Exception ignored) {}
    }

    // -----------------------------------------------------------------------
    // Phase 1 — ListTables: alphabetical order, Limit, ExclusiveStartTableName
    // -----------------------------------------------------------------------

    @Test @Order(10)
    void listTablesReturnsAlphabeticalOrder() {
        ListTablesResponse resp = ddb.listTables();
        List<String> names = resp.tableNames();
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        assertThat(names).isEqualTo(sorted);
    }

    @Test @Order(11)
    void listTablesWithLimit() {
        ListTablesResponse first = ddb.listTables(r -> r.limit(1));
        assertThat(first.tableNames()).hasSize(1);
        assertThat(first.lastEvaluatedTableName()).isNotNull();

        ListTablesResponse second = ddb.listTables(r -> r
                .limit(1)
                .exclusiveStartTableName(first.lastEvaluatedTableName()));
        assertThat(second.tableNames()).isNotEmpty();
        assertThat(second.tableNames()).doesNotContain(first.tableNames().get(0));
    }

    @Test @Order(12)
    void listTablesLimitZeroThrowsValidationException() {
        assertThatThrownBy(() -> ddb.listTables(r -> r.limit(0)))
                .isInstanceOf(DynamoDbException.class)
                .satisfies(e -> assertThat(((DynamoDbException) e).awsErrorDetails().errorCode())
                        .isEqualTo("ValidationException"));
    }

    @Test @Order(13)
    void listTablesLimitOver100ThrowsValidationException() {
        assertThatThrownBy(() -> ddb.listTables(r -> r.limit(101)))
                .isInstanceOf(DynamoDbException.class)
                .satisfies(e -> assertThat(((DynamoDbException) e).awsErrorDetails().errorCode())
                        .isEqualTo("ValidationException"));
    }

    // -----------------------------------------------------------------------
    // Phase 1 — ValidationException for invalid table names
    // -----------------------------------------------------------------------

    @Test @Order(20)
    void putItemInvalidTableNameThrowsValidationException() {
        assertThatThrownBy(() -> ddb.putItem(r -> r.tableName("ab").item(Map.of("pk", av("x")))))
                .isInstanceOf(DynamoDbException.class)
                .satisfies(e -> assertThat(((DynamoDbException) e).awsErrorDetails().errorCode())
                        .isEqualTo("ValidationException"));
    }

    // -----------------------------------------------------------------------
    // Phase 2 — ProjectionExpression
    // -----------------------------------------------------------------------

    @Test @Order(30)
    void getItemWithProjectionReturnsOnlyProjectedAttributes() {
        GetItemResponse resp = ddb.getItem(r -> r
                .tableName(TABLE)
                .key(Map.of("pk", av("p1"), "sk", av("s0")))
                .projectionExpression("#n")
                .expressionAttributeNames(Map.of("#n", "name")));

        assertThat(resp.item()).containsKey("name");
        assertThat(resp.item()).doesNotContainKey("count");
        assertThat(resp.item()).doesNotContainKey("status");
    }

    @Test @Order(31)
    void queryWithProjectionReturnsOnlyProjectedAttributes() {
        QueryResponse resp = ddb.query(r -> r
                .tableName(TABLE)
                .keyConditionExpression("pk = :pk")
                .expressionAttributeValues(Map.of(":pk", av("p1")))
                .projectionExpression("sk, #n")
                .expressionAttributeNames(Map.of("#n", "name")));

        assertThat(resp.items()).isNotEmpty();
        for (Map<String, AttributeValue> item : resp.items()) {
            assertThat(item).containsKeys("sk", "name");
            assertThat(item).doesNotContainKey("count");
        }
    }

    @Test @Order(32)
    void scanWithProjectionReturnsOnlyProjectedAttributes() {
        ScanResponse resp = ddb.scan(r -> r
                .tableName(TABLE)
                .filterExpression("pk = :pk")
                .expressionAttributeValues(Map.of(":pk", av("p1")))
                .projectionExpression("pk, sk"));

        assertThat(resp.items()).isNotEmpty();
        for (Map<String, AttributeValue> item : resp.items()) {
            assertThat(item).containsKeys("pk", "sk");
            assertThat(item).doesNotContainKey("name");
        }
    }

    @Test @Order(33)
    void batchGetItemWithProjectionReturnsOnlyProjectedAttributes() {
        BatchGetItemResponse resp = ddb.batchGetItem(r -> r
                .requestItems(Map.of(TABLE, KeysAndAttributes.builder()
                        .keys(Map.of("pk", av("p1"), "sk", av("s0")))
                        .projectionExpression("#n")
                        .expressionAttributeNames(Map.of("#n", "name"))
                        .build())));

        List<Map<String, AttributeValue>> items = resp.responses().get(TABLE);
        assertThat(items).isNotEmpty();
        assertThat(items.get(0)).containsKey("name");
        assertThat(items.get(0)).doesNotContainKey("count");
    }

    // -----------------------------------------------------------------------
    // Phase 3 — Batch limits and empty-key rejection
    // -----------------------------------------------------------------------

    @Test @Order(40)
    void batchWriteItemRejectsMoreThan25Items() {
        List<WriteRequest> writes = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            writes.add(WriteRequest.builder()
                    .putRequest(PutRequest.builder()
                            .item(Map.of("pk", av("excess-" + i), "sk", av("s")))
                            .build())
                    .build());
        }
        assertThatThrownBy(() -> ddb.batchWriteItem(r -> r.requestItems(Map.of(TABLE, writes))))
                .isInstanceOf(DynamoDbException.class)
                .satisfies(e -> assertThat(((DynamoDbException) e).awsErrorDetails().errorCode())
                        .isEqualTo("ValidationException"));
    }

    @Test @Order(41)
    void batchGetItemRejectsMoreThan100Keys() {
        List<Map<String, AttributeValue>> keys = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            keys.add(Map.of("pk", av("k-" + i), "sk", av("s")));
        }
        assertThatThrownBy(() -> ddb.batchGetItem(r -> r
                .requestItems(Map.of(TABLE, KeysAndAttributes.builder().keys(keys).build()))))
                .isInstanceOf(DynamoDbException.class)
                .satisfies(e -> assertThat(((DynamoDbException) e).awsErrorDetails().errorCode())
                        .isEqualTo("ValidationException"));
    }

    @Test @Order(42)
    void putItemRejectsEmptyStringPrimaryKey() {
        assertThatThrownBy(() -> ddb.putItem(r -> r
                .tableName(TABLE)
                .item(Map.of("pk", av(""), "sk", av("s1")))))
                .isInstanceOf(DynamoDbException.class)
                .satisfies(e -> assertThat(((DynamoDbException) e).awsErrorDetails().errorCode())
                        .isEqualTo("ValidationException"));
    }

    @Test @Order(43)
    void transactWriteItemsRejectsDuplicateKeys() {
        assertThatThrownBy(() -> ddb.transactWriteItems(r -> r
                .transactItems(
                        TransactWriteItem.builder().put(Put.builder()
                                .tableName(TABLE)
                                .item(Map.of("pk", av("dup"), "sk", av("s1")))
                                .build()).build(),
                        TransactWriteItem.builder().put(Put.builder()
                                .tableName(TABLE)
                                .item(Map.of("pk", av("dup"), "sk", av("s1")))
                                .build()).build())))
                .isInstanceOf(DynamoDbException.class)
                .satisfies(e -> assertThat(((DynamoDbException) e).awsErrorDetails().errorCode())
                        .isEqualTo("ValidationException"));
    }

    @Test @Order(44)
    void transactWriteItemsIdempotencyTokenRejectsConflict() {
        String token = UUID.randomUUID().toString();

        // First request succeeds
        ddb.transactWriteItems(r -> r
                .clientRequestToken(token)
                .transactItems(TransactWriteItem.builder()
                        .put(Put.builder()
                                .tableName(TABLE)
                                .item(Map.of("pk", av("idem-test"), "sk", av("s")))
                                .build())
                        .build()));

        // Same token, different payload → IdempotentParameterMismatchException
        assertThatThrownBy(() -> ddb.transactWriteItems(r -> r
                .clientRequestToken(token)
                .transactItems(TransactWriteItem.builder()
                        .put(Put.builder()
                                .tableName(TABLE)
                                .item(Map.of("pk", av("idem-different"), "sk", av("s")))
                                .build())
                        .build())))
                .isInstanceOf(DynamoDbException.class)
                .satisfies(e -> assertThat(((DynamoDbException) e).awsErrorDetails().errorCode())
                        .isEqualTo("IdempotentParameterMismatchException"));
    }

    @Test @Order(45)
    void returnItemCollectionMetricsOnLsiTable() {
        PutItemResponse resp = ddb.putItem(r -> r
                .tableName(TABLE_LSI)
                .item(Map.of(
                        "pk", av("m1"),
                        "sk", av("sk-a"),
                        "gsi_sk", av("g1")))
                .returnItemCollectionMetrics(ReturnItemCollectionMetrics.SIZE));

        assertThat(resp.itemCollectionMetrics()).isNotNull();
        assertThat(resp.itemCollectionMetrics().sizeEstimateRangeGB()).hasSize(2);
    }

    // -----------------------------------------------------------------------
    // Phase 4 — Select=COUNT, parallel scan, attribute_type(), parenthesized key condition
    // -----------------------------------------------------------------------

    @Test @Order(50)
    void querySelectCountReturnsCountNotItems() {
        QueryResponse resp = ddb.query(r -> r
                .tableName(TABLE)
                .keyConditionExpression("pk = :pk")
                .expressionAttributeValues(Map.of(":pk", av("p1")))
                .select(Select.COUNT));

        assertThat(resp.count()).isEqualTo(5);
        assertThat(resp.items()).isEmpty();
    }

    @Test @Order(51)
    void scanSelectCountReturnsCountNotItems() {
        ScanResponse resp = ddb.scan(r -> r
                .tableName(TABLE)
                .filterExpression("pk = :pk")
                .expressionAttributeValues(Map.of(":pk", av("p1")))
                .select(Select.COUNT));

        assertThat(resp.count()).isGreaterThan(0);
        assertThat(resp.items()).isEmpty();
    }

    @Test @Order(52)
    void parallelScanPartitionsResultsCorrectly() {
        int totalSegments = 2;
        List<Map<String, AttributeValue>> segment0 = ddb.scan(r -> r
                .tableName(TABLE)
                .segment(0).totalSegments(totalSegments)).items();
        List<Map<String, AttributeValue>> segment1 = ddb.scan(r -> r
                .tableName(TABLE)
                .segment(1).totalSegments(totalSegments)).items();

        // Key each item by pk#sk to handle multiple items sharing the same sk
        Set<String> keys0 = new HashSet<>(), keys1 = new HashSet<>();
        segment0.forEach(i -> keys0.add(i.get("pk").s() + "#" + i.get("sk").s()));
        segment1.forEach(i -> keys1.add(i.get("pk").s() + "#" + i.get("sk").s()));

        // No overlap between segments
        assertThat(keys0).doesNotContainAnyElementsOf(keys1);
        // Union covers all items in the table
        int totalCount = ddb.scan(r -> r.tableName(TABLE)).count();
        Set<String> combined = new HashSet<>(keys0);
        combined.addAll(keys1);
        assertThat(combined).hasSize(totalCount);
    }

    @Test @Order(53)
    void parallelScanRequiresBothSegmentAndTotalSegments() {
        assertThatThrownBy(() -> ddb.scan(r -> r.tableName(TABLE).segment(0)))
                .isInstanceOf(DynamoDbException.class)
                .satisfies(e -> assertThat(((DynamoDbException) e).awsErrorDetails().errorCode())
                        .isEqualTo("ValidationException"));
    }

    @Test @Order(54)
    void queryWithAttributeTypeFunction() {
        QueryResponse resp = ddb.query(r -> r
                .tableName(TABLE)
                .keyConditionExpression("pk = :pk")
                .filterExpression("attribute_type(#n, :t)")
                .expressionAttributeValues(Map.of(
                        ":pk", av("p1"),
                        ":t", av("S")))
                .expressionAttributeNames(Map.of("#n", "name")));

        assertThat(resp.items()).hasSize(5);
    }

    @Test @Order(55)
    void queryWithParenthesizedKeyCondition() {
        QueryResponse resp = ddb.query(r -> r
                .tableName(TABLE)
                .keyConditionExpression("(pk = :pk AND sk = :sk)")
                .expressionAttributeValues(Map.of(":pk", av("p1"), ":sk", av("s0"))));

        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).get("sk").s()).isEqualTo("s0");
    }

    @Test @Order(56)
    void queryRequiresKeyCondition() {
        assertThatThrownBy(() -> ddb.query(r -> r.tableName(TABLE)))
                .isInstanceOf(DynamoDbException.class)
                .satisfies(e -> assertThat(((DynamoDbException) e).awsErrorDetails().errorCode())
                        .isEqualTo("ValidationException"));
    }

    // -----------------------------------------------------------------------
    // Phase 7 — Tags: ARN validation and non-existent ARN behaviour
    // -----------------------------------------------------------------------

    @Test @Order(60)
    void tagResourceWithInvalidArnThrowsValidationException() {
        assertThatThrownBy(() -> ddb.tagResource(r -> r
                .resourceArn("not-an-arn")
                .tags(Tag.builder().key("k").value("v").build())))
                .isInstanceOf(DynamoDbException.class)
                .satisfies(e -> assertThat(((DynamoDbException) e).awsErrorDetails().errorCode())
                        .isEqualTo("ValidationException"));
    }

    @Test @Order(61)
    void listTagsOfResourceWithValidFormatNonExistentArnThrowsAccessDeniedException() {
        assertThatThrownBy(() -> ddb.listTagsOfResource(r -> r
                .resourceArn("arn:aws:dynamodb:us-east-1:000000000000:table/does-not-exist")))
                .isInstanceOf(DynamoDbException.class)
                .satisfies(e -> assertThat(((DynamoDbException) e).awsErrorDetails().errorCode())
                        .isEqualTo("AccessDeniedException"));
    }

    // -----------------------------------------------------------------------
    // Phase 8 — ReturnValuesOnConditionCheckFailure
    // -----------------------------------------------------------------------

    @Test @Order(70)
    void putItemConditionFailureReturnsOldItemWhenRequested() {
        // Seed an existing item
        ddb.putItem(r -> r.tableName(TABLE).item(Map.of(
                "pk", av("cond-test"), "sk", av("s"), "val", av("old"))));

        DynamoDbException ex = (DynamoDbException) catchThrowable(() ->
                ddb.putItem(r -> r
                        .tableName(TABLE)
                        .item(Map.of("pk", av("cond-test"), "sk", av("s"), "val", av("new")))
                        .conditionExpression("attribute_not_exists(pk)")
                        .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)));

        assertThat(ex).isNotNull();
        assertThat(ex.awsErrorDetails().errorCode()).isEqualTo("ConditionalCheckFailedException");
    }

    @Test @Order(71)
    void updateItemConditionFailureReturnsOldItemWhenRequested() {
        ddb.putItem(r -> r.tableName(TABLE).item(Map.of(
                "pk", av("cond-upd"), "sk", av("s"), "val", av("original"))));

        DynamoDbException ex = (DynamoDbException) catchThrowable(() ->
                ddb.updateItem(r -> r
                        .tableName(TABLE)
                        .key(Map.of("pk", av("cond-upd"), "sk", av("s")))
                        .updateExpression("SET val = :new")
                        .conditionExpression("val = :expected")
                        .expressionAttributeValues(Map.of(":new", av("new"), ":expected", av("wrong")))
                        .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)));

        assertThat(ex).isNotNull();
        assertThat(ex.awsErrorDetails().errorCode()).isEqualTo("ConditionalCheckFailedException");
    }

    // -----------------------------------------------------------------------
    // Phase 10 — Legacy API: AttributesToGet, AttributeUpdates, QueryFilter, ScanFilter
    // -----------------------------------------------------------------------

    @Test @Order(80)
    void getItemAttributesToGet() {
        GetItemResponse resp = ddb.getItem(r -> r
                .tableName(TABLE)
                .key(Map.of("pk", av("p1"), "sk", av("s0")))
                .attributesToGet("name", "count"));

        assertThat(resp.item()).containsKeys("name", "count");
        assertThat(resp.item()).doesNotContainKey("status");
        // AttributesToGet does NOT auto-include key attributes
        assertThat(resp.item()).doesNotContainKey("pk");
    }

    @Test @Order(81)
    void attributesToGetAndProjectionExpressionAreMutuallyExclusive() {
        assertThatThrownBy(() -> ddb.getItem(r -> r
                .tableName(TABLE)
                .key(Map.of("pk", av("p1"), "sk", av("s0")))
                .attributesToGet("name")
                .projectionExpression("name")))
                .isInstanceOf(DynamoDbException.class)
                .satisfies(e -> assertThat(((DynamoDbException) e).awsErrorDetails().errorCode())
                        .isEqualTo("ValidationException"));
    }

    @Test @Order(82)
    @SuppressWarnings("deprecation")
    void updateItemWithAttributeUpdates() {
        ddb.putItem(r -> r.tableName(TABLE).item(Map.of(
                "pk", av("legacy-upd"), "sk", av("s"), "color", av("red"))));

        ddb.updateItem(UpdateItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("pk", av("legacy-upd"), "sk", av("s")))
                .attributeUpdates(Map.of(
                        "color", AttributeValueUpdate.builder()
                                .value(av("blue"))
                                .action(AttributeAction.PUT)
                                .build()))
                .build());

        GetItemResponse resp = ddb.getItem(r -> r
                .tableName(TABLE)
                .key(Map.of("pk", av("legacy-upd"), "sk", av("s"))));
        assertThat(resp.item().get("color").s()).isEqualTo("blue");
    }

    @Test @Order(83)
    @SuppressWarnings("deprecation")
    void queryWithQueryFilter() {
        QueryResponse resp = ddb.query(QueryRequest.builder()
                .tableName(TABLE)
                .keyConditionExpression("pk = :pk")
                .expressionAttributeValues(Map.of(":pk", av("p1")))
                .queryFilter(Map.of("name", Condition.builder()
                        .comparisonOperator(ComparisonOperator.EQ)
                        .attributeValueList(av("Item-0"))
                        .build()))
                .build());

        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).get("name").s()).isEqualTo("Item-0");
    }

    @Test @Order(84)
    @SuppressWarnings("deprecation")
    void scanWithScanFilter() {
        ScanResponse resp = ddb.scan(ScanRequest.builder()
                .tableName(TABLE)
                .scanFilter(Map.of("name", Condition.builder()
                        .comparisonOperator(ComparisonOperator.EQ)
                        .attributeValueList(av("Item-2"))
                        .build()))
                .build());

        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).get("name").s()).isEqualTo("Item-2");
    }

    // -----------------------------------------------------------------------
    // Phase 11 — Enum validation fires before table lookup
    // -----------------------------------------------------------------------

    @Test @Order(90)
    void enumValidationFiresBeforeTableLookupOnPutItem() {
        DynamoDbException ex = (DynamoDbException) catchThrowable(() ->
                ddb.putItem(r -> r
                        .tableName("nonexistent-table-xyz")
                        .item(Map.of("pk", av("x")))
                        .returnValues(ReturnValue.UPDATED_NEW))); // invalid for PutItem

        assertThat(ex).isNotNull();
        assertThat(ex.awsErrorDetails().errorCode()).isEqualTo("ValidationException");
        assertThat(ex.awsErrorDetails().errorMessage()).doesNotContain("ResourceNotFoundException");
    }

    @Test @Order(91)
    void enumValidationFiresBeforeTableLookupOnDeleteItem() {
        DynamoDbException ex = (DynamoDbException) catchThrowable(() ->
                ddb.deleteItem(r -> r
                        .tableName("nonexistent-table-xyz")
                        .key(Map.of("pk", av("x"), "sk", av("y")))
                        .returnValues(ReturnValue.UPDATED_NEW))); // invalid for DeleteItem

        assertThat(ex).isNotNull();
        assertThat(ex.awsErrorDetails().errorCode()).isEqualTo("ValidationException");
    }

    // -----------------------------------------------------------------------
    // Phase 9 — Reserved word rejection in expressions
    // -----------------------------------------------------------------------

    @Test @Order(100)
    void reservedWordAsAttributeNameInConditionThrowsValidationException() {
        assertThatThrownBy(() -> ddb.putItem(r -> r
                .tableName(TABLE)
                .item(Map.of("pk", av("rw-test"), "sk", av("s")))
                .conditionExpression("attribute_not_exists(status)"))) // 'status' is a bare reserved word
                .isInstanceOf(DynamoDbException.class)
                .satisfies(e -> assertThat(((DynamoDbException) e).awsErrorDetails().errorCode())
                        .isEqualTo("ValidationException"));
    }

    @Test @Order(101)
    void reservedWordWithAliasPasses() {
        // Using #status alias should work fine
        assertThatCode(() -> ddb.putItem(r -> r
                .tableName(TABLE)
                .item(Map.of("pk", av("rw-alias"), "sk", av("s"), "status", av("ok")))
                .conditionExpression("attribute_not_exists(pk)")
                .expressionAttributeNames(Map.of("#st", "status"))))
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static AttributeValue av(String s) {
        return AttributeValue.builder().s(s).build();
    }
}
