package io.github.hectorvent.floci.services.appsync.graphql;

import io.github.hectorvent.floci.core.common.Resettable;

import graphql.GraphQL;
import graphql.execution.AsyncExecutionStrategy;
import graphql.execution.AsyncSerialExecutionStrategy;
import graphql.execution.SubscriptionExecutionStrategy;
import graphql.schema.GraphQLSchema;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AuthFieldWrapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SchemaRegistry implements Resettable {
    private final Map<String, GraphQLSchema> schemas = new ConcurrentHashMap<>();
    private final Map<String, GraphQL> engines = new ConcurrentHashMap<>();
    private final AppSyncSchemaParser appSyncSchemaParser;
    private final AuthFieldWrapper authFieldWrapper;

    public SchemaRegistry(AppSyncSchemaParser appSyncSchemaParser) {
        this(appSyncSchemaParser, null);
    }

    @Inject
    public SchemaRegistry(AppSyncSchemaParser appSyncSchemaParser, AuthFieldWrapper authFieldWrapper) {
        this.appSyncSchemaParser = appSyncSchemaParser;
        this.authFieldWrapper = authFieldWrapper;
    }

    public void register(String apiId, String sdl) {
        GraphQLSchema schema = appSyncSchemaParser.parse(sdl);
        if (authFieldWrapper != null) {
            schema = authFieldWrapper.wrap(schema);
        }
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

    public static GraphQL buildGraphQL(GraphQLSchema schema) {
        return GraphQL.newGraphQL(schema)
                .queryExecutionStrategy(new AsyncExecutionStrategy())
                .mutationExecutionStrategy(new AsyncSerialExecutionStrategy())
                .subscriptionExecutionStrategy(new SubscriptionExecutionStrategy())
                .build();
    }

    /**
     * Compiled schemas and engines are keyed by API id and would be served for APIs the reset removed.
     */
    @Override
    public void clear() {
        schemas.clear();
        engines.clear();
    }
}
