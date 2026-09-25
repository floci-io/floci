package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamoDbItemSizeServiceTest {

    private static final String REGION = "us-east-1";
    private static final String TABLE = "ItemSizes";
    private static final int MAX_ITEM_SIZE = 400 * 1024;
    private static final String UPDATE_EXCEEDED =
            "Item size to update has exceeded the maximum allowed size";
    private static final int UPDATE_COST = 3;
    private static final int SET_OR_ADD_COST = 19;
    private static final int REMOVE_OR_DELETE_COST = 2;
    private static final int LIST_INDEX_COST = 1;
    // "pk" plus a one-byte value, short enough that the ceiling falls below 400KB.
    private static final int SHORT_KEY_BYTES = 3;

    private DynamoDbService service;
    private NativeDynamoDbJsonHandler handler;

    @BeforeEach
    void setUp() {
        service = new DynamoDbService(new InMemoryStorage<>());
        handler = new NativeDynamoDbJsonHandler(service, null, null, new ObjectMapper());
        service.createTable(TABLE, List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")), 5L, 5L, REGION);
    }

    @Test
    void updateItemSitsBelowTheLimitAtAShortKey() {
        int ceiling = MAX_ITEM_SIZE + SHORT_KEY_BYTES - (UPDATE_COST + SET_OR_ADD_COST);

        update("a", "SET b = :pad", ceiling, 0);
        assertEquals(ceiling, storedBytes("a"));
        assertRefused(() -> update("b", "SET b = :pad", ceiling + 1, 0));
    }

    @Test
    void updateItemCapsAtFourHundredKilobytesAtALongKey() {
        String longKey = "k".repeat(100);

        update(longKey, "SET b = :pad", MAX_ITEM_SIZE, 0);
        assertEquals(MAX_ITEM_SIZE, storedBytes(longKey));
        assertRefused(() -> update("K".repeat(100), "SET b = :pad", MAX_ITEM_SIZE + 1, 0));
    }

    @Test
    void updateItemChargesNineteenBytesForASecondSetClause() {
        int ceiling = MAX_ITEM_SIZE + SHORT_KEY_BYTES - (UPDATE_COST + SET_OR_ADD_COST * 2);
        ObjectNode second = values(":c", stringValue("y"));

        update("a", "SET b = :pad, c = :c", ceiling, 2, second);
        assertEquals(ceiling, storedBytes("a"));
        assertRefused(() -> update("b", "SET b = :pad, c = :c", ceiling + 1, 2, second));
    }

    @Test
    void updateItemChargesNineteenBytesForAnAdd() {
        int ceiling = MAX_ITEM_SIZE + SHORT_KEY_BYTES - (UPDATE_COST + SET_OR_ADD_COST * 2);
        ObjectNode one = values(":one", numberValue("1"));

        update("a", "SET b = :pad ADD n :one", ceiling, 2, one);
        assertEquals(ceiling, storedBytes("a"));
        assertRefused(() -> update("b", "SET b = :pad ADD n :one", ceiling + 1, 2, one));
    }

    @Test
    void updateItemChargesTwoBytesForARemoveAlongsideASet() {
        int ceiling = MAX_ITEM_SIZE + SHORT_KEY_BYTES
                - (UPDATE_COST + SET_OR_ADD_COST + REMOVE_OR_DELETE_COST);
        seed("a", "r", stringValue("z"));
        seed("b", "r", stringValue("z"));

        update("a", "SET b = :pad REMOVE r", ceiling, 0);
        assertEquals(ceiling, storedBytes("a"));
        assertRefused(() -> update("b", "SET b = :pad REMOVE r", ceiling + 1, 0));
    }

    @Test
    void updateItemChargesTwoBytesForADeleteAlongsideASet() {
        int ceiling = MAX_ITEM_SIZE + SHORT_KEY_BYTES
                - (UPDATE_COST + SET_OR_ADD_COST + REMOVE_OR_DELETE_COST);
        ObjectNode members = values(":members", stringSetValue("z"));
        seed("a", "s", stringSetValue("z"));
        seed("b", "s", stringSetValue("z"));

        update("a", "SET b = :pad DELETE s :members", ceiling, 0, members);
        assertEquals(ceiling, storedBytes("a"));
        assertRefused(() -> update("b", "SET b = :pad DELETE s :members", ceiling + 1, 0, members));
    }

    @Test
    void updateItemChargesADocumentPathLikeAPlainSet() {
        int ceiling = MAX_ITEM_SIZE + SHORT_KEY_BYTES - (UPDATE_COST + SET_OR_ADD_COST);
        int mapBytes = "leaf".length() + 1 + 3;
        seed("a", "d", mapValue("leaf", stringValue("x")));
        seed("b", "d", mapValue("leaf", stringValue("x")));

        update("a", "SET d.leaf = :pad", ceiling, mapBytes);
        assertEquals(ceiling, storedBytes("a"));
        assertRefused(() -> update("b", "SET d.leaf = :pad", ceiling + 1, mapBytes));
    }

    @Test
    void updateItemChargesOneMoreByteThroughAListIndex() {
        int ceiling = MAX_ITEM_SIZE + SHORT_KEY_BYTES
                - (UPDATE_COST + SET_OR_ADD_COST + LIST_INDEX_COST);
        int elementBytes = 3;
        seed("a", "d", listValue(stringValue("x")));
        seed("b", "d", listValue(stringValue("x")));

        update("a", "SET d[0] = :pad", ceiling, elementBytes);
        assertEquals(ceiling, storedBytes("a"));
        assertRefused(() -> update("b", "SET d[0] = :pad", ceiling + 1, elementBytes));
    }

    @Test
    void updateItemChargesAFunctionCallAsOneAction() {
        int ceiling = MAX_ITEM_SIZE + SHORT_KEY_BYTES - (UPDATE_COST + SET_OR_ADD_COST);

        update("a", "SET b = if_not_exists(b, :pad)", ceiling, 0);
        assertEquals(ceiling, storedBytes("a"));
        assertRefused(() -> update("b", "SET b = if_not_exists(b, :pad)", ceiling + 1, 0));
    }

    @Test
    void updateItemChargesALegacyAttributeUpdateLikeASet() {
        int ceiling = MAX_ITEM_SIZE + SHORT_KEY_BYTES - (UPDATE_COST + SET_OR_ADD_COST);

        updateAttribute("a", ceiling);
        assertEquals(ceiling, storedBytes("a"));
        assertRefused(() -> updateAttribute("b", ceiling + 1));
    }

    @Test
    void updateItemDoesNotChargeForAnAttributeItLeavesAlone() {
        int untouchedBytes = 1 + 10; // "u" plus its value
        int ceiling = MAX_ITEM_SIZE + SHORT_KEY_BYTES + untouchedBytes
                - (UPDATE_COST + SET_OR_ADD_COST);
        seed("a", "u", stringValue("y".repeat(10)));
        seed("b", "u", stringValue("y".repeat(10)));

        update("a", "SET b = :pad", ceiling, untouchedBytes);
        assertEquals(ceiling, storedBytes("a"));
        assertRefused(() -> update("b", "SET b = :pad", ceiling + 1, untouchedBytes));
    }

    @Test
    void partiQlUpdateMeasuresTheFinishedItem() throws Exception {
        service.putItem(TABLE, key("a"), REGION);
        service.putItem(TABLE, key("b"), REGION);

        executeUpdateStatement("a", MAX_ITEM_SIZE);
        assertEquals(MAX_ITEM_SIZE, storedBytes("a"));
        assertRefused(() -> executeUpdateStatement("b", MAX_ITEM_SIZE + 1));
    }

    @Test
    void transactedUpdateMeasuresTheFinishedItemAndCancels() {
        service.transactWriteItems(List.of(transactUpdate("a", MAX_ITEM_SIZE)), REGION);
        assertEquals(MAX_ITEM_SIZE, storedBytes("a"));

        TransactionCanceledException cancelled = assertThrows(TransactionCanceledException.class,
                () -> service.transactWriteItems(List.of(transactUpdate("b", MAX_ITEM_SIZE + 1)), REGION));
        assertEquals("ValidationError", cancelled.getCancellationReasons().get(0).code());
        assertEquals(UPDATE_EXCEEDED, cancelled.getCancellationReasons().get(0).message());
        assertNull(service.getItem(TABLE, key("b"), REGION));
    }

    private void update(String keyValue, String expression, int totalBytes, int otherBytes) {
        update(keyValue, expression, totalBytes, otherBytes, JsonNodeFactory.instance.objectNode());
    }

    private void update(String keyValue, String expression, int totalBytes, int otherBytes,
                         ObjectNode extraValues) {
        ObjectNode values = extraValues.deepCopy();
        values.set(":pad", stringValue(padding(keyValue, totalBytes, otherBytes)));
        service.updateItem(TABLE, key(keyValue), null, expression, null, values, "NONE", REGION);
    }

    private void updateAttribute(String keyValue, int totalBytes) {
        ObjectNode updates = JsonNodeFactory.instance.objectNode();
        updates.putObject("b").set("Value", stringValue(padding(keyValue, totalBytes, 0)));
        service.updateItem(TABLE, key(keyValue), updates, null, null, null, "NONE", REGION);
    }

    private void executeUpdateStatement(String keyValue, int totalBytes) throws Exception {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("Statement", "UPDATE \"" + TABLE + "\" SET b = ? WHERE pk = ?");
        ArrayNode parameters = request.putArray("Parameters");
        parameters.add(stringValue(padding(keyValue, totalBytes, 0)));
        parameters.add(stringValue(keyValue));
        handler.handle("ExecuteStatement", request, REGION);
    }

    private JsonNode transactUpdate(String keyValue, int totalBytes) {
        ObjectNode member = JsonNodeFactory.instance.objectNode();
        ObjectNode update = member.putObject("Update");
        update.put("TableName", TABLE);
        update.set("Key", key(keyValue));
        update.put("UpdateExpression", "SET b = :pad");
        update.set("ExpressionAttributeValues",
                values(":pad", stringValue(padding(keyValue, totalBytes, 0))));
        return member;
    }

    private void seed(String keyValue, String attribute, JsonNode value) {
        ObjectNode item = key(keyValue);
        item.set(attribute, value);
        service.putItem(TABLE, item, REGION);
    }

    private String padding(String keyValue, int totalBytes, int otherBytes) {
        return "x".repeat(totalBytes - "pk".length() - keyValue.length() - "b".length() - otherBytes);
    }

    private ObjectNode key(String keyValue) {
        ObjectNode key = JsonNodeFactory.instance.objectNode();
        key.set("pk", stringValue(keyValue));
        return key;
    }

    private ObjectNode values(String placeholder, JsonNode value) {
        ObjectNode values = JsonNodeFactory.instance.objectNode();
        values.set(placeholder, value);
        return values;
    }

    private ObjectNode stringValue(String text) {
        return JsonNodeFactory.instance.objectNode().put("S", text);
    }

    private ObjectNode numberValue(String number) {
        return JsonNodeFactory.instance.objectNode().put("N", number);
    }

    private ObjectNode stringSetValue(String member) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.putArray("SS").add(member);
        return value;
    }

    private ObjectNode mapValue(String attribute, JsonNode value) {
        ObjectNode map = JsonNodeFactory.instance.objectNode();
        map.putObject("M").set(attribute, value);
        return map;
    }

    private ObjectNode listValue(JsonNode element) {
        ObjectNode list = JsonNodeFactory.instance.objectNode();
        list.putArray("L").add(element);
        return list;
    }

    private int storedBytes(String keyValue) {
        JsonNode item = service.getItem(TABLE, key(keyValue), REGION);
        return DynamoDbItemSize.calculateItemSize(item);
    }

    private void assertRefused(Executable body) {
        AwsException refused = assertThrows(AwsException.class, body);
        assertEquals("ValidationException", refused.getErrorCode());
        assertEquals(400, refused.getHttpStatus());
        assertEquals(UPDATE_EXCEEDED, refused.getMessage());
    }
}
