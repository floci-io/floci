package io.github.hectorvent.floci.core.storage;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageBackedMapTest
{
    @Test
    void mapOperationsReadAndWriteThroughStorage()
    {
        InMemoryStorage<String, String> storage = new InMemoryStorage<>();
        StorageBackedMap<String> map = new StorageBackedMap<>(storage);

        assertNull(map.put("alpha", "one"));
        assertEquals("one", storage.get("alpha").orElseThrow());
        assertEquals("one", map.get("alpha"));

        assertEquals("one", map.replace("alpha", "two"));
        assertEquals("two", storage.get("alpha").orElseThrow());

        assertTrue(map.putIfAbsent("beta", "three") == null);
        assertEquals(Map.of("alpha", "two", "beta", "three"), Map.copyOf(map));

        assertTrue(map.remove("alpha", "two"));
        assertFalse(storage.get("alpha").isPresent());
        assertEquals("three", map.remove("beta"));
        assertTrue(map.isEmpty());
    }

    @Test
    void entrySetIteratorRemoveDeletesFromStorage()
    {
        InMemoryStorage<String, String> storage = new InMemoryStorage<>();
        StorageBackedMap<String> map = new StorageBackedMap<>(storage);
        map.put("alpha", "one");

        var iterator = map.entrySet().iterator();
        assertTrue(iterator.hasNext());
        iterator.next();
        iterator.remove();

        assertFalse(storage.get("alpha").isPresent());
        assertTrue(map.isEmpty());
    }
}
