package io.github.hectorvent.floci.services.appsync.graphql;

import graphql.GraphQL;
import graphql.execution.AsyncExecutionStrategy;
import graphql.execution.AsyncSerialExecutionStrategy;
import graphql.execution.SubscriptionExecutionStrategy;
import graphql.schema.GraphQLSchema;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SchemaRegistry {
    private final Map<String, GraphQLSchema> schemas = new ConcurrentHashMap<>();
    private final Map<String, GraphQL> engines = new ConcurrentHashMap<>();
    private final AppSyncSchemaParser appSyncSchemaParser;

    @Inject
    public SchemaRegistry(AppSyncSchemaParser appSyncSchemaParser) {
        this.appSyncSchemaParser = appSyncSchemaParser;
    }

    public void register(String apiId, String sdl) {
        GraphQLSchema schema = appSyncSchemaParser.parse(sdl);
        schemas.put(apiId, schema);
        engines.put(apiId, buildGraphQL(schema));
    }

    public Optional<GraphQLSchema> getSchema(String apiId) {
        return Optional.ofNullable(schemas.get(apiId));
    }

    public Optional<GraphQL> getGraphQL(String apiId) {
        return Optional.ofNullable(engines.get(apiId));
    }

    public void remove(String apiId) {
        schemas.remove(apiId);
        engines.remove(apiId);
    }

    static GraphQL buildGraphQL(GraphQLSchema schema) {
        return GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(new AsyncExecutionStrategy())
                .mutationExecutionStrategy(new AsyncSerialExecutionStrategy())
                .subscriptionExecutionStrategy(new SubscriptionExecutionStrategy())
                .build();
    }
}
