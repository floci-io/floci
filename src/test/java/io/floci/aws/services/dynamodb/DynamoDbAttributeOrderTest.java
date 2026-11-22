package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DynamoDbAttributeOrderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    void mapShowsEverySixOrdersOverManyCalls() throws Exception {
        JsonNode item = json("{\"m\":{\"M\":{\"a\":{\"S\":\"1\"},\"b\":{\"S\":\"2\"},\"c\":{\"S\":\"3\"}}}}");
        Set<String> orders = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            orders.add(order(DynamoDbAttributeOrder.shuffled(item, true).get("m").get("M")));
        }
        assertEquals(6, orders.size());
    }

    @Test
    void topLevelAttributesShuffle() throws Exception {
        JsonNode item = json("{\"a\":{\"S\":\"1\"},\"b\":{\"S\":\"2\"},\"c\":{\"S\":\"3\"}}");
        Set<String> orders = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            orders.add(order(DynamoDbAttributeOrder.shuffled(item, true)));
        }
        assertEquals(6, orders.size());
    }

    @Test
    void mapInsideListInsideMapShuffles() throws Exception {
        JsonNode item = json("{\"o\":{\"M\":{\"l\":{\"L\":[{\"M\":{\"a\":{\"N\":\"1\"},\"b\":{\"N\":\"2\"},"
                + "\"c\":{\"N\":\"3\"}}}]}}}}");
        Set<String> orders = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            JsonNode inner = DynamoDbAttributeOrder.shuffled(item, true)
                    .get("o").get("M").get("l").get("L").get(0).get("M");
            orders.add(order(inner));
        }
        assertEquals(6, orders.size());
    }

    @Test
    void valuesSetsAndListsKeepTheirOrder() throws Exception {
        JsonNode item = json("{\"ss\":{\"SS\":[\"z\",\"a\",\"m\"]},\"ns\":{\"NS\":[\"3\",\"1\"]},"
                + "\"l\":{\"L\":[{\"S\":\"q\"},{\"N\":\"5\"},{\"S\":\"b\"}]},\"n\":{\"N\":\"7\"},"
                + "\"m\":{\"M\":{\"x\":{\"SS\":[\"y\",\"x\"]}}}}");
        for (int i = 0; i < 50; i++) {
            JsonNode shuffled = DynamoDbAttributeOrder.shuffled(item, true);
            assertEquals(item.get("ss"), shuffled.get("ss"));
            assertEquals(item.get("ns"), shuffled.get("ns"));
            assertEquals(item.get("l"), shuffled.get("l"));
            assertEquals(item.get("n"), shuffled.get("n"));
            assertEquals(item.get("m"), shuffled.get("m"));
            assertEquals(item.size(), shuffled.size());
        }
    }

    @Test
    void inputIsNotChanged() throws Exception {
        JsonNode item = json("{\"a\":{\"S\":\"1\"},\"b\":{\"M\":{\"x\":{\"S\":\"1\"},\"y\":{\"S\":\"2\"}}}}");
        String before = item.toString();
        for (int i = 0; i < 50; i++) {
            assertNotSame(item, DynamoDbAttributeOrder.shuffled(item, true));
        }
        assertEquals(before, item.toString());
    }

    @Test
    void disabledReturnsTheSameNode() throws Exception {
        JsonNode item = json("{\"a\":{\"S\":\"1\"}}");
        assertSame(item, DynamoDbAttributeOrder.shuffled(item, false));
    }

    @Test
    void nullStaysNull() {
        assertNull(DynamoDbAttributeOrder.shuffled(null, true));
    }
}
