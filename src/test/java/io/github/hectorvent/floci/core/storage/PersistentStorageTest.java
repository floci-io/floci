package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PersistentStorageTest {

    @TempDir
    Path tempDir;

    private PersistentStorage<String, String> storage;

    @BeforeEach
    void setUp() {
        Path filePath = tempDir.resolve("test-store.json");
        storage = new PersistentStorage<>(filePath, new TypeReference<Map<String, String>>() {});
    }

    @Test
    void putAndGet() {
        storage.put("key1", "value1");
        assertEquals("value1", storage.get("key1").orElseThrow());
    }

    @Test
    void persistsAcrossInstances() {
        Path filePath = tempDir.resolve("persist-test.json");
        var store1 = new PersistentStorage<>(filePath, new TypeReference<Map<String, String>>() {});
        store1.put("key1", "value1");
        store1.put("key2", "value2");

        var store2 = new PersistentStorage<>(filePath, new TypeReference<Map<String, String>>() {});
        store2.load();
        assertEquals("value1", store2.get("key1").orElseThrow());
        assertEquals("value2", store2.get("key2").orElseThrow());
    }

    @Test
    void delete() {
        storage.put("key1", "value1");
        storage.delete("key1");
        assertTrue(storage.get("key1").isEmpty());
    }

    @Test
    void scan() {
        storage.put("/app/db/host", "localhost");
        storage.put("/app/db/port", "5432");
        storage.put("/app/cache/host", "redis");

        List<String> results = storage.scan(key -> key.startsWith("/app/db/"));
        assertEquals(2, results.size());
    }

    @Test
    void scanReturnsMutableList() {
        storage.put("a", "1");
        storage.put("b", "2");
        List<String> result = storage.scan(key -> true);
        assertDoesNotThrow(() -> result.sort(String::compareTo));
        assertDoesNotThrow(() -> result.add("3"));
    }

    @Test
    void clear() {
        storage.put("key1", "value1");
        storage.clear();
        assertTrue(storage.get("key1").isEmpty());
    }

    @Test
    void loadFromEmptyFileDoesNotThrow() {
        assertDoesNotThrow(() -> storage.load());
    }
}
