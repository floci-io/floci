package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamoDbKeySizeServiceTest {

    private static final String REGION = "us-east-1";
    private DynamoDbService service;

    @BeforeEach
    void setUp() {
        service = new DynamoDbService(new InMemoryStorage<>());
    }

    @ParameterizedTest
    @CsvSource({"S,pk,2048", "S,sk,1024", "B,pk,2048", "B,sk,1024"})
    void acceptsLimitAndRejectsOneByteOver(String type, String keyName, int limit) {
        createTable(type);
        ObjectNode item = key(type, "a", "b");
        item.set(keyName, value(type, "x".repeat(limit)));
        service.putItem("KeySizes", item, REGION);
        assertNotNull(service.getItem("KeySizes", item, REGION));

        ObjectNode oversized = item.deepCopy();
        oversized.set(keyName, value(type, "x".repeat(limit + 1)));
        assertSizeError(assertThrows(AwsException.class,
                () -> service.putItem("KeySizes", oversized, REGION)), keyName, limit);
    }

    @ParameterizedTest
    @CsvSource({"pk,2048", "sk,1024"})
    void measuresStringsInUtf8Bytes(String keyName, int limit) {
        createTable("S");
        ObjectNode item = key("S", "a", "b");
        item.set(keyName, value("S", "\u00e9".repeat(limit / 2)));
        service.putItem("KeySizes", item, REGION);
        assertNotNull(service.getItem("KeySizes", item, REGION));

        item.set(keyName, value("S", "\u00e9".repeat(limit / 2) + "x"));
        assertSizeError(assertThrows(AwsException.class,
                () -> service.putItem("KeySizes", item, REGION)), keyName, limit);
    }

    @Test
    void oversizedKeyArgumentsAreRejected() {
        createTable("S");
        ObjectNode oversized = key("S", "x".repeat(2049), "b");
        assertSizeError(assertThrows(AwsException.class,
                () -> service.getItem("KeySizes", oversized, REGION)), "pk", 2048);
        assertSizeError(assertThrows(AwsException.class,
                () -> service.deleteItem("KeySizes", oversized, REGION)), "pk", 2048);
        assertSizeError(assertThrows(AwsException.class,
                () -> service.updateItem("KeySizes", oversized, null,
                        "SET data = :v", null,
                        JsonNodeFactory.instance.objectNode().set(":v", value("S", "value")),
                        null, REGION)), "pk", 2048);
    }

    @Test
    void oversizedBatchKeyDoesNotPartiallyWrite() {
        createTable("S");
        ObjectNode valid = key("S", "valid", "b");
        ObjectNode oversized = key("S", "x".repeat(2049), "b");
        ObjectNode first = JsonNodeFactory.instance.objectNode();
        first.putObject("PutRequest").set("Item", valid);
        ObjectNode second = JsonNodeFactory.instance.objectNode();
        second.putObject("PutRequest").set("Item", oversized);

        assertSizeError(assertThrows(AwsException.class,
                () -> service.batchWriteItem(Map.of("KeySizes", List.of(first, second)), REGION)), "pk", 2048);
        assertNull(service.getItem("KeySizes", valid, REGION));
    }

    @Test
    void oversizedTransactionKeyDoesNotPartiallyWrite() {
        createTable("S");
        ObjectNode valid = key("S", "valid", "b");
        ObjectNode oversized = key("S", "a", "x".repeat(1025));
        ObjectNode first = JsonNodeFactory.instance.objectNode();
        first.putObject("Put").put("TableName", "KeySizes").set("Item", valid);
        ObjectNode second = JsonNodeFactory.instance.objectNode();
        second.putObject("Put").put("TableName", "KeySizes").set("Item", oversized);

        assertThrows(AwsException.class,
                () -> service.transactWriteItems(List.of(first, second), REGION));
        assertNull(service.getItem("KeySizes", valid, REGION));
    }

    @Test
    void nonKeyValuesCanExceedKeyLimits() {
        createTable("S");
        ObjectNode item = key("S", "a", "b");
        item.set("data", value("S", "x".repeat(2049)));
        service.putItem("KeySizes", item, REGION);
        assertEquals(item, service.getItem("KeySizes", key("S", "a", "b"), REGION));
    }

    private void createTable(String type) {
        service.createTable("KeySizes",
                List.of(new KeySchemaElement("pk", "HASH"), new KeySchemaElement("sk", "RANGE")),
                List.of(new AttributeDefinition("pk", type), new AttributeDefinition("sk", type)),
                5L, 5L, REGION);
    }

    private ObjectNode key(String type, String pk, String sk) {
        ObjectNode item = JsonNodeFactory.instance.objectNode();
        item.set("pk", value(type, pk));
        item.set("sk", value(type, sk));
        return item;
    }

    private ObjectNode value(String type, String text) {
        String encoded = "B".equals(type)
                ? Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) : text;
        return JsonNodeFactory.instance.objectNode().put(type, encoded);
    }

    private void assertSizeError(AwsException error, String keyName, int limit) {
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("One or more parameter values were invalid: Size of "
                + ("pk".equals(keyName) ? "hashkey" : "rangekey")
                + " has exceeded the maximum size limit of " + limit + " bytes", error.getMessage());
    }
}
