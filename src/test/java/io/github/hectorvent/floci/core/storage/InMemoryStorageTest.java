package io.github.hectorvent.floci.core.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryStorageTest {

    private InMemoryStorage<String, String> storage;

    @BeforeEach
    void setUp() {
        storage = new InMemoryStorage<>();
    }

    @Test
    void putAndGet() {
        storage.put("key1", "value1");
        Optional<String> result = storage.get("key1");
        assertTrue(result.isPresent());
        assertEquals("value1", result.get());
    }

    @Test
    void getReturnsEmptyForMissingKey() {
        assertTrue(storage.get("missing").isEmpty());
    }

    @Test
    void putOverwritesExistingValue() {
        storage.put("key1", "value1");
        storage.put("key1", "value2");
        assertEquals("value2", storage.get("key1").orElseThrow());
    }

    @Test
    void delete() {
        storage.put("key1", "value1");
        storage.delete("key1");
        assertTrue(storage.get("key1").isEmpty());
    }

    @Test
    void deleteNonExistentKeyDoesNotThrow() {
        assertDoesNotThrow(() -> storage.delete("missing"));
    }

    @Test
    void scan() {
        storage.put("app.db.host", "localhost");
        storage.put("app.db.port", "5432");
        storage.put("app.cache.host", "redis");

        List<String> dbValues = storage.scan(key -> key.startsWith("app.db."));
        assertEquals(2, dbValues.size());
        assertTrue(dbValues.contains("localhost"));
        assertTrue(dbValues.contains("5432"));
    }

    @Test
    void scanReturnsMutableList() {
        storage.put("a", "1");
        storage.put("b", "2");
        List<String> result = storage.scan(k -> true);
        assertDoesNotThrow(() -> result.sort(String::compareTo));
        assertDoesNotThrow(() -> result.add("3"));
    }

    @Test
    void scanWithNoMatchesReturnsEmptyList() {
        storage.put("key1", "value1");
        List<String> result = storage.scan(key -> key.startsWith("nonexistent"));
        assertTrue(result.isEmpty());
    }

    @Test
    void clear() {
        storage.put("key1", "value1");
        storage.put("key2", "value2");
        storage.clear();
        assertTrue(storage.get("key1").isEmpty());
        assertTrue(storage.get("key2").isEmpty());
    }

    @Test
    void flushAndLoadAreNoOps() {
        storage.put("key1", "value1");
        assertDoesNotThrow(() -> storage.flush());
        assertDoesNotThrow(() -> storage.load());
        assertEquals("value1", storage.get("key1").orElseThrow());
    }
}
