package io.floci.aws.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * JSON file-backed persistent storage.
 * Loads all data into memory on startup for fast reads.
 * Write-through on every put/delete.
 * Uses atomic writes (temp file + rename) for safety.
 */
public class PersistentStorage<K, V> implements StorageBackend<K, V> {

    private static final Logger LOG = Logger.getLogger(PersistentStorage.class);

    private volatile ConcurrentHashMap<K, V> store = new ConcurrentHashMap<>();
    private final Path filePath;
    private final ObjectMapper objectMapper;
    private final TypeReference<Map<K, V>> typeReference;

    public PersistentStorage(Path filePath, TypeReference<Map<K, V>> typeReference) {
        this.filePath = filePath;
        this.typeReference = typeReference;
        this.objectMapper = StorageJson.mapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public synchronized void put(K key, V value) {
        ConcurrentHashMap<K, V> candidate = new ConcurrentHashMap<>(store);
        candidate.put(key, value);
        commit(candidate);
    }

    @Override
    public void putAll(Map<K, V> entries) {
        putAllAtomically(entries);
    }

    @Override
    public synchronized void putAllAtomically(Map<K, V> entries) {
        Map<K, V> replacements = Map.copyOf(entries);
        if (replacements.isEmpty()) {
            return;
        }
        ConcurrentHashMap<K, V> candidate = new ConcurrentHashMap<>(store);
        candidate.putAll(replacements);
        commit(candidate);
    }

    @Override
    public Optional<V> get(K key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public synchronized void delete(K key) {
        ConcurrentHashMap<K, V> candidate = new ConcurrentHashMap<>(store);
        candidate.remove(key);
        commit(candidate);
    }

    @Override
    public synchronized void deleteAllAtomically(Set<K> keys) {
        if (keys.isEmpty()) {
            return;
        }
        ConcurrentHashMap<K, V> candidate = new ConcurrentHashMap<>(store);
        candidate.keySet().removeAll(Set.copyOf(keys));
        commit(candidate);
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
    public boolean isEmpty() {
        return store.isEmpty();
    }

    @Override
    public synchronized void flush() {
        try {
            persistToDisk(store);
        } catch (UncheckedIOException exception) {
            LOG.errorv(exception, "Failed to flush data to {0}", filePath);
        }
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
            // Starting empty here silently drops all persisted state for this store, which can leave
            // other services (e.g. CloudFormation) referencing resources that now appear missing
            // (see issue #1634). Quarantine the unreadable file and log loudly so the data loss is
            // detectable rather than masquerading as an empty store.
            StorageQuarantine.quarantine(filePath, e, LOG);
        }
    }

    @Override
    public synchronized void clear() {
        commit(new ConcurrentHashMap<>());
    }

    private void commit(ConcurrentHashMap<K, V> candidate) {
        persistToDisk(candidate);
        store = candidate;
    }

    private void persistToDisk(Map<K, V> data) {
        try {
            StorageFiles.writeAtomically(filePath, tempFile -> objectMapper.writeValue(tempFile.toFile(), data));
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to persist data to " + filePath, exception);
        }
    }
}
