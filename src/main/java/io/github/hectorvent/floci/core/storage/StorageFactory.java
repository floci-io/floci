package io.github.hectorvent.floci.core.storage;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
    // Same backends as allBackends, paired with the service name that first asked for them.
    // Cross-cutting readers (Resource Groups Tagging's estate-wide scan) need to know which
    // service owns a store; the backend itself carries no such label.
    private final List<OwnedBackend> ownedBackends = new ArrayList<>();
    // A file path identifies one logical store: callers sharing a path are expected to agree on
    // its value type and storage mode. The first create() wins; repeat calls reuse that backend.
    private final Map<Path, StorageBackend<?, ?>> backendsByPath = new HashMap<>();
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
    public synchronized <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                 TypeReference<Map<String, V>> typeReference) {
        String mode = resolveMode(serviceName);
        long flushInterval = resolveFlushInterval(serviceName);
        Path basePath = Path.of(config.storage().persistentPath());
        Path filePath = basePath.resolve(fileName);

        // Reuse an existing backend for the same file. Handing out a second backend bound to the
        // same path creates a duplicate in-memory store; on shutdown the stale duplicate flushes
        // after the active instance and clobbers persisted state (issue #1921).
        StorageBackend<?, ?> existing = backendsByPath.get(filePath);
        if (existing != null) {
            LOG.debugv("Reusing existing {0} storage for service {1} (file: {2})", mode, serviceName, filePath);
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<V> typed = (AccountAwareStorageBackend<V>) existing;
            return typed;
        }

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
        ownedBackends.add(new OwnedBackend(serviceName, fileName, backend, isFlatTagMapStore(typeReference)));
        backendsByPath.put(filePath, backend);
        return backend;
    }

    /**
     * A storage backend together with the service name and file name it was created under, and
     * whether its declared value type is itself a flat {@code Map<String, String>}.
     *
     * <p>{@code flatTagMapStore} is what lets the estate-wide tagging scan
     * ({@code TaggedResourceScanner}) recognise a service's side tag-store — several services
     * (Route53, ELBv2, CodeDeploy, Config, CloudFront, Transfer) keep {@code ResourceId → tags}
     * in a store separate from the resource's own model, rather than a {@code tags} field on it.
     * A store registered with that exact shape has nothing in it but tags by construction: no
     * domain model here is declared as a bare string-to-string map, so the signal has no false
     * positives to guard against, and a service added tomorrow with the same shape is covered
     * with no change to the scanner.
     */
    public record OwnedBackend(String serviceName, String fileName, AccountAwareStorageBackend<?> backend,
                               boolean flatTagMapStore) {
        /** Convenience constructor for callers that do not care about the tag-store signal. */
        public OwnedBackend(String serviceName, String fileName, AccountAwareStorageBackend<?> backend) {
            this(serviceName, fileName, backend, false);
        }
    }

    /**
     * True when {@code typeReference} was declared as {@code TypeReference<Map<String, Map<String,
     * String>>>} — i.e. the store's per-key value is itself a flat string-to-string map, with no
     * typed domain fields of its own. Detected from the registered generic type rather than from
     * sniffing stored JSON, so it cannot be confused by a legitimate domain object that happens to
     * have only string fields at the moment it is inspected.
     */
    private static boolean isFlatTagMapStore(TypeReference<?> typeReference) {
        Type outer = typeReference.getType();
        if (!(outer instanceof ParameterizedType outerMap)) {
            return false;
        }
        Type[] outerArgs = outerMap.getActualTypeArguments();
        if (outerArgs.length != 2) {
            return false;
        }
        return isStringToStringMap(outerArgs[1]);
    }

    private static boolean isStringToStringMap(Type type) {
        if (!(type instanceof ParameterizedType pt)) {
            return false;
        }
        if (!(pt.getRawType() instanceof Class<?> rawType) || !Map.class.isAssignableFrom(rawType)) {
            return false;
        }
        Type[] args = pt.getActualTypeArguments();
        return args.length == 2 && String.class.equals(args[0]) && String.class.equals(args[1]);
    }

    /**
     * Every backend created so far, labelled with its owning service. Used by the Resource Groups
     * Tagging estate-wide scan, which has to read resource state it does not own; the list is a
     * snapshot, so a service that registers a store later will not appear until the next call.
     */
    public synchronized List<OwnedBackend> ownedBackends() {
        return List.copyOf(ownedBackends);
    }

    /** Load all storage backends from disk. */
    public synchronized void loadAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.load();
        }
    }

    /** Flush all storage backends to disk. */
    public synchronized void flushAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.flush();
        }
    }

    /** Clear all storage backends. */
    public synchronized void clearAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.clear();
        }
        flushAll();
    }

    /** Shutdown all managed backends (stop schedulers, close connections). */
    public synchronized void shutdownAll() {
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
