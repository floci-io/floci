package io.github.hectorvent.floci.core.storage;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Factory that creates {@link AccountAwareStorageBackend} instances based on configuration.
 * Every backend is wrapped in an account-aware decorator so resources are automatically
 * namespaced by the account ID of the calling credential.
 * Tracks all created backends for lifecycle management.
 */
@ApplicationScoped
public class StorageFactory {

    private static final Logger LOG = Logger.getLogger(StorageFactory.class);

    private final EmulatorConfig config;
    private final ServiceConfigAccess serviceConfigAccess;
    private final List<StorageBackend<?, ?>> allBackends = new ArrayList<>();
    private final List<HybridStorage<?, ?>> hybridBackends = new ArrayList<>();
    private final List<WalStorage<?, ?>> walBackends = new ArrayList<>();

    @Inject
    Instance<RequestContext> requestContextInstance;

    @Inject
    public StorageFactory(EmulatorConfig config, ServiceConfigAccess serviceConfigAccess) {
        this.config = config;
        this.serviceConfigAccess = serviceConfigAccess;
    }

    /**
     * Create an account-aware storage backend for the given service.
     * All keys are automatically prefixed with the current account ID derived from
     * the request credential. Async workers should use the {@code *ForAccount} overloads
     * on {@link AccountAwareStorageBackend} with the account ID stored on the resource model.
     *
     * @param serviceName   the service name (ssm, sqs, s3, …)
     * @param fileName      the JSON file name for persistent storage
     * @param typeReference Jackson type reference for deserialization
     */
    public <V> StorageBackend<String, V> create(String serviceName, String fileName,
                                                 TypeReference<Map<String, V>> typeReference) {
        String mode = resolveMode(serviceName);
        long flushInterval = resolveFlushInterval(serviceName);
        Path basePath = Path.of(config.storage().persistentPath());
        Path filePath = basePath.resolve(fileName);

        LOG.debugv("Creating {0} storage for service {1} (file: {2})", mode, serviceName, filePath);

        StorageBackend<String, V> inner = switch (mode) {
            case "memory" -> new InMemoryStorage<>();
            case "persistent" -> new PersistentStorage<>(filePath, typeReference);
            case "hybrid" -> {
                var hybrid = new HybridStorage<>(filePath, typeReference, flushInterval);
                hybridBackends.add(hybrid);
                yield hybrid;
            }
            case "wal" -> {
                Path snapshotPath = basePath.resolve(fileName.replace(".json", "-snapshot.json"));
                Path walFilePath = basePath.resolve(fileName.replace(".json", ".wal"));
                long compactionInterval = config.storage().wal().compactionIntervalMs();
                var wal = new WalStorage<>(snapshotPath, walFilePath, typeReference, compactionInterval);
                walBackends.add(wal);
                yield wal;
            }
            default -> throw new IllegalArgumentException("Unknown storage mode: " + mode);
        };

        inner.load();

        AccountAwareStorageBackend<V> backend = new AccountAwareStorageBackend<>(
                inner, requestContextInstance, config.defaultAccountId());
        allBackends.add(backend);
        return backend;
    }

    /** Load all storage backends from disk. */
    public void loadAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.load();
        }
    }

    /** Flush all storage backends to disk. */
    public void flushAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.flush();
        }
    }

    /** Shutdown all managed backends (stop schedulers, close connections). */
    public void shutdownAll() {
        for (HybridStorage<?, ?> hybrid : hybridBackends) {
            hybrid.shutdown();
        }
        for (WalStorage<?, ?> wal : walBackends) {
            wal.shutdown();
        }
        flushAll();
    }

    private String resolveMode(String serviceName) {
        return serviceConfigAccess.storageMode(serviceName);
    }

    private long resolveFlushInterval(String serviceName) {
        return serviceConfigAccess.storageFlushInterval(serviceName);
    }
}
