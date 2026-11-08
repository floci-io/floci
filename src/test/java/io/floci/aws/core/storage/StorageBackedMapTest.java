package io.floci.aws.core.storage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Map<String, String>> first = executor.submit(() -> map.computeIfAbsent("us-west-2", key -> {
                calls.incrementAndGet();
                firstMappingEntered.countDown();
                await(releaseFirstMapping);
                return Map.of("first", "one");
            }));
            await(firstMappingEntered);

            Future<Map<String, String>> second = executor.submit(() -> {
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

        Map.Entry<String, String> entry = map.entrySet().iterator().next();

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

        Iterator<Map.Entry<String, String>> iterator = map.entrySet().iterator();
        assertTrue(iterator.hasNext());
        iterator.next();
        iterator.remove();

        assertFalse(storage.get("alpha").isPresent());
        assertTrue(map.isEmpty());
    }

    @Test
    void valuesSkipsKeyDeletedAfterIterationStarted()
    {
        StorageBackedMap<String> map = mapOf("alpha", "beta", "gamma");

        List<String> seen = new ArrayList<>();
        Iterator<String> iterator = map.values().iterator();
        while (iterator.hasNext()) {
            String value = iterator.next();
            if (seen.isEmpty()) {
                map.remove(otherKey(value));
            }
            seen.add(value);
        }

        assertEquals(2, seen.size());
        assertFalse(seen.contains(null));
    }

    @Test
    void entrySetSkipsKeyDeletedAfterIterationStarted()
    {
        StorageBackedMap<String> map = mapOf("alpha", "beta", "gamma");

        List<Map.Entry<String, String>> seen = new ArrayList<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (seen.isEmpty()) {
                map.remove(otherKey(entry.getKey()));
            }
            seen.add(entry);
        }

        assertEquals(2, seen.size());
        for (Map.Entry<String, String> entry : seen) {
            assertNotNull(entry.getValue());
            assertEquals(entry.getKey() + "-value", entry.getValue());
        }
    }

    @Test
    void valuesStreamSurvivesKeyDeletedDuringStream()
    {
        StorageBackedMap<String> map = mapOf("alpha", "beta", "gamma");
        AtomicInteger calls = new AtomicInteger();

        List<String> values = map.values().stream()
                .map(value -> {
                    if (calls.getAndIncrement() == 0) {
                        map.remove(otherKey(value));
                    }
                    return value;
                })
                .toList();

        assertEquals(2, values.size());
        assertFalse(values.contains(null));
    }

    @Test
    void keySetStreamSurvivesKeyDeletedDuringStream()
    {
        StorageBackedMap<String> map = mapOf("alpha", "beta", "gamma");
        AtomicInteger calls = new AtomicInteger();

        List<String> keys = map.keySet().stream()
                .map(key -> {
                    if (calls.getAndIncrement() == 0) {
                        map.remove(otherKey(key));
                    }
                    return key;
                })
                .toList();

        assertEquals(2, keys.size());
        assertFalse(keys.contains(null));
    }

    @Test
    void viewStreamsSeeKeyAddedBeforeTerminalOperation()
    {
        StorageBackedMap<String> map = mapOf("alpha");

        Stream<String> keys = map.keySet().stream();
        Stream<String> values = map.values().stream();
        Stream<Map.Entry<String, String>> entries = map.entrySet().stream();
        map.put("beta", "beta-value");

        assertEquals(Set.of("alpha", "beta"), keys.collect(Collectors.toSet()));
        assertEquals(Set.of("alpha-value", "beta-value"), values.collect(Collectors.toSet()));
        assertEquals(Set.of("alpha", "beta"),
                entries.map(Map.Entry::getKey).collect(Collectors.toSet()));
    }

    @Test
    void iteratorRemoveAfterLookaheadRemovesTheYieldedKey()
    {
        StorageBackedMap<String> map = mapOf("alpha", "beta", "gamma");

        Iterator<Map.Entry<String, String>> iterator = map.entrySet().iterator();
        String removed = iterator.next().getKey();
        // hasNext() reads one element ahead; remove() must still hit the yielded key.
        assertTrue(iterator.hasNext());
        iterator.remove();

        assertFalse(map.containsKey(removed));
        assertEquals(2, map.size());
    }

    @Test
    void iteratorThrowsNoSuchElementAtExhaustion()
    {
        StorageBackedMap<String> map = mapOf("alpha");

        Iterator<String> iterator = map.values().iterator();
        iterator.next();

        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
    }

    @Test
    void entrySetValueWritesThrough()
    {
        StorageBackedMap<String> map = mapOf("alpha");

        Map.Entry<String, String> entry = map.entrySet().iterator().next();
        assertEquals("alpha-value", entry.setValue("updated"));

        assertEquals("updated", entry.getValue());
        assertEquals("updated", map.get("alpha"));
    }

    private static StorageBackedMap<String> mapOf(String... keys)
    {
        StorageBackedMap<String> map = new StorageBackedMap<>(new InMemoryStorage<>());
        for (String key : keys) {
            map.put(key, key + "-value");
        }
        return map;
    }

    // Some key other than the one just yielded, so the deletion lands on a key the
    // iterator has snapshotted but not reached, whatever order storage returns keys in.
    private static String otherKey(String yielded)
    {
        String key = yielded.replace("-value", "");
        return key.equals("gamma") ? "alpha" : "gamma";
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
