package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
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

    private final ConcurrentHashMap<K, V> store = new ConcurrentHashMap<>();
    private final Path filePath;
    private final ObjectMapper objectMapper;
    private final TypeReference<Map<K, V>> typeReference;

    public PersistentStorage(Path filePath, TypeReference<Map<K, V>> typeReference) {
        this.filePath = filePath;
        this.typeReference = typeReference;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void put(K key, V value) {
        store.put(key, value);
        persistToDisk();
    }

    @Override
    public Optional<V> get(K key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void delete(K key) {
        store.remove(key);
        persistToDisk();
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
        return Collections.unmodifiableSet(store.keySet());
    }

    @Override
    public void flush() {
        persistToDisk();
    }

    @Override
    public void load() {
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
            LOG.errorv(e, "Failed to load data from {0}", filePath);
        }
    }

    @Override
    public void clear() {
        store.clear();
        persistToDisk();
    }

    private synchronized void persistToDisk() {
        try {
            Files.createDirectories(filePath.getParent());
            Path tempFile = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            objectMapper.writeValue(tempFile.toFile(), store);
            Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.errorv(e, "Failed to persist data to {0}", filePath);
        }
    }
}
