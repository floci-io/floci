package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.LocalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamoDbConditionKeysTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private TableDefinition table;

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "SELECT payload FROM FgacTable | payload",
            "SELECT PK FROM FgacTable WHERE payload = 'secret' | PK,payload",
            "SELECT document.child FROM FgacTable WHERE NOT (hidden[0] = 1 OR PK = 'x') | document,hidden,PK",
            "SELECT PK FROM FgacTable WHERE size(payload) > 1 | PK,payload",
            "SELECT PK FROM FgacTable ORDER BY sortKey | PK,sortKey",
            "SELECT PK FROM FgacTable WHERE PK = ? | PK"
    })
    void partiqlAttributesIncludeSelectedAndPredicatePaths(String statement, String expected) {
        JsonNode body = mapper.createObjectNode().put("Statement", statement)
                .set("Parameters", statement.contains("?") ? json("[{\"S\":\"one\"}]") : json("[]"));
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract("dynamodb:PartiQLSelect", body, table);
        assertEquals(List.of(expected.split(",")), result.attributes());
        assertEquals("SPECIFIC_ATTRIBUTES", result.select());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "GetItem | {} | ALL_ATTRIBUTES",
            "GetItem | {\"ProjectionExpression\":\"PK\"} | SPECIFIC_ATTRIBUTES",
            "BatchGetItem | {\"RequestItems\":{\"FgacTable\":{\"Keys\":[]}}} | ALL_ATTRIBUTES",
            "BatchGetItem | {\"RequestItems\":{\"FgacTable\":{\"Keys\":[],\"AttributesToGet\":[\"PK\"]}}} | SPECIFIC_ATTRIBUTES",
            "PartiQLSelect | {\"Statement\":\"SELECT * FROM FgacTable\"} | ALL_ATTRIBUTES",
            "PartiQLSelect | {\"Statement\":\"SELECT PK FROM FgacTable\"} | SPECIFIC_ATTRIBUTES",
            "Query | {} | ALL_ATTRIBUTES",
            "Scan | {} | ALL_ATTRIBUTES",
            "Query | {\"IndexName\":\"by-value\"} | ALL_PROJECTED_ATTRIBUTES",
            "Scan | {\"IndexName\":\"by-value\"} | ALL_PROJECTED_ATTRIBUTES",
            "Query | {\"ProjectionExpression\":\"PK\"} | SPECIFIC_ATTRIBUTES",
            "Scan | {\"ProjectionExpression\":\"PK\"} | SPECIFIC_ATTRIBUTES",
            "Query | {\"AttributesToGet\":[\"PK\"]} | SPECIFIC_ATTRIBUTES",
            "Scan | {\"AttributesToGet\":[\"PK\"]} | SPECIFIC_ATTRIBUTES",
            "Query | {\"Select\":\"COUNT\",\"ProjectionExpression\":\"PK\"} | COUNT",
            "Scan | {\"Select\":\"ALL_ATTRIBUTES\",\"IndexName\":\"by-value\"} | ALL_ATTRIBUTES",
            "Query | {\"Select\":\"invalid\"} | invalid"
    })
    void selectionDefaultsMatchTheReadShape(String action, String body, String expected) {
        assertEquals(expected, DynamoDbConditionKeys.extract("dynamodb:" + action, json(body), table).select());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{} | ALL_ATTRIBUTES",
            "{\"Count\":false} | ALL_ATTRIBUTES",
            "{\"AttributesToGet\":[\"PK\"]} | SPECIFIC_ATTRIBUTES",
            "{\"Count\":true} | COUNT",
            "{\"Count\":true,\"AttributesToGet\":[\"PK\"]} | COUNT",
            "{\"Select\":\"COUNT\",\"ProjectionExpression\":\"PK\",\"IndexName\":\"ignored\"} | ALL_ATTRIBUTES"
    })
    void legacySelectionUsesOnlyMembersForwardedByTheAdapter(String body, String expected) {
        assertEquals(expected, DynamoDbConditionKeys.extractLegacy("dynamodb:Query", json(body), table).select());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Query | {\"ProjectionExpression\":\"\"} | ALL_ATTRIBUTES",
            "Scan | {\"ProjectionExpression\":\"   \"} | ALL_ATTRIBUTES",
            "Query | {\"AttributesToGet\":[]} | ALL_ATTRIBUTES",
            "Scan | {\"AttributesToGet\":[]} | ALL_ATTRIBUTES",
            "Query | {\"ProjectionExpression\":null} | ALL_ATTRIBUTES",
            "Scan | {\"AttributesToGet\":null} | ALL_ATTRIBUTES",
            "Query | {\"IndexName\":\"by-value\",\"ProjectionExpression\":\"\"} | ALL_PROJECTED_ATTRIBUTES",
            "Scan | {\"IndexName\":\"by-value\",\"AttributesToGet\":[]} | ALL_PROJECTED_ATTRIBUTES",
            "Query | {\"ProjectionExpression\":\"PK\"} | SPECIFIC_ATTRIBUTES",
            "Scan | {\"AttributesToGet\":[\"PK\"]} | SPECIFIC_ATTRIBUTES",
            "Query | {\"Select\":\"COUNT\",\"AttributesToGet\":[]} | COUNT"
    })
    void emptyProjectionMembersRetainTheDefaultSelection(String action, String body, String expected) {
        assertEquals(expected, DynamoDbConditionKeys.extract("dynamodb:" + action, json(body), table).select());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"AttributesToGet\":[]} | ALL_ATTRIBUTES",
            "{\"AttributesToGet\":null} | ALL_ATTRIBUTES",
            "{\"AttributesToGet\":[\"PK\"]} | SPECIFIC_ATTRIBUTES",
            "{\"Count\":true,\"AttributesToGet\":[]} | COUNT"
    })
    void legacyEmptyProjectionDoesNotClaimSpecificAttributes(String body, String expected) {
        assertEquals(expected, DynamoDbConditionKeys.extractLegacy("dynamodb:Query", json(body), table).select());
    }

    @BeforeEach
    void setUp() {
        table = new TableDefinition();
        table.setTableName("FgacTable");
        table.setKeySchema(List.of(
                new KeySchemaElement("PK", "HASH"),
                new KeySchemaElement("SK", "RANGE")));
    }

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void legacyPartitionValuesRemainExplicitWhenTableMetadataIsUnavailable() {
        DynamoDbConditionKeys.Result query = DynamoDbConditionKeys.extractLegacy("dynamodb:Query",
                json("{\"TableName\":\"missing\",\"HashKeyValue\":{\"S\":\"USER_bob\"}}"), null);
        assertEquals(List.of("USER_bob"), query.leadingKeys());
        assertTrue(query.attributes().isEmpty());
        DynamoDbConditionKeys.Result deletes = DynamoDbConditionKeys.extractLegacy("dynamodb:BatchWriteItem",
                json("""
                        {"RequestItems":{"missing":[{"DeleteRequest":{"Key":{
                          "HashKeyElement":{"S":"USER_bob"},"RangeKeyElement":{"S":"profile"}}}}]}}
                        """), null);
        assertEquals(List.of("USER_bob"), deletes.leadingKeys());
        assertTrue(deletes.attributes().isEmpty());
    }

    @Test
    void getItemExposesTheKeyValueAndTheKeyAttributeNames() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:GetItem",
                json("""
                    {"TableName":"FgacTable",
                     "Key":{"PK":{"S":"USER_alice"},"SK":{"S":"profile"}}}"""),
                table);

        assertEquals(List.of("USER_alice"), result.leadingKeys());
        assertEquals(List.of("PK", "SK"), result.attributes());
        assertEquals("ALL_ATTRIBUTES", result.select());
    }

    @Test
    void putItemExposesTheItemPartitionKeyAndEveryItemAttribute() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:PutItem",
                json("""
                    {"TableName":"FgacTable",
                     "Item":{"PK":{"S":"USER_alice"},"SK":{"S":"profile"},"email":{"S":"a@b.c"}}}"""),
                table);

        assertEquals(List.of("USER_alice"), result.leadingKeys());
        assertEquals(List.of("PK", "SK", "email"), result.attributes());
    }

    @Test
    void updateItemExposesTheKeyValueAndTheUpdateExpressionTargets() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:UpdateItem",
                json("""
                    {"TableName":"FgacTable",
                      "Key":{"PK":{"S":"USER_alice"},"SK":{"S":"profile"}},
                     "UpdateExpression":"SET #e = :e REMOVE nickname",
                     "ExpressionAttributeNames":{"#e":"email"},
                     "ExpressionAttributeValues":{":e":{"S":"a@b.c"}}}"""),
                table);

        assertEquals(List.of("USER_alice"), result.leadingKeys());
        assertTrue(result.attributes().contains("PK"), result.attributes().toString());
        assertTrue(result.attributes().contains("email"), result.attributes().toString());
        assertTrue(result.attributes().contains("nickname"), result.attributes().toString());
    }

    @Test
    void deleteItemExposesTheKeyValue() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:DeleteItem",
                json("""
                    {"TableName":"FgacTable","Key":{"PK":{"S":"USER_bob"},"SK":{"S":"profile"}}}"""),
                table);

        assertEquals(List.of("USER_bob"), result.leadingKeys());
    }

    @Test
    void queryResolvesTheLeadingKeyFromTheKeyConditionExpressionAndCarriesSelect() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:Query",
                json("""
                    {"TableName":"FgacTable",
                     "KeyConditionExpression":"PK = :v AND SK > :s",
                     "ExpressionAttributeValues":{":v":{"S":"USER_alice"},":s":{"S":"2020"}},
                     "ProjectionExpression":"email, #n",
                     "ExpressionAttributeNames":{"#n":"nickname"},
                     "Select":"SPECIFIC_ATTRIBUTES"}"""),
                table);

        assertEquals(List.of("USER_alice"), result.leadingKeys());
        assertEquals("SPECIFIC_ATTRIBUTES", result.select());
        assertTrue(result.attributes().contains("email"), result.attributes().toString());
        assertTrue(result.attributes().contains("nickname"), result.attributes().toString());
    }

    @Test
    void queryResolvesAnAliasedPartitionKey() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:Query",
                json("""
                    {"TableName":"FgacTable",
                     "KeyConditionExpression":"#p = :v",
                     "ExpressionAttributeNames":{"#p":"PK"},
                     "ExpressionAttributeValues":{":v":{"S":"USER_alice"}}}"""),
                table);

        assertEquals(List.of("USER_alice"), result.leadingKeys());
    }

    @Test
    void batchGetItemExposesEveryRequestedKey() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:BatchGetItem",
                json("""
                    {"RequestItems":{"FgacTable":{"Keys":[
                       {"PK":{"S":"USER_alice"},"SK":{"S":"a"}},
                       {"PK":{"S":"USER_alice_2"},"SK":{"S":"b"}},
                       {"PK":{"S":"USER_bob"},"SK":{"S":"c"}}]}}}"""),
                table);

        assertEquals(List.of("USER_alice", "USER_alice_2", "USER_bob"), result.leadingKeys());
    }

    @Test
    void batchWriteItemExposesPutAndDeleteKeys() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:BatchWriteItem",
                json("""
                    {"RequestItems":{"FgacTable":[
                       {"PutRequest":{"Item":{"PK":{"S":"USER_alice"},"SK":{"S":"a"}}}},
                       {"DeleteRequest":{"Key":{"PK":{"S":"USER_bob"},"SK":{"S":"b"}}}}]}}"""),
                table);

        assertEquals(List.of("USER_alice", "USER_bob"), result.leadingKeys());
    }

    @Test
    void nullTableYieldsNoLeadingKeysAndDoesNotThrow() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:GetItem",
                json("""
                    {"TableName":"FgacTable","Key":{"PK":{"S":"USER_alice"}}}"""),
                null);

        assertTrue(result.leadingKeys().isEmpty());
        assertEquals(List.of("PK"), result.attributes());
    }

    @Test
    void aKeyMissingThePartitionAttributeYieldsNoLeadingKeys() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:GetItem",
                json("""
                    {"TableName":"FgacTable","Key":{"SK":{"S":"profile"}}}"""),
                table);

        assertTrue(result.leadingKeys().isEmpty());
    }

    @Test
    void nullBodyYieldsAnEmptyResult() {
        DynamoDbConditionKeys.Result result =
                DynamoDbConditionKeys.extract("dynamodb:GetItem", null, table);

        assertTrue(result.leadingKeys().isEmpty());
        assertTrue(result.attributes().isEmpty());
        assertNull(result.select());
    }

    @Test
    void queryCollectsLiteralAttributeNamesFromAFilterExpression() {
        // A projection of only allowed attributes must not hide a filter on a forbidden one.
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:Query",
                json("""
                    {"TableName":"FgacTable",
                     "KeyConditionExpression":"PK = :v",
                     "ExpressionAttributeValues":{":v":{"S":"USER_alice"},":x":{"S":"y"}},
                     "FilterExpression":"ssn = :x",
                     "ProjectionExpression":"email"}"""),
                table);

        assertTrue(result.attributes().contains("ssn"), result.attributes().toString());
        assertTrue(result.attributes().contains("email"), result.attributes().toString());
        assertTrue(result.attributes().contains("PK"), result.attributes().toString());
    }

    @Test
    void getItemLeavesAProjectionWithAListIndexRootUnresolved() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:GetItem",
                json("""
                    {"TableName":"FgacTable",
                     "Key":{"PK":{"S":"USER_alice"},"SK":{"S":"profile"}},
                     "ProjectionExpression":"email, [0]"}"""),
                table);

        assertEquals(List.of("PK", "SK"), result.attributes());
    }

    @Test
    void putItemCollectsLiteralAttributeNamesFromAConditionExpression() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:PutItem",
                json("""
                    {"TableName":"FgacTable",
                     "Item":{"PK":{"S":"USER_alice"},"SK":{"S":"profile"}},
                     "ConditionExpression":"attribute_not_exists(locked)"}"""),
                table);

        assertTrue(result.attributes().contains("locked"), result.attributes().toString());
    }

    @Test
    void queryResolvesTheLeadingKeyFromALegacyKeyConditionsMap() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:Query",
                json("""
                    {"TableName":"FgacTable",
                     "KeyConditions":{"PK":{"ComparisonOperator":"EQ",
                        "AttributeValueList":[{"S":"USER_alice"}]}}}"""),
                table);

        assertEquals(List.of("USER_alice"), result.leadingKeys());
        assertTrue(result.attributes().contains("PK"), result.attributes().toString());
    }

    @Test
    void attributesToGetAreExposed() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:GetItem",
                json("""
                    {"TableName":"FgacTable","Key":{"PK":{"S":"USER_alice"}},
                     "AttributesToGet":["email","nickname"]}"""),
                table);

        assertTrue(result.attributes().contains("email"), result.attributes().toString());
        assertTrue(result.attributes().contains("nickname"), result.attributes().toString());
    }

    @Test
    void queryAgainstGsiResolvesGsiPartitionKeyAsLeadingKey() {
        GlobalSecondaryIndex gsi = new GlobalSecondaryIndex();
        gsi.setIndexName("EmailGSI");
        gsi.setKeySchema(List.of(
                new KeySchemaElement("GSI_PK", "HASH"),
                new KeySchemaElement("GSI_SK", "RANGE")));
        table.setGlobalSecondaryIndexes(List.of(gsi));

        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:Query",
                json("""
                    {"TableName":"FgacTable",
                     "IndexName":"EmailGSI",
                     "KeyConditionExpression":"GSI_PK = :email",
                     "ExpressionAttributeValues":{":email":{"S":"alice@example.com"}}}"""),
                table);

        assertEquals(List.of("alice@example.com"), result.leadingKeys());
        assertTrue(result.attributes().contains("GSI_PK"), result.attributes().toString());
    }

    @Test
    void queryAgainstLsiResolvesTablePartitionKeyAsLeadingKey() {
        LocalSecondaryIndex lsi = new LocalSecondaryIndex();
        lsi.setIndexName("CreatedLSI");
        lsi.setKeySchema(List.of(
                new KeySchemaElement("PK", "HASH"),
                new KeySchemaElement("CreatedAt", "RANGE")));
        table.setLocalSecondaryIndexes(List.of(lsi));

        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:Query",
                json("""
                    {"TableName":"FgacTable",
                     "IndexName":"CreatedLSI",
                     "KeyConditionExpression":"PK = :pk",
                     "ExpressionAttributeValues":{":pk":{"S":"USER_alice"}}}"""),
                table);

        assertEquals(List.of("USER_alice"), result.leadingKeys());
        assertTrue(result.attributes().contains("PK"), result.attributes().toString());
    }

    @Test
    void updateItemExposesReadOperandsAndTargetsFromUpdateExpression() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:UpdateItem",
                json("""
                    {"TableName":"FgacTable",
                     "Key":{"PK":{"S":"USER_alice"},"SK":{"S":"profile"}},
                     "UpdateExpression":"SET total = price + :tax, history = list_append(oldHistory, :entry), city = if_not_exists(fallbackCity, :def) REMOVE oldFlag, tags[0] ADD score :pts DELETE categories :cat",
                     "ExpressionAttributeValues":{":tax":{"N":"5"},":entry":{"S":"login"},":def":{"S":"HN"},":pts":{"N":"1"},":cat":{"SS":["old"]}}}"""),
                table);

        assertEquals(List.of("USER_alice"), result.leadingKeys());
        assertTrue(result.attributes().contains("total"), result.attributes().toString());
        assertTrue(result.attributes().contains("price"), result.attributes().toString());
        assertTrue(result.attributes().contains("history"), result.attributes().toString());
        assertTrue(result.attributes().contains("oldHistory"), result.attributes().toString());
        assertTrue(result.attributes().contains("city"), result.attributes().toString());
        assertTrue(result.attributes().contains("fallbackCity"), result.attributes().toString());
        assertTrue(result.attributes().contains("oldFlag"), result.attributes().toString());
        assertTrue(result.attributes().contains("tags"), result.attributes().toString());
        assertTrue(result.attributes().contains("score"), result.attributes().toString());
        assertTrue(result.attributes().contains("categories"), result.attributes().toString());
    }

    @Test
    void updateItemDoesNotExposeNestedAttributeNamesAsTopLevelAttributes() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:UpdateItem",
                json("""
                    {"TableName":"FgacTable",
                     "Key":{"PK":{"S":"USER_alice"}},
                     "UpdateExpression":"SET #a.subfield = :v, nested[0].detail = :d",
                     "ExpressionAttributeNames":{"#a":"actualTop"},
                     "ExpressionAttributeValues":{":v":{"S":"val"},":d":{"S":"detail"}}}"""),
                table);

        assertTrue(result.attributes().contains("actualTop"), result.attributes().toString());
        assertTrue(result.attributes().contains("nested"), result.attributes().toString());
        assertFalse(result.attributes().contains("subfield"), result.attributes().toString());
        assertFalse(result.attributes().contains("detail"), result.attributes().toString());
    }

    @Test
    void legacyAttributeUpdatesAndExpectedAreExposedAsAttributes() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:UpdateItem",
                json("""
                    {"TableName":"FgacTable",
                     "Key":{"PK":{"S":"USER_alice"}},
                     "AttributeUpdates":{"status":{"Action":"PUT","Value":{"S":"active"}}},
                     "Expected":{"version":{"ComparisonOperator":"EQ","AttributeValueList":[{"N":"1"}]}}}"""),
                table);

        assertTrue(result.attributes().contains("status"), result.attributes().toString());
        assertTrue(result.attributes().contains("version"), result.attributes().toString());
    }

    @Test
    void scanExposesLegacyScanFilterAttributes() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:Scan",
                json("""
                    {"TableName":"FgacTable",
                     "ScanFilter":{"inStock":{"ComparisonOperator":"EQ","AttributeValueList":[{"BOOL":true}]}}}"""),
                table);

        assertTrue(result.attributes().contains("inStock"), result.attributes().toString());
    }
}
