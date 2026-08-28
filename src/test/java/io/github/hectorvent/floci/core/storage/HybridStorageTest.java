package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.LocalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HybridStorageTest {

    @TempDir
    Path tempDir;

    private HybridStorage<String, String> storage;

    @BeforeEach
    void setUp() {
        Path filePath = tempDir.resolve("hybrid-test.json");
        storage = new HybridStorage<>(filePath, new TypeReference<>() {
        }, 60000);
    }

    @AfterEach
    void tearDown() {
        storage.shutdown();
    }

    @Test
    void putAndGetFromMemory() {
        storage.put("key1", "value1");
        assertEquals("value1", storage.get("key1").orElseThrow());
    }

    @Test
    void explicitFlushPersistsData() {
        Path filePath = tempDir.resolve("flush-test.json");
        var store1 = new HybridStorage<>(filePath, new TypeReference<Map<String, String>>() {}, 60000);
        store1.put("key1", "value1");
        store1.flush();
        store1.shutdown();

        var store2 = new HybridStorage<>(filePath, new TypeReference<Map<String, String>>() {}, 60000);
        store2.load();
        assertEquals("value1", store2.get("key1").orElseThrow());
        store2.shutdown();
    }

    @Test
    void dynamoTableWithLocalSecondaryIndexSurvivesRestart() {
        Path filePath = tempDir.resolve("dynamodb-tables.json");
        TypeReference<Map<String, TableDefinition>> type = new TypeReference<>() {};
        var table = new TableDefinition(
                "ConfigTable",
                List.of(new KeySchemaElement("pk", "HASH"), new KeySchemaElement("sk", "RANGE")),
                List.of(new AttributeDefinition("pk", "S"), new AttributeDefinition("sk", "S"),
                        new AttributeDefinition("lsiSk", "S")));
        table.setLocalSecondaryIndexes(List.of(new LocalSecondaryIndex(
                "lsi", List.of(new KeySchemaElement("pk", "HASH"),
                        new KeySchemaElement("lsiSk", "RANGE")), null, "ALL")));

        var store1 = new HybridStorage<String, TableDefinition>(filePath, type, 60000);
        store1.put("000000000000/us-east-1::ConfigTable", table);
        store1.shutdown();

        var store2 = new HybridStorage<String, TableDefinition>(filePath, type, 60000);
        store2.load();
        TableDefinition restored = store2.get("000000000000/us-east-1::ConfigTable").orElseThrow();
        assertEquals("lsiSk", restored.getLocalSecondaryIndexes().getFirst().getSortKeyName());
        store2.shutdown();
    }

    @Test
    void deleteRemovesFromMemory() {
        storage.put("key1", "value1");
        storage.delete("key1");
        assertTrue(storage.get("key1").isEmpty());
    }

    @Test
    void scanWorks() {
        storage.put("a.1", "v1");
        storage.put("a.2", "v2");
        storage.put("b.1", "v3");

        var results = storage.scan(key -> key.startsWith("a."));
        assertEquals(2, results.size());
    }

    @Test
    void scanReturnsMutableList() {
        storage.put("a", "1");
        storage.put("b", "2");
        var result = storage.scan(key -> true);
        assertDoesNotThrow(() -> result.sort(String::compareTo));
        assertDoesNotThrow(() -> result.add("3"));
    }

    @Test
    void clearRemovesAll() {
        storage.put("key1", "value1");
        storage.put("key2", "value2");
        storage.clear();
        assertTrue(storage.get("key1").isEmpty());
        assertTrue(storage.get("key2").isEmpty());
    }
}
