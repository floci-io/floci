package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamoDbLegacyExpectedOperandsTest {
    private static final String REGION = "us-east-1";
    private static final String TABLE = "ExpectedOperands";
    private static final String PREFIX =
            "1 validation error detected: One or more parameter values were invalid: ";
    private final ObjectMapper mapper = new ObjectMapper();
    private DynamoDbService service;
    private NativeDynamoDbJsonHandler handler;
    private ObjectNode seed;
    private ObjectNode key;

    @BeforeEach
    void setUp() throws Exception {
        service = new DynamoDbService(new InMemoryStorage<>());
        handler = new NativeDynamoDbJsonHandler(service, null, null, mapper);
        service.createTable(TABLE, List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")), 5L, 5L, REGION);
        seed = (ObjectNode) mapper.readTree("""
                {"pk":{"S":"row"},"tested":{"S":"READY"},"marker":{"S":"before"}}
                """);
        key = (ObjectNode) mapper.readTree("""
                {"pk":{"S":"row"}}
                """);
        service.putItem(TABLE, seed, REGION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PutItem", "UpdateItem", "DeleteItem"})
    void rejectsMissingConflictingAndExtraOperandsWithoutChangingItem(String operation) throws Exception {
        List<String> conditions = List.of(
                """
                {"ComparisonOperator":"NE"}
                """,
                """
                {"ComparisonOperator":"EQ","Value":{"S":"OTHER"},"AttributeValueList":[{"S":"READY"}]}
                """,
                """
                {"ComparisonOperator":"EQ","AttributeValueList":[{"S":"READY"},{"S":"OTHER"}]}
                """,
                """
                {"ComparisonOperator":"NOT_NULL","Value":{"S":"READY"}}
                """);
        List<String> messages = List.of(
                "Value or AttributeValueList must be used with ComparisonOperator: NE for Attribute: tested",
                "Value and AttributeValueList cannot be used together for Attribute: tested",
                "Invalid number of argument(s) for the EQ ComparisonOperator",
                "Invalid number of argument(s) for the NOT_NULL ComparisonOperator");
        for (int index = 0; index < conditions.size(); index++) {
            ObjectNode request = writeRequest(operation, mapper.readTree(conditions.get(index)));
            AwsException error = assertThrows(AwsException.class,
                    () -> handler.handle(operation, request, REGION));
            assertEquals("ValidationException", error.getErrorCode());
            assertEquals(PREFIX + messages.get(index), error.getMessage());
            assertEquals(seed, service.getItem(TABLE, key, REGION));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PutItem", "UpdateItem", "DeleteItem"})
    void acceptsComparisonOperatorWithValue(String operation) throws Exception {
        ObjectNode request = writeRequest(operation, mapper.readTree("""
                {"ComparisonOperator":"EQ","Value":{"S":"READY"}}
                """));
        assertEquals(200, handler.handle(operation, request, REGION).getStatus());
    }

    private ObjectNode writeRequest(String operation, JsonNode condition) {
        ObjectNode request = mapper.createObjectNode().put("TableName", TABLE);
        request.set("Expected", mapper.createObjectNode().set("tested", condition));
        if ("PutItem".equals(operation)) {
            ObjectNode replacement = seed.deepCopy();
            replacement.set("marker", mapper.createObjectNode().put("S", "after"));
            request.set("Item", replacement);
        } else {
            request.set("Key", key);
            if ("UpdateItem".equals(operation)) {
                ObjectNode update = mapper.createObjectNode().put("Action", "PUT");
                update.set("Value", mapper.createObjectNode().put("S", "after"));
                request.set("AttributeUpdates", mapper.createObjectNode().set("marker", update));
            }
        }
        return request;
    }
}
