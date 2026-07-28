package io.github.hectorvent.floci.services.appsync.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.appsync.model.SchemaCreationStatus;
import io.github.hectorvent.floci.services.appsync.model.SchemaCreationStatusType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Async worker that processes schema creation jobs in background threads.
 * Mirrors AWS AppSync behavior where StartSchemaCreation is asynchronous:
 * the request returns PROCESSING immediately, and the schema is compiled
 * in the background, transitioning to ACTIVE (success) or FAILED (error).
 */
@ApplicationScoped
public class SchemaCreationWorker {

    private static final Logger LOG = Logger.getLogger(SchemaCreationWorker.class);

    private final SchemaRegistry schemaRegistry;
    private final AccountAwareStorageBackend<SchemaCreationStatus> schemaStatusStore;
    private final StorageBackend<String, String> schemaStore;
    private final EmulatorConfig config;
    private final ObjectMapper objectMapper;

    private ExecutorService executor;

    @Inject
    public SchemaCreationWorker(SchemaRegistry schemaRegistry,
                                AccountAwareStorageBackend<SchemaCreationStatus> schemaStatusStore,
                                StorageBackend<String, String> schemaStore,
                                EmulatorConfig config,
                                ObjectMapper objectMapper) {
        this.schemaRegistry = schemaRegistry;
        this.schemaStatusStore = schemaStatusStore;
        this.schemaStore = schemaStore;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        int threads = config.services().appsync().schemaWorkerThreads();
        executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "appsync-schema-worker");
            t.setDaemon(true);
            return t;
        });
        LOG.infov("SchemaCreationWorker started with {0} threads", threads);
    }

    @PreDestroy
    void shutdown() {
        if (executor == null) {
            return;
        }
        int timeout = config.services().appsync().schemaWorkerShutdownTimeoutSeconds();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout, TimeUnit.SECONDS)) {
                LOG.warnv("Schema workers did not finish within {0}s, forcing shutdown", timeout);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void submit(String apiId, String sdl, String accountId) {
        executor.submit(() -> process(apiId, sdl, accountId));
    }

    private void process(String apiId, String sdl, String accountId) {
        try {
            schemaRegistry.register(apiId, sdl);
            schemaStore.put(apiId, sdl);
            markStatus(accountId, apiId, SchemaCreationStatusType.SUCCESS, null);
            LOG.infov("Schema creation completed for API {0}", apiId);
        } catch (AwsException e) {
            String details = serializeExtendedData(e);
            markStatus(accountId, apiId, SchemaCreationStatusType.FAILED, details);
            LOG.warnv("Schema creation failed for API {0}: {1}", apiId, e.getMessage());
        } catch (RuntimeException e) {
            markStatus(accountId, apiId, SchemaCreationStatusType.FAILED, e.getMessage());
            LOG.errorv(e, "Unexpected error during schema creation for API {0}", apiId);
        }
    }

    private void markStatus(String accountId, String apiId, SchemaCreationStatusType status, String details) {
        SchemaCreationStatus s = new SchemaCreationStatus();
        s.setStatus(status);
        s.setAccountId(accountId);
        if (details != null) {
            s.setDetails(details);
        }
        schemaStatusStore.putForAccount(accountId, apiId, s);
    }

    private String serializeExtendedData(AwsException e) {
        if (e.getExtendedData() == null) {
            return e.getMessage();
        }
        try {
            return objectMapper.writeValueAsString(e.getExtendedData());
        } catch (JsonProcessingException ex) {
            return e.getMessage();
        }
    }

    /**
     * Used at startup to recover orphan PROCESSING statuses from a previous run.
     */
    public void recoverOrphans() {
        int recovered = 0;
        Map<String, SchemaCreationStatus> allEntries = schemaStatusStore.scanAllAccountsAsMap();
        for (Map.Entry<String, SchemaCreationStatus> entry : allEntries.entrySet()) {
            SchemaCreationStatus s = entry.getValue();
            if (s.getStatus() == SchemaCreationStatusType.PROCESSING) {
                String apiId = entry.getKey();
                String accountId = s.getAccountId();
                if (accountId == null) {
                    accountId = "000000000000";
                }
                s.setStatus(SchemaCreationStatusType.FAILED);
                if (s.getDetails() == null) {
                    s.setDetails("{\"reason\":\"ORPHAN_PROCESSING\",\"message\":\"Recovered from orphan PROCESSING on emulator startup\"}");
                }
                schemaStatusStore.putForAccount(accountId, apiId, s);
                recovered++;
                LOG.warnv("Marked orphan schema creation for API {0} as FAILED", apiId);
            }
        }
        if (recovered > 0) {
            LOG.infov("Recovered {0} orphan schema creation(s) on startup", recovered);
        }
    }
}
