package io.github.hectorvent.floci.core.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Thread-safe in-memory storage backed by ConcurrentHashMap.
 * No persistence — data is lost on shutdown unless explicitly flushed.
 */
public class InMemoryStorage<K, V> implements StorageBackend<K, V> {

    private volatile ConcurrentHashMap<K, V> store = new ConcurrentHashMap<>();

    @Override
    public synchronized void put(K key, V value) {
        store.put(key, value);
    }

    @Override
    public synchronized void putAllAtomically(Map<K, V> entries) {
        ConcurrentHashMap<K, V> candidate = new ConcurrentHashMap<>(store);
        candidate.putAll(Map.copyOf(entries));
        store = candidate;
    }

    @Override
    public Optional<V> get(K key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public synchronized void delete(K key) {
        store.remove(key);
    }

    @Override
    public synchronized void deleteAllAtomically(Set<K> keys) {
        if (keys.isEmpty()) {
            return;
        }
        ConcurrentHashMap<K, V> candidate = new ConcurrentHashMap<>(store);
        candidate.keySet().removeAll(Set.copyOf(keys));
        store = candidate;
    }

    @Override
    public List<V> scan(Predicate<K> keyFilter) {
        List<V> result = new ArrayList<>();
        store.forEach((k, v) -> {
            if (keyFilter.test(k)) {
                result.add(v);
            }
        });
        return result;
    }

    @Override
    public Set<K> keys() {
        return Set.copyOf(store.keySet());
    }

    @Override
    public void flush() {
        // No-op for in-memory storage
    }

    @Override
    public synchronized void load() {
        // No-op for in-memory storage
    }

    @Override
    public synchronized void clear() {
        store.clear();
    }
}
