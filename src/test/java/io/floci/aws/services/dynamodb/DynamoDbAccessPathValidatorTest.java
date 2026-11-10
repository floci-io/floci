package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.GlobalSecondaryIndex;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import io.floci.aws.services.dynamodb.model.LocalSecondaryIndex;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

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
        table.setAttributeDefinitions(List.of(
                new AttributeDefinition("pk", "S"),
                new AttributeDefinition("sk", "S"),
                new AttributeDefinition("status", "S"),
                new AttributeDefinition("createdAt", "S"),
                new AttributeDefinition("sequence", "S"),
                new AttributeDefinition("alternate", "S"),
                new AttributeDefinition("tenantId", "S"),
                new AttributeDefinition("region", "S")));
        table.setGlobalSecondaryIndexes(List.of(
                new GlobalSecondaryIndex(
                        "status-index",
                        List.of(new KeySchemaElement("status", "HASH"),
                                new KeySchemaElement("createdAt", "RANGE"),
                                new KeySchemaElement("sequence", "RANGE")),
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
    void acceptsKeyConditionsGroupedInParentheses() {
        assertDoesNotThrow(() -> validateExpression(compositePkGsiPath,
                "tenantId = :t AND (region = :r AND createdAt > :c)"));
        assertDoesNotThrow(() -> validateExpression(compositePkGsiPath,
                "(tenantId = :t AND region = :r) AND (createdAt > :c)"));
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
                table, compositePkGsiPath, valid, null, null, null, null, null));

        ObjectNode missingRegion = mapper.createObjectNode();
        missingRegion.set("tenantId", legacyCondition("EQ", attributeValues("acme")));
        AwsException error = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateQuery(
                        table, compositePkGsiPath, missingRegion, null, null, null, null, null));
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
                table, gsiPath, null, "#status = :status AND createdAt BETWEEN :start AND :end",
                null, null, names, expressionValues(":status", ":start", ":end")));
    }

    @Test
    void acceptsCompositeSortKeyPrefixWithInequalityLast() {
        assertDoesNotThrow(() -> validateExpression(gsiPath,
                "status = :status AND createdAt = :createdAt AND sequence >= :sequence"));
        assertDoesNotThrow(() -> validateExpression(gsiPath,
                "status = :status AND createdAt BETWEEN :start AND :end"));
    }

    @Test
    void rejectsSkippedCompositeSortKey() {
        AwsException error = assertThrows(AwsException.class,
                () -> validateExpression(gsiPath, "status = :status AND sequence = :sequence"));

        assertEquals("RANGE key attributes createdAt must have equality conditions specified in the query "
                + "because a condition is present on key attribute sequence", error.getMessage());
    }

    @Test
    void rejectsCompositeSortKeyConditionAfterInequality() {
        AwsException error = assertThrows(AwsException.class,
                () -> validateExpression(gsiPath,
                        "status = :status AND createdAt > :createdAt AND sequence = :sequence"));

        assertEquals("RANGE key attributes createdAt must have equality conditions specified in the query "
                + "because a condition is present on key attribute sequence", error.getMessage());
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
    void rejectsMalformedKeyAndFilterExpressions() {
        AwsException keyError = assertThrows(AwsException.class,
                () -> validateExpression(tablePath, "pk ="));
        assertEquals("Invalid KeyConditionExpression: Syntax error; token: \"<EOF>\", near: \"=\"", keyError.getMessage());

        AwsException filterError = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateQuery(
                        table, tablePath, null, "pk = :pk", "status =", null, null,
                        expressionValues(":pk")));
        assertEquals("Invalid FilterExpression: Syntax error; token: \"<EOF>\", near: \"=\"", filterError.getMessage());
    }

    @Test
    void validatesLegacyKeyConditions() {
        ObjectNode valid = mapper.createObjectNode();
        valid.set("pk", legacyCondition("EQ", attributeValues("p1")));
        valid.set("sk", legacyCondition("BETWEEN", attributeValues("a", "z")));
        assertDoesNotThrow(() -> DynamoDbAccessPathValidator.validateQuery(
                table, tablePath, valid, null, null, null, null, null));

        ObjectNode nonKey = valid.deepCopy();
        nonKey.set("status", legacyCondition("EQ", attributeValues("open")));
        assertThrows(AwsException.class, () -> DynamoDbAccessPathValidator.validateQuery(
                table, tablePath, nonKey, null, null, null, null, null));

        ObjectNode missingPartitionKey = mapper.createObjectNode();
        missingPartitionKey.set("sk", legacyCondition("EQ", attributeValues("a")));
        assertThrows(AwsException.class, () -> DynamoDbAccessPathValidator.validateQuery(
                table, tablePath, missingPartitionKey, null, null, null, null, null));
    }

    @Test
    void validatesLegacyCompositeSortKeyOrder() {
        ObjectNode valid = mapper.createObjectNode();
        valid.set("status", legacyCondition("EQ", attributeValues("open")));
        valid.set("createdAt", legacyCondition("EQ", attributeValues("2026-01-01")));
        valid.set("sequence", legacyCondition("GE", attributeValues("1")));
        assertDoesNotThrow(() -> DynamoDbAccessPathValidator.validateQuery(
                table, gsiPath, valid, null, null, null, null, null));

        ObjectNode skipped = mapper.createObjectNode();
        skipped.set("status", legacyCondition("EQ", attributeValues("open")));
        skipped.set("sequence", legacyCondition("EQ", attributeValues("1")));
        AwsException skippedError = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateQuery(
                        table, gsiPath, skipped, null, null, null, null, null));
        assertEquals("RANGE key attributes createdAt must have equality conditions specified in the query "
                + "because a condition is present on key attribute sequence", skippedError.getMessage());

        ObjectNode nonFinalInequality = valid.deepCopy();
        nonFinalInequality.set("createdAt", legacyCondition("GT", attributeValues("2026-01-01")));
        assertThrows(AwsException.class, () -> DynamoDbAccessPathValidator.validateQuery(
                table, gsiPath, nonFinalInequality, null, null, null, null, null));
    }

    @Test
    void rejectsSelectedKeysInQueryFilters() {
        assertThrows(AwsException.class, () -> DynamoDbAccessPathValidator.validateQuery(
                table, gsiPath, null, "status = :status", "createdAt > :cutoff", null, null,
                expressionValues(":status", ":cutoff")));

        ObjectNode queryFilter = mapper.createObjectNode();
        queryFilter.set("status", legacyCondition("EQ", attributeValues("open")));
        assertThrows(AwsException.class, () -> DynamoDbAccessPathValidator.validateQuery(
                table, gsiPath, null, "status = :status", null, queryFilter, null,
                expressionValues(":status")));
    }

    @Test
    void rejectsKeyConditionValuesWithWrongSchemaTypes() {
        ObjectNode values = expressionValues(":pk", ":sk");
        values.set(":pk", numberValue("1"));

        AwsException expressionError = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateQuery(
                        table, tablePath, null, "pk = :pk AND sk > :sk",
                        null, null, null, values));
        assertEquals("One or more parameter values were invalid: "
                + "Condition parameter type does not match schema type", expressionError.getMessage());

        ObjectNode legacy = mapper.createObjectNode();
        legacy.set("pk", legacyCondition("EQ", attributeValues("p1")));
        legacy.set("sk", legacyCondition("EQ", mapper.createArrayNode().add(numberValue("1"))));
        assertThrows(AwsException.class, () -> DynamoDbAccessPathValidator.validateQuery(
                table, tablePath, legacy, null, null, null, null, null));
    }

    @Test
    void rejectsMalformedKeyConditionValues() {
        ObjectNode nullString = mapper.createObjectNode();
        nullString.putNull("S");
        ObjectNode values = expressionValues(":pk");
        values.set(":pk", nullString);

        assertThrows(AwsException.class, () -> DynamoDbAccessPathValidator.validateQuery(
                table, tablePath, null, "pk = :pk", null, null, null, values));

        ObjectNode multipleTypes = stringValue("p1");
        multipleTypes.put("N", "1");
        values.set(":pk", multipleTypes);

        assertThrows(AwsException.class, () -> DynamoDbAccessPathValidator.validateQuery(
                table, tablePath, null, "pk = :pk", null, null, null, values));
    }

    @Test
    void rejectsMissingKeyConditionExpressionValue() {
        ObjectNode values = expressionValues(":pk");

        AwsException error = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateQuery(
                        table, tablePath, null, "pk = :pk AND sk > :sk",
                        null, null, null, values));

        assertEquals("Invalid KeyConditionExpression: An expression attribute value used in expression "
                + "is not defined; attribute value: :sk", error.getMessage());
    }

    @Test
    void rejectsMissingAttributeDefinition() {
        table.setAttributeDefinitions(null);

        AwsException error = assertThrows(AwsException.class,
                () -> validateExpression(tablePath, "pk = :pk"));

        assertEquals("ValidationException", error.getErrorCode());
        assertEquals("One or more parameter values were invalid: "
                + "Condition parameter type does not match schema type", error.getMessage());
    }

    @Test
    void enforcesGsiProjectionButAllowsLsiTableFetches() {
        ObjectNode names = mapper.createObjectNode();
        names.put("#summary", "summary");

        assertDoesNotThrow(() -> DynamoDbAccessPathValidator.validateGsiProjection(
                table, gsiPath, "SPECIFIC_ATTRIBUTES", "pk, status, #summary",
                null, names));
        AwsException projectionError = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateGsiProjection(
                        table, gsiPath, "SPECIFIC_ATTRIBUTES", "zeta, details",
                        null, null));
        assertEquals("One or more parameter values were invalid: Global secondary index "
                + "status-index does not project [zeta, details]", projectionError.getMessage());
        AwsException allAttributes = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateGsiProjection(
                        table, gsiPath, "ALL_ATTRIBUTES", null, null, null));
        assertEquals("One or more parameter values were invalid: Select type ALL_ATTRIBUTES is not supported "
                + "for global secondary index status-index because its projection type is not ALL",
                allAttributes.getMessage());
        assertDoesNotThrow(() -> DynamoDbAccessPathValidator.validateGsiProjection(
                table, lsiPath, "ALL_ATTRIBUTES", null, null, null));
    }

    @Test
    void listsTheUnprojectedGsiAttributesOnceInRequestOrder() {
        ObjectNode names = mapper.createObjectNode();
        names.put("#q", "qq");

        AwsException error = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateGsiProjection(
                        table, gsiPath, null, "zz.a, #q, zz.b, aa[0], pk", null, names));
        assertEquals("One or more parameter values were invalid: Global secondary index "
                + "status-index does not project [zz, qq, aa]", error.getMessage());
    }

    @Test
    void rejectsTableAllProjectedAttributesAndInvalidSelectProjectionCombination() {
        assertThrows(AwsException.class, () -> DynamoDbAccessPathValidator.validateSelection(
                "ALL_PROJECTED_ATTRIBUTES", null, null, null));
        assertThrows(AwsException.class, () -> DynamoDbAccessPathValidator.validateSelection(
                "COUNT", null, "pk", null));
    }

    @Test
    void acceptsAKeyConditionWithTheValueOnTheLeft() {
        assertDoesNotThrow(() -> validateExpression(tablePath, "pk = :pk AND :lo <= sk"));
        assertDoesNotThrow(() -> validateExpression(tablePath, "pk = :pk AND :hi > sk"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            pk.value = :pk                                 | cannot have conditions on nested attributes
            pk = :pk AND #sk.foo = :v                      | cannot have conditions on nested attributes
            pk = :pk AND x[0] = :v                         | cannot have conditions on nested attributes
            pk = :pk AND x.y BETWEEN :a AND :b             | cannot have conditions on nested attributes
            pk = :pk AND begins_with(x.y, :v)              | cannot have conditions on nested attributes
            pk = :pk AND x.y = :v AND sk > :a AND sk < :b  | cannot have conditions on nested attributes
            pk = :pk AND sk > :a AND sk < :b AND x.y = :v  | must only contain one condition per key
            pk = :pk AND #sk > :a AND sk < :b              | must only contain one condition per key
            pk = :pk AND :a < sk AND sk < :b               | must only contain one condition per key
            x = :a AND x = :b AND pk = :pk                 | must only contain one condition per key
            pk = :pk AND pk = :pk                          | must only contain one condition per key
            pk = :pk AND (sk > :a AND x.y = :v)            | cannot have conditions on nested attributes
            pk = :pk AND (sk > :a AND sk < :b)             | must only contain one condition per key
            (pk = :pk AND sk > :a) AND sk < :b             | must only contain one condition per key
            pk = :pk AND (sk > :a AND (sk < :b))           | must only contain one condition per key
            """)
    void refusesANestedOrRepeatedAttributeInAKeyCondition(String expression, String error) {
        ObjectNode names = mapper.createObjectNode();
        names.put("#sk", "sk");

        AwsException thrown = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateKeyConditionAttributes(expression, names));

        assertEquals("Invalid KeyConditionExpression: KeyConditionExpressions " + error, thrown.getMessage());
    }

    @Test
    void acceptsANameThatHoldsADotInAKeyCondition() {
        ObjectNode names = mapper.createObjectNode();
        names.put("#a", "x.y");

        assertDoesNotThrow(() -> DynamoDbAccessPathValidator.validateKeyConditionAttributes(
                "pk = :pk AND #a = :v", names));
    }

    @Test
    void reportsTheMissingKeySchemaElementForANonKeyAttribute() {
        AwsException error = assertThrows(AwsException.class,
                () -> validateExpression(tablePath, "attr1 = :v"));

        assertEquals("Query condition missed key schema element: pk", error.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                                 | pk = :pk AND sk > :a AND x = :b                             | 3 | 2
                                 | x = :a AND y = :b AND z = :c                                | 3 | 2
                                 | pk > :pk AND sk > :a AND x = :b                             | 3 | 2
                                 | pk = :pk AND (sk > :a AND x = :b)                           | 3 | 2
            alternate-index      | pk = :pk AND alternate = :a AND sk = :b                     | 3 | 2
            tenant-region-index  | tenantId = :t AND region = :r AND createdAt = :c AND x = :x | 4 | 3
            """)
    void refusesMoreKeyConditionsThanTheKeySchemaHas(String indexName, String expression,
                                                     int conditions, int keyAttributes) {
        AwsException error = assertThrows(AwsException.class,
                () -> validateExpression(DynamoDbAccessPath.resolve(table, indexName), expression));

        assertEquals("The number of query conditions (" + conditions + ") exceeds the number of key attributes "
                + "defined in the schema (" + keyAttributes + ")", error.getMessage());
    }

    @Test
    void countsKeyConditionsBeforeCheckingTheirValueTypes() {
        ObjectNode values = expressionValues(":pk", ":sk", ":v");
        values.set(":pk", numberValue("1"));
        AwsException expressionError = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateQuery(
                        table, tablePath, null, "pk = :pk AND sk > :sk AND status = :v",
                        null, null, null, values));

        ObjectNode legacy = mapper.createObjectNode();
        legacy.set("pk", legacyCondition("EQ", attributeValues("p1")));
        legacy.set("sk", legacyCondition("EQ", mapper.createArrayNode().add(numberValue("1"))));
        legacy.set("status", legacyCondition("EQ", attributeValues("open")));
        AwsException legacyError = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateQuery(
                        table, tablePath, legacy, null, null, null, null, null));

        String message = "The number of query conditions (3) exceeds the number of key attributes "
                + "defined in the schema (2)";
        assertEquals(message, expressionError.getMessage());
        assertEquals(message, legacyError.getMessage());
    }

    @Test
    void stillRejectsANonKeyAttributeAlongsideTheFullKey() {
        AwsException error = assertThrows(AwsException.class,
                () -> validateExpression(tablePath, "pk = :pk AND attr1 = :v"));

        assertEquals("Query key condition not supported", error.getMessage());
    }

    @Test
    void namesTheSelectValueWhenRejectingAProjectionExpression() {
        AwsException allAttributes = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateSelection(
                        "ALL_ATTRIBUTES", null, "sk", null));
        assertEquals("1 validation error detected: "
                + "Cannot specify the ProjectionExpression when choosing to get ALL_ATTRIBUTES",
                allAttributes.getMessage());

        AwsException count = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateSelection(
                        "COUNT", null, "sk", null));
        assertEquals("1 validation error detected: "
                + "Cannot specify the ProjectionExpression when choosing to get only the Count",
                count.getMessage());
    }

    @Test
    void reportsTheProjectionExpressionConflictBeforeTheMissingIndex() {
        AwsException both = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateSelection(
                        "ALL_PROJECTED_ATTRIBUTES", null, "sk", null));
        assertEquals("1 validation error detected: "
                + "Cannot specify the ProjectionExpression when choosing to get ALL_PROJECTED_ATTRIBUTES",
                both.getMessage());

        AwsException indexOnly = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateSelection(
                        "ALL_PROJECTED_ATTRIBUTES", null, null, null));
        assertEquals("1 validation error detected: "
                + "ALL_PROJECTED_ATTRIBUTES can be used only when Querying using an IndexName",
                indexOnly.getMessage());
    }

    @Test
    void queryNamesAnEmptyStartKeyValueInKeySchemaOrder() {
        AwsException tableKey = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateExclusiveStartKey(
                        mapper.readTree("""
                                {"sk": {"S": ""}, "pk": {"S": ""}, "other": {"S": "x"}}
                                """), table, tablePath));
        assertEquals("One or more parameter values are not valid. The AttributeValue for a key attribute "
                + "cannot contain an empty string value. Key: pk", tableKey.getMessage());

        AwsException indexKey = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateExclusiveStartKey(
                        mapper.readTree("""
                                {"pk": {"S": ""}, "sk": {"S": "a"}, "status": {"S": ""},
                                 "createdAt": {"S": "a"}, "sequence": {"S": "a"}}
                                """), table, gsiPath));
        assertEquals("One or more parameter values are not valid. The AttributeValue for a key attribute "
                + "cannot contain an empty string value. Key: status", indexKey.getMessage());
    }

    @Test
    void queryReportsAWrongStartKeyTypeBeforeALaterEmptyValue() {
        AwsException error = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateExclusiveStartKey(
                        mapper.readTree("""
                                {"pk": {"N": "1"}, "sk": {"S": ""}}
                                """), table, tablePath));
        assertEquals("The provided starting key is invalid", error.getMessage());
    }

    @Test
    void scanNamesTheFirstIndexByNameForABadTableKey() {
        AwsException empty = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateScanExclusiveStartKey(
                        mapper.readTree("""
                                {"pk": {"S": ""}, "sk": {"S": "a"}}
                                """), table, tablePath));
        assertEquals("One or more parameter values are not valid. A value specified for a secondary index key "
                + "is not supported. The AttributeValue for a key attribute cannot contain an empty string value. "
                + "IndexName: alternate-index, IndexKey: pk", empty.getMessage());

        AwsException mismatch = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateScanExclusiveStartKey(
                        mapper.readTree("""
                                {"pk": {"S": ""}, "sk": {"BOOL": true}}
                                """), table, tablePath));
        assertEquals("One or more parameter values were invalid: Type mismatch for Index Key sk "
                + "Expected: S Actual: BOOL IndexName: alternate-index", mismatch.getMessage());
    }

    @Test
    void scanNamesTheIndexThatOwnsABadIndexKey() {
        AwsException error = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateScanExclusiveStartKey(
                        mapper.readTree("""
                                {"pk": {"S": "a"}, "sk": {"S": "a"}, "tenantId": {"S": ""}}
                                """), table, tablePath));
        assertEquals("One or more parameter values are not valid. A value specified for a secondary index key "
                + "is not supported. The AttributeValue for a key attribute cannot contain an empty string value. "
                + "IndexName: tenant-region-index, IndexKey: tenantId", error.getMessage());
    }

    @Test
    void scanOfAnIndexChecksOnlyThatIndex() {
        AwsException error = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateScanExclusiveStartKey(
                        mapper.readTree("""
                                {"pk": {"S": "a"}, "sk": {"S": "a"}, "status": {"S": "a"},
                                 "createdAt": {"S": "a"}, "sequence": {"S": "a"}, "alternate": {"S": ""}}
                                """), table, gsiPath));
        assertEquals("The provided starting key is invalid", error.getMessage());
    }

    @Test
    void anEmptyIndexKeyGetsTheKeyWordingFromQueryAndTheIndexWordingFromScan() throws Exception {
        JsonNode startKey = mapper.readTree("""
                {"pk": {"S": "a"}, "sk": {"S": "a"}, "alternate": {"S": ""}}
                """);

        AwsException query = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateExclusiveStartKey(startKey, table, lsiPath));
        assertEquals("One or more parameter values are not valid. The AttributeValue for a key attribute "
                + "cannot contain an empty string value. Key: alternate", query.getMessage());

        AwsException scan = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateScanExclusiveStartKey(startKey, table, lsiPath));
        assertEquals("One or more parameter values are not valid. A value specified for a secondary index key "
                + "is not supported. The AttributeValue for a key attribute cannot contain an empty string value. "
                + "IndexName: alternate-index, IndexKey: alternate", scan.getMessage());
    }

    @Test
    void scanOfATableWithoutIndexesUsesTheKeyWording() {
        TableDefinition plain = new TableDefinition();
        plain.setKeySchema(List.of(
                new KeySchemaElement("pk", "HASH"),
                new KeySchemaElement("sk", "RANGE")));
        plain.setAttributeDefinitions(List.of(
                new AttributeDefinition("pk", "S"),
                new AttributeDefinition("sk", "B")));

        AwsException error = assertThrows(AwsException.class,
                () -> DynamoDbAccessPathValidator.validateScanExclusiveStartKey(
                        mapper.readTree("""
                                {"pk": {"S": "a"}, "sk": {"B": ""}}
                                """), plain, DynamoDbAccessPath.resolve(plain, null)));
        assertEquals("One or more parameter values are not valid. The AttributeValue for a key attribute "
                + "cannot contain an empty binary value. Key: sk", error.getMessage());
    }

    private void validateExpression(DynamoDbAccessPath accessPath, String expression) {
        DynamoDbAccessPathValidator.validateQuery(
                table, accessPath, null, expression, null, null, null,
                expressionValues(extractPlaceholders(expression)));
    }

    private ObjectNode expressionValues(String... placeholders) {
        ObjectNode values = mapper.createObjectNode();
        for (String placeholder : placeholders) {
            values.set(placeholder, stringValue(placeholder.substring(1)));
        }
        return values;
    }

    private String[] extractPlaceholders(String expression) {
        return Pattern.compile(":\\w+")
                .matcher(expression)
                .results()
                .map(MatchResult::group)
                .distinct()
                .toArray(String[]::new);
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
            result.add(stringValue(value));
        }
        return result;
    }

    private ObjectNode stringValue(String value) {
        return mapper.createObjectNode().put("S", value);
    }

    private ObjectNode numberValue(String value) {
        return mapper.createObjectNode().put("N", value);
    }
}
