package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.ITEM;
import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamoDbAttributeOrderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TABLE = "attribute-order";
    private static final String REGION = "us-east-1";

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

    @Test
    void storedOrderIsKeptWhenTheSettingIsOff() throws Exception {
        Orders orders = handlerOrders(false);
        assertEquals(1, orders.top().size(), orders.top().toString());
        assertEquals(Set.of("k1,k2,k3,k4,k5,k6"), orders.map());
    }

    @Test
    void returnValuesShuffleWhenTheSettingIsOn() throws Exception {
        Orders orders = handlerOrders(true);
        assertTrue(orders.top().size() > 1, orders.top().toString());
        assertTrue(orders.map().size() > 1, orders.map().toString());
    }

    private record Orders(Set<String> top, Set<String> map) {
    }

    private static Orders handlerOrders(boolean randomize) throws Exception {
        Orders orders = new Orders(new HashSet<>(), new HashSet<>());
        DynamoDbService service = new DynamoDbService(new InMemoryStorage<>());
        service.createTable(TABLE, List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")), 5L, 5L, REGION);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().dynamodb().randomizeAttributeOrder()).thenReturn(randomize);
        NativeDynamoDbJsonHandler handler = new NativeDynamoDbJsonHandler(service,
                new NativeDynamoDbTableService(service, null, null), MAPPER, config);
        JsonNode put = json("{\"TableName\":\"" + TABLE + "\",\"Item\":" + ITEM
                + ",\"ReturnValues\":\"ALL_OLD\"}");
        JsonNode transactGet = json("{\"TransactItems\":[{\"Get\":{\"TableName\":\"" + TABLE
                + "\",\"Key\":{\"pk\":{\"S\":\"k\"}}}}]}");
        handler.handle("PutItem", put, REGION);
        for (int i = 0; i < 20; i++) {
            JsonNode attributes = body(handler.handle("PutItem", put, REGION)).get("Attributes");
            orders.top().add(order(attributes));
            orders.map().add(order(attributes.get("m").get("M")));
            JsonNode transacted = body(handler.handle("TransactGetItems", transactGet, REGION))
                    .get("Responses").get(0).get("Item");
            orders.top().add(order(transacted));
            orders.map().add(order(transacted.get("m").get("M")));
        }
        return orders;
    }

    private static JsonNode body(Response response) {
        return MAPPER.convertValue(response.getEntity(), JsonNode.class);
    }
}
