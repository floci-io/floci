package io.github.hectorvent.floci.core.storage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Generic storage abstraction for AWS emulator services.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public interface StorageBackend<K, V> {

    void put(K key, V value);

    /**
     * Store several entries as one logical write. Backends that can persist a batch more
     * efficiently should override this method; the default preserves compatibility for
     * lightweight and service-specific implementations.
     */
    default void putAll(Map<K, V> entries) {
        entries.forEach(this::put);
    }

    /**
     * Publish replacement values as one batch, including in snapshots and WAL replay.
     * Callers must not mutate values already stored here. Persistent and WAL backends
     * report write failures before publishing; hybrid retains its asynchronous durability.
     * Unsupported backends reject the operation without changing any entries.
     */
    default void putAllAtomically(Map<K, V> entries) {
        throw new UnsupportedOperationException("Atomic batches are not supported by this backend");
    }

    Optional<V> get(K key);

    void delete(K key);

    /** Remove keys as one storage commit, with the same durability guarantees as atomic puts. */
    default void deleteAllAtomically(Set<K> keys) {
        throw new UnsupportedOperationException("Atomic deletions are not supported by this backend");
    }

    /**
     * Return a new mutable list of values whose keys pass the filter. Callers may sort,
     * filter, or otherwise mutate the returned list without affecting the underlying store.
     */
    List<V> scan(Predicate<K> keyFilter);

    /** Return all keys in this store. */
    Set<K> keys();

    /** Persist data to disk if applicable. */
    void flush();

    /** Load data from disk on startup. */
    void load();

    /** Clear all data. */
    void clear();
}
