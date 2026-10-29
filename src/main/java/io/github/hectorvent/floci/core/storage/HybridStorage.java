package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Hybrid storage: in-memory reads with async flush to disk.
 * Writes go to memory immediately, then flushed to disk periodically.
 */
public class HybridStorage<K, V> implements StorageBackend<K, V> {

    private static final Logger LOG = Logger.getLogger(HybridStorage.class);

    private volatile ConcurrentHashMap<K, V> store = new ConcurrentHashMap<>();
    private final Path filePath;
    private final ObjectMapper objectMapper;
    private final TypeReference<Map<K, V>> typeReference;
    private final ScheduledExecutorService scheduler;
    private final Object flushLock = new Object();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public HybridStorage(Path filePath, TypeReference<Map<K, V>> typeReference, long flushIntervalMs) {
        this.filePath = filePath;
        this.typeReference = typeReference;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        this.scheduler = StorageFiles.scheduleEvery("hybrid-storage-flush", flushIntervalMs, this::flushIfDirty);
    }

    @Override
    public synchronized void put(K key, V value) {
        store.put(key, value);
        dirty.set(true);
    }

    @Override
    public synchronized void putAllAtomically(Map<K, V> entries) {
        ConcurrentHashMap<K, V> candidate = new ConcurrentHashMap<>(store);
        candidate.putAll(Map.copyOf(entries));
        store = candidate;
        dirty.set(true);
    }

    @Override
    public Optional<V> get(K key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public synchronized void delete(K key) {
        store.remove(key);
        dirty.set(true);
    }

    @Override
    public synchronized void deleteAllAtomically(Set<K> keys) {
        if (keys.isEmpty()) {
            return;
        }
        ConcurrentHashMap<K, V> candidate = new ConcurrentHashMap<>(store);
        candidate.keySet().removeAll(Set.copyOf(keys));
        store = candidate;
        dirty.set(true);
    }

    @Override
    public List<V> scan(Predicate<K> keyFilter) {
        return store.entrySet().stream()
                .filter(e -> keyFilter.test(e.getKey()))
                .map(Map.Entry::getValue)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public Set<K> keys() {
        return Set.copyOf(store.keySet());
    }

    @Override
    public void flush() {
        persistToDisk();
    }

    @Override
    public synchronized void load() {
        if (!Files.exists(filePath)) {
            LOG.debugv("No persistent file found at {0}, starting with empty store", filePath);
            return;
        }
        try {
            Map<K, V> data = objectMapper.readValue(filePath.toFile(), typeReference);
            store.clear();
            store.putAll(data);
            LOG.infov("Loaded {0} entries from {1}", store.size(), filePath);
        } catch (IOException e) {
            StorageQuarantine.quarantine(filePath, e, LOG);
        }
    }

    @Override
    public synchronized void clear() {
        store.clear();
        dirty.set(true);
    }

    public void shutdown() {
        StorageFiles.stop(scheduler);
        flush();
    }

    private void flushIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            persistToDisk();
        }
    }

    private void persistToDisk() {
        synchronized (flushLock) {
            Map<K, V> snapshot = store;
            try {
                StorageFiles.writeAtomically(filePath, tempFile -> objectMapper.writeValue(tempFile.toFile(), snapshot));
                LOG.debugv("Flushed {0} entries to {1}", snapshot.size(), filePath);
            } catch (IOException e) {
                LOG.errorv(e, "Failed to persist data to {0}", filePath);
                dirty.set(true);
            }
        }
    }
}
