package io.floci.aws.services.appsync.graphql;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.appsync.model.SchemaCreationStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * Produces the shared {@link AccountAwareStorageBackend} used to track schema creation
 * status. Lives as a CDI bean so both {@code AppSyncService} (which mutates
 * the status from request threads) and {@code SchemaCreationWorker} (which
 * transitions the status from background worker threads) see the same
 * in-memory state and the same flush cycle.
 */
@ApplicationScoped
public class SchemaStatusStoreProducer {

    private final AccountAwareStorageBackend<SchemaCreationStatus> store;

    @Inject
    public SchemaStatusStoreProducer(StorageFactory storageFactory) {
        this.store = storageFactory.create("appsync", "appsync-schema-status.json",
                new TypeReference<Map<String, SchemaCreationStatus>>() {});
    }

    @Produces
    @ApplicationScoped
    public AccountAwareStorageBackend<SchemaCreationStatus> produce() {
        return store;
    }
}
