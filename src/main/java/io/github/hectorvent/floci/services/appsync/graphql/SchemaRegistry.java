package io.github.hectorvent.floci.services.appsync.graphql;

import graphql.GraphQL;
import graphql.execution.AsyncExecutionStrategy;
import graphql.execution.AsyncSerialExecutionStrategy;
import graphql.execution.SubscriptionExecutionStrategy;
import graphql.schema.DataFetcher;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import io.github.hectorvent.floci.services.appsync.graphql.auth.AuthFieldWrapper;
import io.github.hectorvent.floci.services.appsync.graphql.resolver.AppSyncResolverExecutor;
import io.github.hectorvent.floci.services.appsync.graphql.resolver.ResolverDataFetcher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SchemaRegistry {
    private final Map<String, GraphQLSchema> schemas = new ConcurrentHashMap<>();
    private final Map<String, GraphQL> engines = new ConcurrentHashMap<>();
    private final AppSyncSchemaParser appSyncSchemaParser;
    private final AuthFieldWrapper authFieldWrapper;
    /**
     * Looked up lazily, not injected: the executor needs {@code AppSyncService} to find a field's
     * resolver, and {@code AppSyncService} needs this registry to install a compiled schema. That
     * is a constructor cycle, which ArC rejects; an {@code Instance} defers the lookup past
     * start-up and breaks it.
     */
    private final Instance<AppSyncResolverExecutor> resolverExecutor;

    public SchemaRegistry(AppSyncSchemaParser appSyncSchemaParser) {
        this(appSyncSchemaParser, null, null);
    }

    public SchemaRegistry(AppSyncSchemaParser appSyncSchemaParser, AuthFieldWrapper authFieldWrapper) {
        this(appSyncSchemaParser, authFieldWrapper, null);
    }

    @Inject
    public SchemaRegistry(AppSyncSchemaParser appSyncSchemaParser, AuthFieldWrapper authFieldWrapper,
                          Instance<AppSyncResolverExecutor> resolverExecutor) {
        this.appSyncSchemaParser = appSyncSchemaParser;
        this.authFieldWrapper = authFieldWrapper;
        this.resolverExecutor = resolverExecutor;
    }

    public void register(String apiId, String sdl) {
        GraphQLSchema schema = appSyncSchemaParser.parse(sdl);
        // Resolver fetchers go on before the auth wrapper, so authorisation still runs first and a
        // denied field never reaches its resolver.
        schema = withResolverFetchers(apiId, schema);
        if (authFieldWrapper != null) {
            schema = authFieldWrapper.wrap(schema);
        }
        schemas.put(apiId, schema);
        engines.put(apiId, buildGraphQL(schema));
    }

    /**
     * Puts a {@link ResolverDataFetcher} on every field of the schema, wrapping whatever fetcher
     * the field had. Every field, because AppSync allows a resolver on any of them, and the fetcher
     * itself decides per call whether one exists.
     */
    private GraphQLSchema withResolverFetchers(String apiId, GraphQLSchema schema) {
        AppSyncResolverExecutor executor = executor();
        if (executor == null) {
            return schema;
        }
        GraphQLCodeRegistry.Builder code = GraphQLCodeRegistry.newCodeRegistry(schema.getCodeRegistry());
        for (GraphQLNamedType type : schema.getAllTypesAsList()) {
            if (!(type instanceof GraphQLObjectType objectType) || objectType.getName().startsWith("__")) {
                continue;
            }
            for (GraphQLFieldDefinition field : objectType.getFieldDefinitions()) {
                if (field.getName().startsWith("__")) {
                    continue;
                }
                FieldCoordinates coords = FieldCoordinates.coordinates(objectType.getName(), field.getName());
                DataFetcher<?> existing = schema.getCodeRegistry().getDataFetcher(coords, field);
                if (existing instanceof ResolverDataFetcher) {
                    continue;
                }
                code.dataFetcher(coords, new ResolverDataFetcher(apiId, objectType.getName(),
                        field.getName(), existing, executor));
            }
        }
        GraphQLCodeRegistry registry = code.build();
        return schema.transform(builder -> builder.codeRegistry(registry));
    }

    /** Null when no executor is available — the unit-test constructors, and nothing else. */
    private AppSyncResolverExecutor executor() {
        if (resolverExecutor == null || resolverExecutor.isUnsatisfied()) {
            return null;
        }
        return resolverExecutor.get();
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
}
