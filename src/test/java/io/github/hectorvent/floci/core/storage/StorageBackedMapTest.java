package io.github.hectorvent.floci.core.storage;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertTrue(map.containsKey("alpha"));

        assertEquals("one", map.replace("alpha", "two"));
        assertEquals("two", storage.get("alpha").orElseThrow());

        assertNull(map.putIfAbsent("beta", "three"));
        assertEquals(Map.of("alpha", "two", "beta", "three"), Map.copyOf(map));

        assertTrue(map.remove("alpha", "two"));
        assertFalse(storage.get("alpha").isPresent());
        assertEquals("three", map.remove("beta"));
        assertTrue(map.isEmpty());
    }

    @Test
    void rejectsNullValues()
    {
        InMemoryStorage<String, String> storage = new InMemoryStorage<>();
        StorageBackedMap<String> map = new StorageBackedMap<>(storage);

        assertThrows(NullPointerException.class, () -> map.put("alpha", null));
        assertFalse(storage.get("alpha").isPresent());
    }

    @Test
    void conditionalRemoveOnlyDeletesMatchingValues()
    {
        InMemoryStorage<String, String> storage = new InMemoryStorage<>();
        StorageBackedMap<String> map = new StorageBackedMap<>(storage);
        map.put("alpha", "one");

        assertFalse(map.remove("alpha", "two"));
        assertEquals("one", storage.get("alpha").orElseThrow());

        assertTrue(map.remove("alpha", "one"));
        assertFalse(storage.get("alpha").isPresent());
    }

    @Test
    void computeIfAbsentCreatesOneValueUnderConcurrentAccess()
            throws Exception
    {
        InMemoryStorage<String, Map<String, String>> storage = new InMemoryStorage<>();
        StorageBackedMap<Map<String, String>> map = new StorageBackedMap<>(storage);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstMappingEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstMapping = new CountDownLatch(1);
        CountDownLatch secondCallStarted = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> map.computeIfAbsent("us-west-2", key -> {
                calls.incrementAndGet();
                firstMappingEntered.countDown();
                await(releaseFirstMapping);
                return Map.of("first", "one");
            }));
            await(firstMappingEntered);

            var second = executor.submit(() -> {
                secondCallStarted.countDown();
                return map.computeIfAbsent("us-west-2", key -> {
                    calls.incrementAndGet();
                    return Map.of("second", "two");
                });
            });
            await(secondCallStarted);
            assertTimesOut(second);
            releaseFirstMapping.countDown();

            Map<String, String> firstValue = first.get(5, TimeUnit.SECONDS);
            Map<String, String> secondValue = second.get(5, TimeUnit.SECONDS);

            assertEquals(1, calls.get());
            assertEquals(firstValue, secondValue);
            assertEquals(firstValue, storage.get("us-west-2").orElseThrow());
        }
    }

    @Test
    void conditionalReplaceOnlyUpdatesMatchingValues()
    {
        InMemoryStorage<String, String> storage = new InMemoryStorage<>();
        StorageBackedMap<String> map = new StorageBackedMap<>(storage);
        map.put("alpha", "one");

        assertFalse(map.replace("alpha", "two", "three"));
        assertEquals("one", storage.get("alpha").orElseThrow());

        assertTrue(map.replace("alpha", "one", "three"));
        assertEquals("three", storage.get("alpha").orElseThrow());
    }

    @Test
    void entrySetSetValueWritesThroughStorage()
    {
        InMemoryStorage<String, String> storage = new InMemoryStorage<>();
        StorageBackedMap<String> map = new StorageBackedMap<>(storage);
        map.put("alpha", "one");

        var entry = map.entrySet().iterator().next();

        assertEquals("one", entry.setValue("two"));
        assertEquals("two", storage.get("alpha").orElseThrow());
        assertEquals("two", entry.getValue());
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

    private static void await(CountDownLatch latch)
    {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void assertTimesOut(Future<?> future)
            throws InterruptedException, ExecutionException
    {
        try {
            future.get(250, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException expected) {
            return;
        }
        throw new AssertionError("future completed before the first mapping function was released");
    }
}
