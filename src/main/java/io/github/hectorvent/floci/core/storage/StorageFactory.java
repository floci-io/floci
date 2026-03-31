package io.github.hectorvent.floci.core.storage;

import io.github.hectorvent.floci.config.EmulatorConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Factory that creates StorageBackend instances based on configuration.
 * Tracks all created backends for lifecycle management.
 */
@ApplicationScoped
public class StorageFactory {

    private static final Logger LOG = Logger.getLogger(StorageFactory.class);

    private final EmulatorConfig config;
    private final List<StorageBackend<?, ?>> allBackends = new ArrayList<>();
    private final List<HybridStorage<?, ?>> hybridBackends = new ArrayList<>();
    private final List<WalStorage<?, ?>> walBackends = new ArrayList<>();

    @Inject
    public StorageFactory(EmulatorConfig config) {
        this.config = config;
    }

    /**
     * Create a storage backend for the given service.
     *
     * @param serviceName   the service name (ssm, sqs, s3)
     * @param fileName      the JSON file name for persistent storage
     * @param typeReference Jackson type reference for deserialization
     */
    public <K, V> StorageBackend<K, V> create(String serviceName, String fileName,
                                               TypeReference<Map<K, V>> typeReference) {
        String mode = resolveMode(serviceName);
        long flushInterval = resolveFlushInterval(serviceName);
        Path basePath = Path.of(config.storage().persistentPath());
        Path filePath = basePath.resolve(fileName);

        LOG.infov("Creating {0} storage for service {1} (file: {2})", mode, serviceName, filePath);

        StorageBackend<K, V> backend = switch (mode) {
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

        // load because loadAll() may run before the service is initialized
        backend.load();

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
        String globalMode = config.storage().mode();
        return switch (serviceName) {
            case "ssm" -> config.storage().services().ssm().mode().orElse(globalMode);
            case "sqs" -> config.storage().services().sqs().mode().orElse(globalMode);
            case "s3" -> config.storage().services().s3().mode().orElse(globalMode);
            case "dynamodb" -> config.storage().services().dynamodb().mode().orElse(globalMode);
            case "sns" -> config.storage().services().sns().mode().orElse(globalMode);
            case "lambda" -> config.storage().services().lambda().mode().orElse(globalMode);
            case "cloudwatchlogs" -> config.storage().services().cloudwatchlogs().mode().orElse(globalMode);
            case "cloudwatchmetrics" -> config.storage().services().cloudwatchmetrics().mode().orElse(globalMode);
            case "secretsmanager" -> config.storage().services().secretsmanager().mode().orElse(globalMode);
            default -> globalMode;
        };
    }

    private long resolveFlushInterval(String serviceName) {
        return switch (serviceName) {
            case "ssm" -> config.storage().services().ssm().flushIntervalMs();
            case "dynamodb" -> config.storage().services().dynamodb().flushIntervalMs();
            case "sns" -> config.storage().services().sns().flushIntervalMs();
            case "lambda" -> config.storage().services().lambda().flushIntervalMs();
            case "cloudwatchlogs" -> config.storage().services().cloudwatchlogs().flushIntervalMs();
            case "cloudwatchmetrics" -> config.storage().services().cloudwatchmetrics().flushIntervalMs();
            case "secretsmanager" -> config.storage().services().secretsmanager().flushIntervalMs();
            default -> 5000L;
        };
    }
}
