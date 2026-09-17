package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DynamoDbCompositeKeyEncodingTest {

    private static final String REGION = "us-east-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private DynamoDbService service;

    @BeforeEach
    void setUp() {
        service = new DynamoDbService(new InMemoryStorage<>());
        createOrdersTable(service);
    }

    @Test
    void putAndGetKeepDelimiterBearingCompositeKeysDistinct() {
        ObjectNode first = order("A#", "B", "FIRST");
        ObjectNode second = order("A", "#B", "SECOND");

        service.putItem("Orders", first, REGION);
        service.putItem("Orders", second, REGION);

        assertEquals("FIRST", marker(service.getItem("Orders", key("A#", "B"), REGION)));
        assertEquals("SECOND", marker(service.getItem("Orders", key("A", "#B"), REGION)));
        assertEquals(2, service.scan("Orders", null, null, null, null, null, null, REGION).items().size());

        service.deleteItem("Orders", key("A#", "B"), REGION);
        assertEquals("SECOND", marker(service.getItem("Orders", key("A", "#B"), REGION)));
    }

    @Test
    void transactWriteAcceptsDelimiterBearingKeysAsDifferentItems() {
        service.transactWriteItems(List.of(
                transactPut(order("X#", "Y", "FIRST")),
                transactPut(order("X", "#Y", "SECOND"))), REGION);

        assertEquals("FIRST", marker(service.getItem("Orders", key("X#", "Y"), REGION)));
        assertEquals("SECOND", marker(service.getItem("Orders", key("X", "#Y"), REGION)));
    }

    @Test
    void batchWriteAcceptsDelimiterBearingKeysAsDifferentItems() {
        service.batchWriteItem(Map.of("Orders", List.of(
                batchPut(order("P#", "Q", "FIRST")),
                batchPut(order("P", "#Q", "SECOND")))), REGION);

        assertEquals("FIRST", marker(service.getItem("Orders", key("P#", "Q"), REGION)));
        assertEquals("SECOND", marker(service.getItem("Orders", key("P", "#Q"), REGION)));
    }

    @Test
    void backslashesRemainUnambiguousWhenKeySegmentsAreEscaped() {
        service.putItem("Orders", order("A\\", "#B", "FIRST"), REGION);
        service.putItem("Orders", order("A\\#", "B", "SECOND"), REGION);

        assertEquals("FIRST", marker(service.getItem("Orders", key("A\\", "#B"), REGION)));
        assertEquals("SECOND", marker(service.getItem("Orders", key("A\\#", "B"), REGION)));
    }

    @Test
    void scanPaginationContinuesAfterDelimiterBearingKey() {
        service.putItem("Orders", order("A#", "B", "FIRST"), REGION);
        service.putItem("Orders", order("A", "#B", "SECOND"), REGION);

        DynamoDbService.ScanResult firstPage =
                service.scan("Orders", null, null, null, null, 1, null, REGION);
        assertEquals(1, firstPage.items().size());
        assertNotNull(firstPage.lastEvaluatedKey());

        DynamoDbService.ScanResult secondPage = service.scan(
                "Orders", null, null, null, null, 1, firstPage.lastEvaluatedKey(), REGION);
        assertEquals(1, secondPage.items().size());

        Set<String> markers = new HashSet<>();
        markers.add(marker(firstPage.items().getFirst()));
        markers.add(marker(secondPage.items().getFirst()));
        assertEquals(Set.of("FIRST", "SECOND"), markers);
    }

    @Test
    void startupRekeysItemsPersistedWithLegacyDelimitedAddress() {
        String accountId = "222222222222";
        StorageBackend<String, TableDefinition> tableStore =
                AccountAwareStorageBackend.inMemory(accountId);
        StorageBackend<String, Map<String, JsonNode>> itemStore =
                AccountAwareStorageBackend.inMemory(accountId);
        RegionResolver resolver = new RegionResolver(REGION, accountId);
        DynamoDbService beforeRestart = new DynamoDbService(tableStore, itemStore, resolver);
        createOrdersTable(beforeRestart);

        ObjectNode persisted = order("A#", "B", "LEGACY");
        itemStore.put(REGION + "::Orders", Map.of("A##B", persisted));

        DynamoDbService restarted = new DynamoDbService(tableStore, itemStore, resolver);

        assertEquals("LEGACY", marker(restarted.getItem("Orders", key("A#", "B"), REGION)));
    }

    private void createOrdersTable(DynamoDbService target) {
        target.createTable("Orders",
                List.of(
                        new KeySchemaElement("customerId", "HASH"),
                        new KeySchemaElement("orderId", "RANGE")),
                List.of(
                        new AttributeDefinition("customerId", "S"),
                        new AttributeDefinition("orderId", "S")),
                5L, 5L, REGION);
    }

    private ObjectNode order(String customerId, String orderId, String marker) {
        ObjectNode item = key(customerId, orderId);
        item.set("marker", attribute("S", marker));
        return item;
    }

    private ObjectNode key(String customerId, String orderId) {
        ObjectNode key = mapper.createObjectNode();
        key.set("customerId", attribute("S", customerId));
        key.set("orderId", attribute("S", orderId));
        return key;
    }

    private ObjectNode attribute(String type, String value) {
        ObjectNode attribute = mapper.createObjectNode();
        attribute.put(type, value);
        return attribute;
    }

    private ObjectNode transactPut(JsonNode item) {
        ObjectNode put = mapper.createObjectNode();
        put.put("TableName", "Orders");
        put.set("Item", item);
        ObjectNode request = mapper.createObjectNode();
        request.set("Put", put);
        return request;
    }

    private ObjectNode batchPut(JsonNode item) {
        ObjectNode put = mapper.createObjectNode();
        put.set("Item", item);
        ObjectNode request = mapper.createObjectNode();
        request.set("PutRequest", put);
        return request;
    }

    private String marker(JsonNode item) {
        return item.path("marker").path("S").asText();
    }
}
