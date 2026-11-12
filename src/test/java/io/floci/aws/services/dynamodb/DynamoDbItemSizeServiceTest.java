package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.ConditionalCheckFailedException;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Base64;
import java.util.List;
import java.util.function.IntFunction;

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
    private static final int REMOVE_OR_DELETE_COST = 1;
    private static final int LIST_INDEX_COST = 1;
    private static final int OPERAND_PATH_COST = 1;
    private static final int OPERAND_PATH_ELEMENT_COST = 14;
    private static final int PLUS_COST = 34;
    private static final int MINUS_COST = 37;
    private static final int LIST_APPEND_COST = 37;
    private static final int IF_NOT_EXISTS_COST = 39;
    private static final int CONTAINER_COST = 3;
    private static final int TARGET_CONTAINER_COST = 4;
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

    @ParameterizedTest
    @CsvSource({
            "0, 1", "-0, 1", "1, 2", "12, 2", "123, 3", "1234, 3", "123456, 4",
            "12345678901234567890123456789012345678, 20",
            "0042, 2", "100, 2", "110, 3", "1010, 3", "1100, 2", "11000, 3",
            "0.1, 2", "0.15, 2", "0.015, 3", "0.0015, 2", "0.0000001, 2",
            "1.5, 3", "1.05, 3", "1.200, 3", "3.14159, 5", "100.5, 4", "110.5, 4",
            "1E125, 2", "1E-100, 2", "1.1E2, 3", "-42, 3", "-110, 4", "-0.015, 4",
            "-12345678901234567890123456789012345678, 21",
            "-1234567890123456789012345678901234567.8, 21",
            "1234567890123456789012345678901234567.8, 21",
            "-0.012345678901234567890123456789012345678, 21"
    })
    void numberCostsOneBytePerDigitPairFromTheDecimalPoint(String number, int bytes) {
        assertEquals(bytes, DynamoDbItemSize.numberSize(number));
    }

    @Test
    void numberSizeSurvivesAnExponentAtTheEdgeOfTheScaleRange() {
        assertEquals("100E2147483647".length(), DynamoDbItemSize.numberSize("100E2147483647"));
        assertEquals(4, DynamoDbItemSize.numberSize("1234E2147483645"));
    }

    @Test
    void putItemMeasuresANumberByItsDigitPairs() {
        ObjectNode item = key("a");
        item.set("n", numberValue("3.14159"));
        item.set("b", stringValue(padding("a", MAX_ITEM_SIZE, "n".length() + 5)));
        service.putItem(TABLE, item, REGION);
        assertEquals(MAX_ITEM_SIZE, storedBytes("a"));

        ObjectNode over = key("b");
        over.set("n", numberValue("3.14159"));
        over.set("b", stringValue(padding("b", MAX_ITEM_SIZE + 1, "n".length() + 5)));
        AwsException refused = assertThrows(AwsException.class, () -> service.putItem(TABLE, over, REGION));
        assertEquals("Item size has exceeded the maximum allowed size", refused.getMessage());
    }

    @Test
    void putItemMeasuresANumberSetByItsMembersAlone() {
        int setBytes = "n".length() + 2 + 3 + 3;
        ObjectNode item = key("a");
        item.set("n", numberSetValue("1", "110", "0.015"));
        item.set("b", stringValue(padding("a", MAX_ITEM_SIZE, setBytes)));
        service.putItem(TABLE, item, REGION);
        assertEquals(MAX_ITEM_SIZE, storedBytes("a"));

        ObjectNode over = key("b");
        over.set("n", numberSetValue("1", "110", "0.015"));
        over.set("b", stringValue(padding("b", MAX_ITEM_SIZE + 1, setBytes)));
        AwsException refused = assertThrows(AwsException.class, () -> service.putItem(TABLE, over, REGION));
        assertEquals("Item size has exceeded the maximum allowed size", refused.getMessage());
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
        int numberBytes = "n".length() + 2;

        update("a", "SET b = :pad ADD n :one", ceiling, numberBytes, one);
        assertEquals(ceiling, storedBytes("a"));
        assertRefused(() -> update("b", "SET b = :pad ADD n :one", ceiling + 1, numberBytes, one));
    }

    @Test
    void updateItemChargesTwoBytesForARemoveAlongsideASet() {
        int ceiling = MAX_ITEM_SIZE + SHORT_KEY_BYTES
                - (UPDATE_COST + SET_OR_ADD_COST + REMOVE_OR_DELETE_COST + "r".length());
        seed("a", "r", stringValue("z"));
        seed("b", "r", stringValue("z"));

        update("a", "SET b = :pad REMOVE r", ceiling, 0);
        assertEquals(ceiling, storedBytes("a"));
        assertRefused(() -> update("b", "SET b = :pad REMOVE r", ceiling + 1, 0));
    }

    @Test
    void updateItemChargesTwoBytesForADeleteAlongsideASet() {
        int ceiling = MAX_ITEM_SIZE + SHORT_KEY_BYTES
                - (UPDATE_COST + SET_OR_ADD_COST + REMOVE_OR_DELETE_COST + "s".length());
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
        int elementBytes = CONTAINER_COST + 1;
        seed("a", "d", listValue(stringValue("x")));
        seed("b", "d", listValue(stringValue("x")));

        update("a", "SET d[0] = :pad", ceiling, elementBytes);
        assertEquals(ceiling, storedBytes("a"));
        assertRefused(() -> update("b", "SET d[0] = :pad", ceiling + 1, elementBytes));
    }

    @Test
    void updateItemChargesIfNotExistsAndTheOperandsItReads() {
        int ceiling = MAX_ITEM_SIZE + SHORT_KEY_BYTES
                - (UPDATE_COST + SET_OR_ADD_COST + IF_NOT_EXISTS_COST + operandPath("b"));

        update("a", "SET b = if_not_exists(b, :pad)", ceiling, 0);
        assertEquals(ceiling, storedBytes("a"));
        assertRefused(() -> update("b", "SET b = if_not_exists(b, :pad)", ceiling + 1, 0));
    }

    @ParameterizedTest
    @CsvSource({
            "'SET c = if_not_exists(c, :huge)', attribute_exists(nope)",
            "'SET c = if_not_exists(c, :huge)', attribute_exists(c)",
            "'SET c = if_not_exists(c, :huge)',",
            "SET pk = :huge,"
    })
    void updateItemRefusesAnOverLimitStatementBeforeTheCondition(String expression, String condition) {
        seed("a", "c", stringValue("x"));

        assertRefused(() -> service.updateItem(TABLE, key("a"), null, expression, null,
                values(":huge", stringValue("h".repeat(MAX_ITEM_SIZE + 100))), "NONE", condition, REGION, "NONE"));
        assertEquals("pk".length() + "a".length() + "c".length() + "x".length(), storedBytes("a"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            SET a
            SET a = :v, b
            ADD a
            SET ,
            ADD ,
            SET a = if_not_exists(b, :v
            SET a = list_append(c, :v
            SET a = if_not_exists(list_append(c, :v), :v
            SET a, b = :v
            """)
    void updateItemLeavesAStatementItCannotSizeToTheUpdateParser(String expression) {
        seed("a", "c", stringValue("x"));

        AwsException refused = assertThrows(AwsException.class, () -> service.updateItem(TABLE, key("a"), null,
                expression, null, values(":v", stringValue("v")), "NONE", REGION));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void updateItemChecksTheConditionBeforeTheFinishedItem() {
        seed("a", "b", stringValue(padding("a", MAX_ITEM_SIZE, 0)));

        assertThrows(ConditionalCheckFailedException.class, () -> service.updateItem(TABLE, key("a"), null,
                "SET d = :d", null, values(":d", stringValue("y")), "NONE", "attribute_exists(nope)", REGION, "NONE"));
        assertRefused(() -> service.updateItem(TABLE, key("a"), null,
                "SET d = :d", null, values(":d", stringValue("y")), "NONE", null, REGION, "NONE"));
    }

    @Test
    void updateItemMeasuresALegacyAttributeUpdateByTheFinishedItem() {
        updateAttribute("a", MAX_ITEM_SIZE);
        assertEquals(MAX_ITEM_SIZE, storedBytes("a"));
        assertRefused(() -> updateAttribute("b", MAX_ITEM_SIZE + 1));
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

    @Test
    void putItemMeasuresAListAtThreeBytesPlusOnePerElement() {
        ObjectNode attributes = JsonNodeFactory.instance.objectNode();
        attributes.set("l", listValue(stringValue("x"), stringValue("y")));
        attributes.set("e", listValue());

        assertPutCeiling(attributes, "l".length() + CONTAINER_COST + 2 * (1 + 1) + "e".length() + CONTAINER_COST);
    }

    @Test
    void putItemMeasuresAMapAtThreeBytesPlusOnePerEntryAndItsName() {
        ObjectNode map = JsonNodeFactory.instance.objectNode();
        map.putObject("M").set("k", stringValue("x"));
        ((ObjectNode) map.get("M")).set("kk", stringValue("y"));
        ObjectNode attributes = JsonNodeFactory.instance.objectNode();
        attributes.set("m", map);
        attributes.set("n", JsonNodeFactory.instance.objectNode().set("M", JsonNodeFactory.instance.objectNode()));

        assertPutCeiling(attributes, "m".length() + CONTAINER_COST + (1 + 1 + 1) + (2 + 1 + 1)
                + "n".length() + CONTAINER_COST);
    }

    @Test
    void putItemMeasuresStringAndBinarySetsByTheirMembersAlone() {
        ObjectNode binarySet = JsonNodeFactory.instance.objectNode();
        binarySet.putArray("BS")
                .add(Base64.getEncoder().encodeToString(new byte[] {1}))
                .add(Base64.getEncoder().encodeToString(new byte[] {2, 2}));
        ObjectNode attributes = JsonNodeFactory.instance.objectNode();
        attributes.set("ss", stringSetValue("x", "yy"));
        attributes.set("bs", binarySet);

        assertPutCeiling(attributes, "ss".length() + 1 + 2 + "bs".length() + 1 + 2);
    }

    @Test
    void refusedPutLeavesTheStoredItemAsItWas() {
        seed("a", "u", stringValue("y".repeat(10)));
        JsonNode before = service.getItem(TABLE, key("a"), REGION).deepCopy();
        ObjectNode over = key("a");
        over.set("b", stringValue(padding("a", MAX_ITEM_SIZE + 1, 0)));

        assertThrows(AwsException.class, () -> service.putItem(TABLE, over, REGION));
        assertEquals(before, service.getItem(TABLE, key("a"), REGION));
    }

    @Test
    void refusedUpdateLeavesTheStoredItemAsItWas() {
        seed("a", "u", stringValue("y".repeat(10)));
        JsonNode before = service.getItem(TABLE, key("a"), REGION).deepCopy();

        assertRefused(() -> update("a", "SET b = :pad", MAX_ITEM_SIZE + 1, "u".length() + 10));
        assertEquals(before, service.getItem(TABLE, key("a"), REGION));
    }

    @Test
    void updateItemChargesListAppendAndTheOperandsItReads() {
        int fixed = UPDATE_COST + SET_OR_ADD_COST + "l".length()
                + LIST_APPEND_COST + operandPath("l") + CONTAINER_COST + 1;

        assertUpdateCeiling(attributes("l", listValue(stringValue("x"))), "SET l = list_append(l, :list)",
                pad -> values(":list", listValue(stringValue("x".repeat(pad)))), MAX_ITEM_SIZE - fixed);
    }

    @Test
    void updateItemChargesIfNotExistsNestedInListAppend() {
        int fixed = UPDATE_COST + SET_OR_ADD_COST + "l".length() + LIST_APPEND_COST
                + IF_NOT_EXISTS_COST + operandPath("l") + CONTAINER_COST + CONTAINER_COST + 1;

        assertUpdateCeiling(null, "SET l = list_append(if_not_exists(l, :empty), :list)", pad -> {
            ObjectNode values = values(":list", listValue(stringValue("x".repeat(pad))));
            values.set(":empty", listValue());
            return values;
        }, MAX_ITEM_SIZE - fixed);
    }

    @Test
    void updateItemChargesTheSourcePathOfACopy() {
        int fixed = UPDATE_COST + 2 * SET_OR_ADD_COST + "a".length() + operandPath("b") + "c".length();

        assertUpdateCeiling(attributes("b", stringValue("y")), "SET a = b, c = :pad",
                pad -> values(":pad", stringValue("x".repeat(pad))), MAX_ITEM_SIZE - fixed);
    }

    @Test
    void updateItemChargesEveryElementOfANestedSourcePath() {
        ObjectNode list = listValue();
        for (int i = 0; i < 11; i++) {
            ((ArrayNode) list.get("L")).add(stringValue("x"));
        }
        int fixed = UPDATE_COST + 2 * SET_OR_ADD_COST + "a".length() + operandPath("d", "ee", "10") + "c".length();

        assertUpdateCeiling(attributes("d", mapValue("ee", list)), "SET a = d.ee[10], c = :pad",
                pad -> values(":pad", stringValue("x".repeat(pad))), MAX_ITEM_SIZE - fixed);
    }

    @Test
    void updateItemChargesPlusAndMinusDifferently() {
        int one = 2;
        int fixed = UPDATE_COST + 3 * SET_OR_ADD_COST
                + "n".length() + PLUS_COST + operandPath("n") + one
                + "m".length() + MINUS_COST + operandPath("m") + one
                + "c".length();
        ObjectNode seed = attributes("n", numberValue("1"));
        seed.set("m", numberValue("1"));

        assertUpdateCeiling(seed, "SET n = n + :one, m = m - :one, c = :pad", pad -> {
            ObjectNode values = values(":pad", stringValue("x".repeat(pad)));
            values.set(":one", numberValue("1"));
            return values;
        }, MAX_ITEM_SIZE - fixed);
    }

    @Test
    void updateItemCountsASharedTargetPrefixOnce() {
        int fixed = UPDATE_COST + 2 * SET_OR_ADD_COST
                + "d".length() + TARGET_CONTAINER_COST + "a".length() + "b".length() + "e".length();

        assertUpdateCeiling(attributes("d", JsonNodeFactory.instance.objectNode().set("M",
                        JsonNodeFactory.instance.objectNode())), "SET d.a = :pad, d.b = :e", pad -> {
            ObjectNode values = values(":pad", stringValue("x".repeat(pad)));
            values.set(":e", stringValue("e"));
            return values;
        }, MAX_ITEM_SIZE - fixed);
    }

    @Test
    void updateItemCountsTheDigitsOfAListIndex() {
        ObjectNode list = listValue();
        for (int i = 0; i < 11; i++) {
            ((ArrayNode) list.get("L")).add(stringValue("x"));
        }
        int fixed = UPDATE_COST + SET_OR_ADD_COST + "l".length() + TARGET_CONTAINER_COST + "10".length();

        assertUpdateCeiling(attributes("l", list), "SET l[10] = :pad",
                pad -> values(":pad", stringValue("x".repeat(pad))), MAX_ITEM_SIZE - fixed);
    }

    @Test
    void updateItemChargesRemoveAndDeleteByTheirPathAlone() {
        ObjectNode seed = attributes("rrrrrrrrrr", stringValue("y"));
        seed.set("ssssssssss", stringSetValue("y", "z"));
        int fixed = UPDATE_COST + 2 * (REMOVE_OR_DELETE_COST + 10) + SET_OR_ADD_COST + "c".length();

        assertUpdateCeiling(seed, "REMOVE rrrrrrrrrr DELETE ssssssssss :z SET c = :pad", pad -> {
            ObjectNode values = values(":pad", stringValue("x".repeat(pad)));
            values.set(":z", stringSetValue("z".repeat(100)));
            return values;
        }, MAX_ITEM_SIZE - fixed);
    }

    @Test
    void updateItemChargesTheValueAnAddWritesNotTheSetItBuilds() {
        int fixed = UPDATE_COST + 2 * SET_OR_ADD_COST + "ss".length() + 1 + "c".length();

        assertUpdateCeiling(attributes("ss", stringSetValue("yy")), "ADD ss :v SET c = :pad", pad -> {
            ObjectNode values = values(":pad", stringValue("x".repeat(pad)));
            values.set(":v", stringSetValue("z"));
            return values;
        }, MAX_ITEM_SIZE - fixed);
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

    private void assertPutCeiling(ObjectNode attributes, int attributeBytes) {
        putPadded("a", MAX_ITEM_SIZE, attributes, attributeBytes);
        assertEquals(MAX_ITEM_SIZE, storedBytes("a"));
        AwsException refused = assertThrows(AwsException.class,
                () -> putPadded("b", MAX_ITEM_SIZE + 1, attributes, attributeBytes));
        assertEquals("Item size has exceeded the maximum allowed size", refused.getMessage());
    }

    private void putPadded(String keyValue, int totalBytes, ObjectNode attributes, int attributeBytes) {
        ObjectNode item = key(keyValue);
        item.setAll(attributes.deepCopy());
        item.set("b", stringValue(padding(keyValue, totalBytes, attributeBytes)));
        service.putItem(TABLE, item, REGION);
    }

    private void assertUpdateCeiling(ObjectNode seed, String expression, IntFunction<ObjectNode> values,
                                     int maxPadding) {
        for (String keyValue : List.of("a", "b")) {
            if (seed != null) {
                ObjectNode item = key(keyValue);
                item.setAll(seed.deepCopy());
                service.putItem(TABLE, item, REGION);
            }
        }
        service.updateItem(TABLE, key("a"), null, expression, null, values.apply(maxPadding), "NONE", REGION);
        assertRefused(() -> service.updateItem(TABLE, key("b"), null, expression, null,
                values.apply(maxPadding + 1), "NONE", REGION));
    }

    private static int operandPath(String... elements) {
        int size = OPERAND_PATH_COST;
        for (String element : elements) {
            size += OPERAND_PATH_ELEMENT_COST + element.length();
        }
        return size;
    }

    private ObjectNode attributes(String name, JsonNode value) {
        ObjectNode attributes = JsonNodeFactory.instance.objectNode();
        attributes.set(name, value);
        return attributes;
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

    private ObjectNode numberSetValue(String... members) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        ArrayNode set = value.putArray("NS");
        for (String member : members) {
            set.add(member);
        }
        return value;
    }

    private ObjectNode stringSetValue(String... members) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        ArrayNode set = value.putArray("SS");
        for (String member : members) {
            set.add(member);
        }
        return value;
    }

    private ObjectNode mapValue(String attribute, JsonNode value) {
        ObjectNode map = JsonNodeFactory.instance.objectNode();
        map.putObject("M").set(attribute, value);
        return map;
    }

    private ObjectNode listValue(JsonNode... elements) {
        ObjectNode list = JsonNodeFactory.instance.objectNode();
        ArrayNode array = list.putArray("L");
        for (JsonNode element : elements) {
            array.add(element);
        }
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
