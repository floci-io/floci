package io.floci.aws.testing;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.HybridStorage;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.WalStorage;

import java.nio.file.Path;
import java.util.Map;

/** Real storage backends for tests that exercise writes and reload persisted state. */
public final class StorageTestBackends {
    private StorageTestBackends() {
    }

    /** Creates an unloaded backend. The caller owns loading and closing it. */
    public static <V> StorageBackend<String, V> create(String mode, Path snapshot, Path journal,
                                                     TypeReference<Map<String, V>> type, long intervalMillis) {
        return switch (mode) {
            case "memory" -> new InMemoryStorage<>();
            case "persistent" -> new PersistentStorage<>(snapshot, type);
            case "hybrid" -> new HybridStorage<>(snapshot, type, intervalMillis);
            case "wal" -> new WalStorage<>(snapshot, journal, type, intervalMillis);
            default -> throw new IllegalArgumentException("Unsupported test storage mode: " + mode);
        };
    }

    /** Flushes pending state and stops any background storage worker. */
    public static void close(StorageBackend<?, ?> backend) {
        if (backend instanceof HybridStorage<?, ?> hybrid) {
            hybrid.shutdown();
        } else if (backend instanceof WalStorage<?, ?> wal) {
            wal.shutdown();
        } else {
            backend.flush();
        }
    }
}
