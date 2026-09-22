package io.github.hectorvent.floci.core.storage;

/**
 * How a store is written, so {@link StorageFactory} can pick a backend that fits the workload
 * under the configured storage mode. The profile describes the data, not a backend: the
 * {@code memory}, {@code hybrid} and {@code wal} modes treat both profiles alike.
 */
public enum WriteProfile {
    /**
     * Small sets that change rarely, such as resource definitions. Under {@code persistent} mode
     * the store file is rewritten in full on every mutation, so it is current after every call.
     */
    DEFAULT,
    /**
     * Append-heavy data such as log events. Under {@code persistent} mode the store is journaled:
     * every mutation is appended to a {@code .wal} file next to the store, and the store file is
     * rewritten only on the WAL compaction interval and at shutdown.
     */
    APPEND_HEAVY
}
