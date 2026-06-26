package io.github.hectorvent.floci.services.appsync.graphql;

import graphql.schema.GraphQLSchema;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SchemaRegistry {
    private final Map<String, GraphQLSchema> schemas = new ConcurrentHashMap<>();
    private final AppSyncSchemaParser appSyncSchemaParser;
    private final SchemaCreationWorker worker;

    @Inject
    public SchemaRegistry(AppSyncSchemaParser appSyncSchemaParser, SchemaCreationWorker worker) {
        this.appSyncSchemaParser = appSyncSchemaParser;
        this.worker = worker;
    }

    public void register(String apiId, String sdl) {
        GraphQLSchema schema = appSyncSchemaParser.parse(sdl);
        schemas.put(apiId, schema);
    }

    /**
     * Submit a schema for async creation. The status is set to PROCESSING
     * synchronously by the caller; this method enqueues the actual parse/register.
     */
    public void submitSchemaCreation(String apiId, String sdl) {
        worker.submit(apiId, sdl);
    }

    public Optional<GraphQLSchema> getSchema(String apiId) {
        return Optional.ofNullable(schemas.get(apiId));
    }

    public void remove(String apiId) {
        schemas.remove(apiId);
    }
}
