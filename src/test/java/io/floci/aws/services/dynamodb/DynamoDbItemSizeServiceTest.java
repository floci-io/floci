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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamoDbItemSizeServiceTest {

    private static final String REGION = "us-east-1";
    private static final String TABLE = "ItemSizes";
    private static final int MAX_ITEM_SIZE = 400 * 1024;
    private static final String UPDATE_EXCEEDED =
            "Item size to update has exceeded the maximum allowed size";
    private static final int CONTAINER_COST = 3;

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
    void updateItemAcceptsAFinishedItemOfExactlyTheLimitAtAShortKey() {
        update("a", "SET b = :pad", MAX_ITEM_SIZE, 0);
        assertEquals(MAX_ITEM_SIZE, storedBytes("a"));
        assertRefused(() -> update("b", "SET b = :pad", MAX_ITEM_SIZE + 1, 0));
    }

    @Test
    void updateItemCountsATargetNameAndEachValueTheOperandUses() {
        assertStatementCeiling(withC(), "SET c = if_not_exists(c, :v)", null, null, 1);
    }

    @Test
    void updateItemCountsEveryNameOfADocumentPath() {
        assertStatementCeiling(withC("d", mapValue("leaf", stringValue("x"))),
                "SET d.leaf = if_not_exists(d.leaf, :v)", null, null, "d".length() + "leaf".length());
        ObjectNode nested = mapValue("e", mapValue("leaf", stringValue("x")));
        assertStatementCeiling(withC("d", nested),
                "SET d.e.leaf = if_not_exists(d.e.leaf, :v)", null, null, "d".length() + "e".length() + "leaf".length());
    }

    @Test
    void updateItemCountsAListIndexAsZero() {
        ObjectNode list = listValue();
        for (int i = 0; i < 11; i++) {
            ((ArrayNode) list.get("L")).add(stringValue("x"));
        }
        assertStatementCeiling(withC("l", list), "SET l[0] = if_not_exists(l[0], :v)", null, null, "l".length());
        assertStatementCeiling(withC("l", list), "SET l[10] = if_not_exists(l[10], :v)", null, null, "l".length());
    }

    @Test
    void updateItemDoesNotMergeTargetPathsThatShareAPrefix() {
        ObjectNode map = JsonNodeFactory.instance.objectNode();
        map.putObject("M").set("a", stringValue("x"));
        ((ObjectNode) map.get("M")).set("b", stringValue("y"));
        assertStatementCeiling(withC("d", map),
                "SET d.a = if_not_exists(d.a, :v), d.b = if_not_exists(d.b, :w)", null,
                values(":w", stringValue("y")),
                "d".length() + "a".length() + "d".length() + "b".length() + "y".length());
    }

    @Test
    void updateItemResolvesAliasesInATargetPath() {
        assertStatementCeiling(withC("d", mapValue("leafleafle", stringValue("x"))),
                "SET #d.#l = if_not_exists(#d.#l, :v)", names("#d", "d", "#l", "leafleafle"), null,
                "d".length() + "leafleafle".length());
    }

    @Test
    void updateItemCountsTheValueAnAddWritesButNotADelete() {
        ObjectNode members = values(":w", stringSetValue("z".repeat(100000)));
        ObjectNode seed = withC("s", stringSetValue("q"));
        assertStatementCeiling(seed, "SET c = if_not_exists(c, :v) ADD s :w", null, members, 1 + 1 + 100000);
        assertStatementCeiling(seed, "SET c = if_not_exists(c, :v) DELETE s :w", null, members, 1);
    }

    @Test
    void updateItemDoesNotCountARemovePath() {
        assertStatementCeiling(withC(), "SET c = if_not_exists(c, :v) REMOVE #r", names("#r", "r".repeat(1000)),
                null, 1);
    }

    @Test
    void updateItemCountsTheNameAndNumberBytesOfAnAdd() {
        assertStatementCeiling(withC(), "SET c = if_not_exists(c, :v) ADD #m :w", names("#m", "m".repeat(1000)),
                values(":w", numberValue("12345678901234567890")), 1 + 1000 + 11);
    }

    @Test
    void updateItemCountsEachValueOfAnArithmeticOperand() {
        ObjectNode two = values(":one", numberValue("1"));
        two.set(":two", numberValue("2"));
        assertStatementCeiling(withC(), "SET c = if_not_exists(c, :v), n = :one + :two", null, two,
                1 + 1 + 2 + 2);
        assertStatementCeiling(withC("n", numberValue("1")), "SET c = if_not_exists(c, :v), n = n + :two", null,
                values(":two", numberValue("2")), 1 + 1 + 2);
        assertStatementCeiling(withC(), "SET c = if_not_exists(c, :v), n = if_not_exists(n, :two) + :two", null,
                values(":two", numberValue("2")), 1 + 1 + 2 + 2);
    }

    @Test
    void updateItemCountsEachValueOfAListAppend() {
        ObjectNode lists = values(":a", listValue(stringValue("aaaa")));
        lists.set(":b", listValue(stringValue("bbbb")));
        assertStatementCeiling(withC(), "SET c = if_not_exists(c, :v), l = list_append(:a, :b)", null, lists,
                1 + 1 + 8 + 8);
    }

    @Test
    void updateItemDoesNotCountAnOperandPath() {
        assertStatementCeiling(withC("e", stringValue("e".repeat(100000))), "SET c = if_not_exists(c, :v), d = e",
                null, null, 1 + 1);
    }

    @Test
    void updateItemCountsAValueOncePerUse() {
        assertStatementCeiling(withC(), "SET c = if_not_exists(c, :v), d = if_not_exists(d, :v)", null, null,
                1 + 1, 2);
    }

    @Test
    void updateItemDoesNotCountAValueUsedOnlyInTheCondition() {
        seed("a", "c", stringValue("x"));

        service.updateItem(TABLE, key("a"), null, "SET b = :s", null,
                values(":s", stringValue("y")).set(":v", stringValue("h".repeat(MAX_ITEM_SIZE + 300))),
                "NONE", "c <> :v", REGION, "NONE");
        assertEquals("y", service.getItem(TABLE, key("a"), REGION).get("b").get("S").asText());
    }

    @Test
    void updateItemCapsAtFourHundredKilobytesAtALongKey() {
        String longKey = "k".repeat(100);

        update(longKey, "SET b = :pad", MAX_ITEM_SIZE, 0);
        assertEquals(MAX_ITEM_SIZE, storedBytes(longKey));
        assertRefused(() -> update("K".repeat(100), "SET b = :pad", MAX_ITEM_SIZE + 1, 0));
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
    void updateItemCountsAnUntouchedAttributeInTheFinishedItem() {
        int untouchedBytes = 1 + 10; // "u" plus its value
        seed("a", "u", stringValue("y".repeat(10)));
        seed("b", "u", stringValue("y".repeat(10)));

        update("a", "SET b = :pad", MAX_ITEM_SIZE, untouchedBytes);
        assertEquals(MAX_ITEM_SIZE, storedBytes("a"));
        assertRefused(() -> update("b", "SET b = :pad", MAX_ITEM_SIZE + 1, untouchedBytes));
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

    private void update(String keyValue, String expression, int totalBytes, int otherBytes) {
        ObjectNode values = values(":pad", stringValue(padding(keyValue, totalBytes, otherBytes)));
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

    private ObjectNode withC() {
        return attributes("c", stringValue("x"));
    }

    private ObjectNode withC(String name, JsonNode value) {
        ObjectNode seed = withC();
        seed.set(name, value);
        return seed;
    }

    private ObjectNode names(String... aliasAndName) {
        ObjectNode names = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < aliasAndName.length; i += 2) {
            names.put(aliasAndName[i], aliasAndName[i + 1]);
        }
        return names;
    }

    private void assertStatementCeiling(ObjectNode seed, String expression, ObjectNode names, ObjectNode extra,
                                        int fixedBytes) {
        assertStatementCeiling(seed, expression, names, extra, fixedBytes, 1);
    }

    private void assertStatementCeiling(ObjectNode seed, String expression, ObjectNode names, ObjectNode extra,
                                        int fixedBytes, int vUses) {
        int maxPad = (MAX_ITEM_SIZE - fixedBytes) / vUses;
        for (String keyValue : List.of("a", "b")) {
            ObjectNode item = key(keyValue);
            item.setAll(seed.deepCopy());
            service.putItem(TABLE, item, REGION);
        }
        service.updateItem(TABLE, key("a"), null, expression, names, statementValues(extra, maxPad),
                "NONE", REGION);
        assertRefused(() -> service.updateItem(TABLE, key("b"), null, expression, names,
                statementValues(extra, maxPad + 1), "NONE", REGION));
    }

    private ObjectNode statementValues(ObjectNode extra, int pad) {
        ObjectNode values = extra == null ? JsonNodeFactory.instance.objectNode() : extra.deepCopy();
        values.set(":v", stringValue("x".repeat(pad)));
        return values;
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
