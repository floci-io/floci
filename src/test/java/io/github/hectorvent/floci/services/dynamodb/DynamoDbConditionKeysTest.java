package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamoDbConditionKeysTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private TableDefinition table;

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
    void getItemExposesTheKeyValueAndTheKeyAttributeNames() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:GetItem",
                json("""
                    {"TableName":"FgacTable",
                     "Key":{"PK":{"S":"USER_alice"},"SK":{"S":"profile"}}}"""),
                table);

        assertEquals(List.of("USER_alice"), result.leadingKeys());
        assertEquals(List.of("PK", "SK"), result.attributes());
        assertNull(result.select());
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
}
