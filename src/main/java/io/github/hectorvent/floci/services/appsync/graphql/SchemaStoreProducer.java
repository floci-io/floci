package io.github.hectorvent.floci.services.appsync.graphql;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * Produces the shared {@link AccountAwareStorageBackend} used to store compiled SDL schemas.
 * Shared between {@code AppSyncService} (reads via getIntrospectionSchema) and
 * {@code SchemaCreationWorker} (writes on successful compilation / rehydrates on boot).
 */
@ApplicationScoped
public class SchemaStoreProducer {

    private final AccountAwareStorageBackend<String> store;

    @Inject
    public SchemaStoreProducer(StorageFactory storageFactory) {
        this.store = storageFactory.create("appsync", "appsync-schemas.json",
                new TypeReference<Map<String, String>>() {});
    }

    @Produces
    @ApplicationScoped
    public AccountAwareStorageBackend<String> produce() {
        return store;
    }
}
