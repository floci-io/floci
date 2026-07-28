package io.github.hectorvent.floci.services.appsync.graphql;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * Produces the shared {@link StorageBackend} used to store compiled SDL schemas.
 * Shared between {@code AppSyncService} (reads via getIntrospectionSchema) and
 * {@code SchemaCreationWorker} (writes on successful compilation).
 */
@ApplicationScoped
public class SchemaStoreProducer {

    private final StorageBackend<String, String> store;

    @Inject
    public SchemaStoreProducer(StorageFactory storageFactory) {
        this.store = storageFactory.create("appsync", "appsync-schemas.json",
                new TypeReference<Map<String, String>>() {});
    }

    @Produces
    @ApplicationScoped
    public StorageBackend<String, String> produce() {
        return store;
    }
}
