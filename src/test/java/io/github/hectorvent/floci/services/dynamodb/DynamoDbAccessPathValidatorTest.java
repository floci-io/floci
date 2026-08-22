package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.LocalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamoDbAccessPathValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private TableDefinition table;
    private DynamoDbAccessPath tablePath;
    private DynamoDbAccessPath gsiPath;
    private DynamoDbAccessPath lsiPath;
    private DynamoDbAccessPath compositePkGsiPath;

    @BeforeEach
    void setUp() {
        table = new TableDefinition();
        table.setKeySchema(List.of(
                new KeySchemaElement("pk", "HASH"),
                new KeySchemaElement("sk", "RANGE")));
        table.setGlobalSecondaryIndexes(List.of(
                new GlobalSecondaryIndex(
                        "status-index",
                        List.of(new KeySchemaElement("status", "HASH"),
                                new KeySchemaElement("createdAt", "RANGE")),
                        null, "INCLUDE", List.of("summary")),
                new GlobalSecondaryIndex(
                        "tenant-region-index",
                        List.of(new KeySchemaElement("tenantId", "HASH"),
                                new KeySchemaElement("region", "HASH"),
                                new KeySchemaElement("createdAt", "RANGE")),
                        null, "ALL", null)));
        table.setLocalSecondaryIndexes(List.of(new LocalSecondaryIndex(
                "alternate-index",
                List.of(new KeySchemaElement("pk", "HASH"),
                        new KeySchemaElement("alternate", "RANGE")),
                null, "KEYS_ONLY")));

        tablePath = DynamoDbAccessPath.resolve(table, null);
        gsiPath = DynamoDbAccessPath.resolve(table, "status-index");
        lsiPath = DynamoDbAccessPath.resolve(table, "alternate-index");
        compositePkGsiPath = DynamoDbAccessPath.resolve(table, "tenant-region-index");
    }

    @Test
    void acceptsCompositePartitionKeyQueryWhenEveryHashAttributeHasEquality() {
        assertDoesNotThrow(() -> validateExpression(compositePkGsiPath,
                "tenantId = :t AND region = :r"));
        assertDoesNotThrow(() -> validateExpression(compositePkGsiPath,
                "region = :r AND tenantId = :t"));
    }

    @Test
    void rejectsCompositePartitionKeyQueryMissingOneHashAttribute() {
        AwsException error = assertThrows(AwsException.class,
                () -> validateExpression(compositePkGsiPath, "tenantId = :t"));

        assertEquals("Query condition missed key schema element: region", error.getMessage());
    }

    @Test
    void rejectsCompositePartitionKeyQueryWithNonEqualityCondition() {
        assertThrows(AwsException.class, () -> validateExpression(compositePkGsiPath,
                "tenantId = :t AND region > :r"));
    }

    @Test
    void validatesLegacyCompositePartitionKeyConditions() {
        ObjectNode valid = mapper.createObjectNode();
        valid.set("tenantId", legacyCondition("EQ", attributeValues("acme")));
        valid.set("region", legacyCondition("EQ", attributeValues("us")));
        assertDoesNotThrow(() -> DynamoDbAccessPathValidator.validateQuery(
                compositePkGsiPath, valid, null, null, null, null));

        ObjectNode missingRegion = mapper.createObjectNode();
        missingRegion.set("tenantId", legacyCondition("EQ", attributeValues("acme")));
        AwsException error = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateQuery(
                        compositePkGsiPath, missingRegion, null, null, null, null));
        assertEquals("Query condition missed key schema element: region", error.getMessage());
    }

    @Test
    void rejectsUnknownIndex() {
        AwsException error = assertThrows(AwsException.class,
                () -> DynamoDbAccessPath.resolve(table, "missing-index"));

        assertEquals("ValidationException", error.getErrorCode());
        assertEquals("The table does not have the specified index: missing-index", error.getMessage());
    }

    @Test
    void acceptsTableKeyConditionsInEitherOrder() {
        assertDoesNotThrow(() -> validateExpression(tablePath,
                "pk = :pk AND begins_with(sk, :prefix)"));
        assertDoesNotThrow(() -> validateExpression(tablePath,
                "sk >= :start AND pk = :pk"));
    }

    @Test
    void acceptsAliasedGsiKeyConditions() {
        ObjectNode names = mapper.createObjectNode();
        names.put("#status", "status");

        assertEquals(":status", DynamoDbAccessPathValidator.validateQuery(
                gsiPath, null, "#status = :status AND createdAt BETWEEN :start AND :end",
                null, null, names));
    }

    @Test
    void rejectsMissingPartitionKeyCondition() {
        AwsException error = assertThrows(AwsException.class,
                () -> validateExpression(tablePath, "sk = :sk"));

        assertEquals("Query condition missed key schema element: pk", error.getMessage());
    }

    @Test
    void rejectsNonKeyCondition() {
        AwsException error = assertThrows(AwsException.class,
                () -> validateExpression(tablePath, "pk = :pk AND status = :status"));

        assertEquals("Query key condition not supported", error.getMessage());
    }

    @Test
    void rejectsUnsupportedPartitionAndSortKeyOperators() {
        assertThrows(AwsException.class,
                () -> validateExpression(tablePath, "pk > :pk"));
        assertThrows(AwsException.class,
                () -> validateExpression(tablePath, "pk = :pk AND sk <> :sk"));
    }

    @Test
    void rejectsDuplicateAndNestedKeyConditions() {
        assertThrows(AwsException.class, () -> validateExpression(
                tablePath, "pk = :pk AND sk > :start AND sk < :end"));
        assertThrows(AwsException.class, () -> validateExpression(
                tablePath, "pk.value = :pk"));
    }

    @Test
    void rejectsMalformedKeyAndFilterExpressions() {
        AwsException keyError = assertThrows(AwsException.class,
                () -> validateExpression(tablePath, "pk ="));
        assertEquals("Invalid KeyConditionExpression: Syntax error", keyError.getMessage());

        AwsException filterError = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateQuery(
                        tablePath, null, "pk = :pk", "status =", null, null));
        assertEquals("Invalid FilterExpression: Syntax error", filterError.getMessage());
    }

    @Test
    void validatesLegacyKeyConditions() {
        ObjectNode valid = mapper.createObjectNode();
        valid.set("pk", legacyCondition("EQ", attributeValues("p1")));
        valid.set("sk", legacyCondition("BETWEEN", attributeValues("a", "z")));
        assertDoesNotThrow(() -> DynamoDbAccessPathValidator.validateQuery(
                tablePath, valid, null, null, null, null));

        ObjectNode nonKey = valid.deepCopy();
        nonKey.set("status", legacyCondition("EQ", attributeValues("open")));
        assertThrows(AwsException.class, () -> DynamoDbAccessPathValidator.validateQuery(
                tablePath, nonKey, null, null, null, null));

        ObjectNode missingPartitionKey = mapper.createObjectNode();
        missingPartitionKey.set("sk", legacyCondition("EQ", attributeValues("a")));
        assertThrows(AwsException.class, () -> DynamoDbAccessPathValidator.validateQuery(
                tablePath, missingPartitionKey, null, null, null, null));
    }

    @Test
    void rejectsSelectedKeysInQueryFilters() {
        assertThrows(AwsException.class, () -> DynamoDbAccessPathValidator.validateQuery(
                gsiPath, null, "status = :status", "createdAt > :cutoff", null, null));

        ObjectNode queryFilter = mapper.createObjectNode();
        queryFilter.set("status", legacyCondition("EQ", attributeValues("open")));
        assertThrows(AwsException.class, () -> DynamoDbAccessPathValidator.validateQuery(
                gsiPath, null, "status = :status", null, queryFilter, null));
    }

    @Test
    void enforcesGsiProjectionButAllowsLsiTableFetches() {
        ObjectNode names = mapper.createObjectNode();
        names.put("#summary", "summary");

        assertDoesNotThrow(() -> DynamoDbAccessPathValidator.validateSelection(
                table, gsiPath, "SPECIFIC_ATTRIBUTES", "pk, status, #summary",
                null, names));
        AwsException projectionError = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateSelection(
                        table, gsiPath, "SPECIFIC_ATTRIBUTES", "zeta, details",
                        null, null));
        assertEquals("One or more parameter values were invalid: Global secondary index "
                + "status-index does not project details, zeta", projectionError.getMessage());
        assertThrows(AwsException.class, () -> DynamoDbAccessPathValidator.validateSelection(
                table, gsiPath, "ALL_ATTRIBUTES", null, null, null));
        assertDoesNotThrow(() -> DynamoDbAccessPathValidator.validateSelection(
                table, lsiPath, "ALL_ATTRIBUTES", null, null, null));
    }

    @Test
    void rejectsTableAllProjectedAttributesAndInvalidSelectProjectionCombination() {
        assertThrows(AwsException.class, () -> DynamoDbAccessPathValidator.validateSelection(
                table, tablePath, "ALL_PROJECTED_ATTRIBUTES", null, null, null));
        assertThrows(AwsException.class, () -> DynamoDbAccessPathValidator.validateSelection(
                table, tablePath, "COUNT", "pk", null, null));
    }

    private void validateExpression(DynamoDbAccessPath accessPath, String expression) {
        DynamoDbAccessPathValidator.validateQuery(
                accessPath, null, expression, null, null, null);
    }

    private ObjectNode legacyCondition(String operator, ArrayNode values) {
        ObjectNode condition = mapper.createObjectNode();
        condition.put("ComparisonOperator", operator);
        condition.set("AttributeValueList", values);
        return condition;
    }

    private ArrayNode attributeValues(String... values) {
        ArrayNode result = mapper.createArrayNode();
        for (String value : values) {
            ObjectNode attributeValue = mapper.createObjectNode();
            attributeValue.put("S", value);
            result.add(attributeValue);
        }
        return result;
    }
}
