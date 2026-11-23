package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DynamoDbMapOrderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    void keysSortByCodePointNotByUtf16Unit() throws Exception {
        JsonNode item = json("""
                {"m":{"M":{"😀":{"S":"x"},"￦":{"S":"x"},"é":{"S":"x"},"aa":{"S":"x"},
                "a b":{"S":"x"},"a":{"S":"x"},"_":{"S":"x"},"B":{"S":"x"},"A":{"S":"x"},"1":{"S":"x"}}}}""");

        JsonNode sorted = DynamoDbMapOrder.sortedItem(item);

        assertEquals("1,A,B,_,a,a b,aa,é,￦,😀", order(sorted.get("m").get("M")));
    }

    @Test
    void mapInsideListInsideMapIsSorted() throws Exception {
        JsonNode item = json("""
                {"m":{"M":{"z":{"S":"1"},"l":{"L":[{"M":{"z":{"S":"1"},"b":{"S":"2"}}},{"S":"s"}]},"b":{"S":"2"}}}}""");

        JsonNode sorted = DynamoDbMapOrder.sortedItem(item);

        JsonNode m = sorted.get("m").get("M");
        assertEquals("b,l,z", order(m));
        assertEquals("b,z", order(m.get("l").get("L").get(0).get("M")));
    }

    @Test
    void topLevelOrderIsKept() throws Exception {
        JsonNode item = json("{\"z\":{\"S\":\"1\"},\"a\":{\"S\":\"2\"},\"m\":{\"M\":{\"y\":{\"S\":\"1\"},\"x\":{\"S\":\"2\"}}}}");

        JsonNode sorted = DynamoDbMapOrder.sortedItem(item);

        assertEquals("z,a,m", order(sorted));
        assertEquals("x,y", order(sorted.get("m").get("M")));
    }

    @Test
    void inputIsNotChanged() throws Exception {
        JsonNode item = json("{\"m\":{\"M\":{\"y\":{\"S\":\"1\"},\"x\":{\"S\":\"2\"}}}}");

        DynamoDbMapOrder.sortedItem(item);

        assertEquals("y,x", order(item.get("m").get("M")));
    }

    @Test
    void itemWithoutMapIsReturnedAsIs() throws Exception {
        JsonNode item = json("{\"b\":{\"S\":\"1\"},\"a\":{\"L\":[{\"N\":\"1\"}]}}");

        assertSame(item, DynamoDbMapOrder.sortedItem(item));
    }

    @Test
    void setsAreNotChanged() throws Exception {
        JsonNode item = json("{\"m\":{\"M\":{\"b\":{\"SS\":[\"z\",\"a\"]},\"a\":{\"NS\":[\"2\",\"1\"]}}}}");

        JsonNode sorted = DynamoDbMapOrder.sortedItem(item);

        JsonNode m = sorted.get("m").get("M");
        assertEquals("a,b", order(m));
        assertEquals("[\"z\",\"a\"]", m.get("b").get("SS").toString());
        assertEquals("[\"2\",\"1\"]", m.get("a").get("NS").toString());
    }

    @Test
    void valueIsSortedAtAnyDepth() throws Exception {
        JsonNode values = json("{\":v\":{\"L\":[{\"M\":{\"z\":{\"S\":\"1\"},\"a\":{\"M\":{\"y\":{\"S\":\"1\"},"
                + "\"x\":{\"S\":\"2\"}}}}}]}}");

        JsonNode sorted = DynamoDbMapOrder.sortedItem(values);

        JsonNode map = sorted.get(":v").get("L").get(0).get("M");
        assertEquals("a,z", order(map));
        assertEquals("x,y", order(map.get("a").get("M")));
    }

    @Test
    void attributeUpdatesSortEachValue() throws Exception {
        JsonNode updates = json("""
                {"m":{"Action":"PUT","Value":{"M":{"z":{"S":"1"},"b":{"S":"2"}}}},"n":{"Action":"ADD","Value":{"N":"1"}}}""");

        JsonNode sorted = DynamoDbMapOrder.sortedAttributeUpdates(updates);

        assertEquals("b,z", order(sorted.get("m").get("Value").get("M")));
        assertEquals("PUT", sorted.get("m").get("Action").asText());
        assertEquals("z,b", order(updates.get("m").get("Value").get("M")));
    }

    @Test
    void sortKeysReordersOneMapInPlace() throws Exception {
        ObjectNode map = (ObjectNode) json("{\"z\":{\"M\":{\"y\":{\"S\":\"1\"},\"x\":{\"S\":\"2\"}}},\"a\":{\"S\":\"1\"}}");
        JsonNode nested = map.get("z");

        DynamoDbMapOrder.sortKeys(map);

        assertEquals("a,z", order(map));
        assertSame(nested, map.get("z"));
        assertEquals("y,x", order(map.get("z").get("M")));
    }

    @Test
    void withSortedMemberSortsTheMemberAndKeepsTheRest() throws Exception {
        JsonNode request = json("""
                {"Put":{"Item":{"m":{"M":{"z":{"S":"1"},"b":{"S":"2"}}}},"TableName":"t"},"Other":1}""");

        JsonNode result = DynamoDbMapOrder.withSortedMember(request, "Put", "Item");

        assertEquals("b,z", order(result.get("Put").get("Item").get("m").get("M")));
        assertEquals("t", result.get("Put").get("TableName").asText());
        assertEquals(1, result.get("Other").asInt());
        assertEquals("z,b", order(request.get("Put").get("Item").get("m").get("M")));
    }

    @Test
    void withSortedMemberReturnsTheRequestWhenTheOperationIsAbsent() throws Exception {
        JsonNode request = json("{\"Delete\":{\"Key\":{\"id\":{\"S\":\"k\"}}}}");

        assertSame(request, DynamoDbMapOrder.withSortedMember(request, "Put", "Item"));
    }
}
