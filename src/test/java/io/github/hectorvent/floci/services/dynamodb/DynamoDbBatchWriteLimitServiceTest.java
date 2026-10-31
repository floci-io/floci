package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DynamoDbBatchWriteLimitServiceTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private DynamoDbService service;

    @BeforeEach
    void setUp() {
        service = new DynamoDbService(new InMemoryStorage<>());
        for (String table : List.of("First", "Second")) {
            service.createTable(table, List.of(new KeySchemaElement("pk", "HASH")),
                    List.of(new AttributeDefinition("pk", "S")), 5L, 5L, REGION);
            service.putItem(table, key("delete"), REGION);
        }
    }

    @Test
    void acceptsTwentyFiveMixedWritesAcrossTables() {
        assertTrue(service.batchWriteItem(batch(12, 13), REGION).unprocessedItems().isEmpty());
        assertNull(service.getItem("First", key("delete"), REGION));
        assertNull(service.getItem("Second", key("delete"), REGION));
        assertNotNull(service.getItem("First", key("put-10"), REGION));
        assertNotNull(service.getItem("Second", key("put-11"), REGION));
    }

    @Test
    void rejectsTwentySixMixedWritesAcrossTablesBeforeAnyMutation() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.batchWriteItem(batch(13, 13), REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertUnchanged();
    }

    @Test
    void rejectsTwentySixWritesInOneTableBeforeAnyMutation() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.batchWriteItem(Map.of("First", writes(26)), REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertUnchanged();
    }

    @Test
    void preservesExistingValidationBeforeAggregateLimit() {
        Map<String, List<JsonNode>> requests = batch(13, 13);
        requests.get("Second").set(12, requests.get("Second").get(11));
        AwsException duplicate = assertThrows(AwsException.class,
                () -> service.batchWriteItem(requests, REGION));
        assertEquals("Provided list of item keys contains duplicates", duplicate.getMessage());
        assertUnchanged();
    }

    private void assertUnchanged() {
        for (String table : List.of("First", "Second")) {
            assertNotNull(service.getItem(table, key("delete"), REGION));
            assertEquals(1, service.scan(table, null, null, null, null, null, null, REGION).items().size());
        }
    }

    private Map<String, List<JsonNode>> batch(int first, int second) {
        Map<String, List<JsonNode>> requests = new LinkedHashMap<>();
        requests.put("First", writes(first));
        requests.put("Second", writes(second));
        return requests;
    }

    private List<JsonNode> writes(int count) {
        List<JsonNode> result = new ArrayList<>();
        ObjectNode delete = mapper.createObjectNode();
        delete.putObject("DeleteRequest").set("Key", key("delete"));
        result.add(delete);
        for (int index = 0; index < count - 1; index++) {
            ObjectNode put = mapper.createObjectNode();
            put.putObject("PutRequest").set("Item", key("put-" + index));
            result.add(put);
        }
        return result;
    }

    private ObjectNode key(String value) {
        ObjectNode key = mapper.createObjectNode();
        key.putObject("pk").put("S", value);
        return key;
    }
}
