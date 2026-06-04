package io.github.hectorvent.floci.services.appsync.graphql;

import graphql.schema.GraphQLSchema;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SchemaRegistry {
    private final Map<String, GraphQLSchema> schemas = new ConcurrentHashMap<>();
    private final AppSyncSchemaParser appSyncSchemaParser;

    @Inject
    public SchemaRegistry(AppSyncSchemaParser appSyncSchemaParser) {
        this.appSyncSchemaParser = appSyncSchemaParser;
    }

    public void register(String apiId, String sdl) {
        GraphQLSchema schema = appSyncSchemaParser.parse(sdl);
        schemas.put(apiId, schema);
    }

    public GraphQLSchema getSchema(String apiId) {
        return schemas.get(apiId);
    }

    public void remove(String apiId) {
        schemas.remove(apiId);
    }

    public boolean hasSchema(String apiId) {
        return schemas.containsKey(apiId);
    }
}
