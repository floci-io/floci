package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ComparisonOperator;
import software.amazon.awssdk.services.dynamodb.model.Condition;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.LocalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.Select;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamoDbAccessPathValidationTest {

    private static final String TABLE = "access-path-" + UUID.randomUUID().toString().substring(0, 8);
    private static DynamoDbClient ddb;

    @BeforeAll
    static void createTable() {
        ddb = TestFixtures.dynamoDbClient();
        ddb.createTable(request -> request
                .tableName(TABLE)
                .keySchema(key("pk", KeyType.HASH), key("sk", KeyType.RANGE))
                .attributeDefinitions(
                        attribute("pk"), attribute("sk"), attribute("status"),
                        attribute("createdAt"), attribute("alternate"))
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName("status-index")
                        .keySchema(key("status", KeyType.HASH), key("createdAt", KeyType.RANGE),
                                key("alternate", KeyType.RANGE))
                        .projection(Projection.builder()
                                .projectionType(ProjectionType.INCLUDE)
                                .nonKeyAttributes("summary")
                                .build())
                        .build())
                .localSecondaryIndexes(LocalSecondaryIndex.builder()
                        .indexName("alternate-index")
                        .keySchema(key("pk", KeyType.HASH), key("alternate", KeyType.RANGE))
                        .projection(Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build())
                        .build())
                .billingMode(BillingMode.PAY_PER_REQUEST));
    }

    @AfterAll
    static void deleteTable() {
        ddb.deleteTable(request -> request.tableName(TABLE));
        ddb.close();
    }

    @Test
    void queryAndScanRejectUnknownIndexOnEmptyTable() {
        assertValidationException(() -> ddb.scan(request -> request
                .tableName(TABLE)
                .indexName("missing-index")));

        assertValidationException(() -> ddb.query(request -> request
                .tableName(TABLE)
                .indexName("missing-index")
                .keyConditionExpression("pk = :pk")
                .expressionAttributeValues(Map.of(":pk", value("p1")))));
    }

    @Test
    void queryRejectsNonKeyConditionOnEmptyTable() {
        assertValidationException(() -> ddb.query(request -> request
                .tableName(TABLE)
                .keyConditionExpression("pk = :pk AND status = :status")
                .expressionAttributeValues(Map.of(
                        ":pk", value("p1"),
                        ":status", value("open")))));
    }

    @Test
    void queryRejectsKeyConditionValuesWithWrongSchemaTypes() {
        assertValidationException(() -> ddb.query(request -> request
                .tableName(TABLE)
                .keyConditionExpression("pk = :pk AND sk > :sk")
                .expressionAttributeValues(Map.of(
                        ":pk", value("p1"),
                        ":sk", numberValue("1")))));
    }

    @Test
    void acceptsTableKeyConditionsInEitherOrder() {
        var response = ddb.query(request -> request
                .tableName(TABLE)
                .keyConditionExpression("sk >= :sk AND pk = :pk")
                .expressionAttributeValues(Map.of(
                        ":pk", value("p1"),
                        ":sk", value("a"))));

        assertThat(response.count()).isZero();
    }

    @Test
    void lsiRejectsBaseTableSortKeyCondition() {
        assertValidationException(() -> ddb.query(request -> request
                .tableName(TABLE)
                .indexName("alternate-index")
                .keyConditionExpression("pk = :pk AND sk = :sk")
                .expressionAttributeValues(Map.of(
                        ":pk", value("p1"),
                        ":sk", value("s1")))));
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyKeyConditionsUseSelectedGsiSchema() {
        assertValidationException(() -> ddb.query(request -> request
                .tableName(TABLE)
                .indexName("status-index")
                .keyConditions(Map.of(
                        "status", condition(ComparisonOperator.EQ, "open"),
                        "pk", condition(ComparisonOperator.EQ, "p1")))));
    }

    @Test
    void queryRejectsSelectedKeyInFilterExpression() {
        assertValidationException(() -> ddb.query(request -> request
                .tableName(TABLE)
                .indexName("status-index")
                .keyConditionExpression("#status = :status")
                .filterExpression("createdAt > :cutoff")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(
                        ":status", value("open"),
                        ":cutoff", value("2026-01-01")))));
    }

    @Test
    void queryAndScanRejectNonProjectedGsiAttribute() {
        assertValidationException(() -> ddb.query(request -> request
                .tableName(TABLE)
                .indexName("status-index")
                .keyConditionExpression("#status = :status")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":status", value("open")))
                .projectionExpression("details")));

        assertValidationException(() -> ddb.scan(request -> request
                .tableName(TABLE)
                .indexName("status-index")
                .projectionExpression("details")));
    }

    @Test
    void acceptsProjectedGsiAttributesAndLsiTableFetch() {
        var gsiResponse = ddb.query(request -> request
                .tableName(TABLE)
                .indexName("status-index")
                .keyConditionExpression("#status = :status")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":status", value("open")))
                .projectionExpression("pk, #status, summary"));
        assertThat(gsiResponse.count()).isZero();

        var lsiResponse = ddb.query(request -> request
                .tableName(TABLE)
                .indexName("alternate-index")
                .keyConditionExpression("pk = :pk")
                .expressionAttributeValues(Map.of(":pk", value("p1")))
                .select(Select.ALL_ATTRIBUTES));
        assertThat(lsiResponse.count()).isZero();

        var lsiScanResponse = ddb.scan(request -> request
                .tableName(TABLE)
                .indexName("alternate-index")
                .projectionExpression("details"));
        assertThat(lsiScanResponse.count()).isZero();
    }

    @Test
    void gsiStillRejectsConsistentReads() {
        assertValidationException(() -> ddb.query(request -> request
                .tableName(TABLE)
                .indexName("status-index")
                .consistentRead(true)
                .keyConditionExpression("#status = :status")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":status", value("open")))));
    }

    @Test
    void queryAndScanRejectWrongExclusiveStartKeyTypes() {
        Map<String, AttributeValue> invalidStartKey = Map.of(
                "pk", AttributeValue.builder().n("1").build(),
                "sk", value("s1"));

        assertValidationException(() -> ddb.query(request -> request
                .tableName(TABLE)
                .keyConditionExpression("pk = :pk")
                .expressionAttributeValues(Map.of(":pk", value("p1")))
                .exclusiveStartKey(invalidStartKey)));

        assertValidationException(() -> ddb.scan(request -> request
                .tableName(TABLE)
                .exclusiveStartKey(invalidStartKey)));
    }

    @Test
    @SuppressWarnings("deprecation")
    void queryRejectsInvalidCompositeSortKeyConditions() {
        assertValidationException(() -> ddb.query(request -> request
                .tableName(TABLE)
                .indexName("status-index")
                .keyConditionExpression("#status = :status AND alternate = :alternate")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(
                        ":status", value("open"),
                        ":alternate", value("a1")))));

        assertValidationException(() -> ddb.query(request -> request
                .tableName(TABLE)
                .indexName("status-index")
                .keyConditionExpression("#status = :status AND createdAt > :createdAt "
                        + "AND alternate = :alternate")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(
                        ":status", value("open"),
                        ":createdAt", value("2026-01-01"),
                        ":alternate", value("a1")))));

        assertValidationException(() -> ddb.query(request -> request
                .tableName(TABLE)
                .indexName("status-index")
                .keyConditions(Map.of(
                        "status", condition(ComparisonOperator.EQ, "open"),
                        "alternate", condition(ComparisonOperator.EQ, "a1")))));

        assertValidationException(() -> ddb.query(request -> request
                .tableName(TABLE)
                .indexName("status-index")
                .keyConditions(Map.of(
                        "status", condition(ComparisonOperator.EQ, "open"),
                        "createdAt", condition(ComparisonOperator.GT, "2026-01-01"),
                        "alternate", condition(ComparisonOperator.EQ, "a1")))));
    }

    private static void assertValidationException(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(DynamoDbException.class)
                .satisfies(error -> assertThat(((DynamoDbException) error)
                        .awsErrorDetails().errorCode()).isEqualTo("ValidationException"));
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder()
                .attributeName(name)
                .attributeType(ScalarAttributeType.S)
                .build();
    }

    private static AttributeValue value(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue numberValue(String value) {
        return AttributeValue.builder().n(value).build();
    }

    private static Condition condition(ComparisonOperator operator, String attributeValue) {
        return Condition.builder()
                .comparisonOperator(operator)
                .attributeValueList(value(attributeValue))
                .build();
    }
}
