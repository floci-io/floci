package io.github.hectorvent.floci.services.appsync.graphql.execution;

import graphql.GraphQLError;
import graphql.execution.DataFetcherResult;
import graphql.language.AstPrinter;
import graphql.language.SourceLocation;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLObjectType;
import graphql.schema.PropertyDataFetcher;
import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.Resolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single data fetcher installed for every schema field. Fields with an attached resolver run
 * through {@link UnitResolverRuntime}; fields without one read the property from the parent map,
 * as AppSync does. Never throws: every failure becomes an AppSync-shaped error on the field.
 */
@ApplicationScoped
public class AppSyncFieldDataFetcher implements DataFetcher<Object> {

    private static final Logger LOG = Logger.getLogger(AppSyncFieldDataFetcher.class);

    private final Instance<AppSyncService> appSyncService;
    private final UnitResolverRuntime runtime;

    @Inject
    public AppSyncFieldDataFetcher(Instance<AppSyncService> appSyncService, UnitResolverRuntime runtime) {
        this.appSyncService = appSyncService;
        this.runtime = runtime;
    }

    @Override
    public Object get(DataFetchingEnvironment env) {
        GraphQLObjectType parent = env.getExecutionStepInfo().getObjectType();
        String typeName = parent.getName();
        String fieldName = env.getField().getName();
        try {
            return resolve(env, typeName, fieldName);
        } catch (RuntimeException e) {
            LOG.errorv(e, "Resolver {0}.{1} failed unexpectedly", typeName, fieldName);
            return toResult(FieldOutcome.ofError(FieldError.of("InternalFailure", e.getMessage())), env);
        }
    }

    private Object resolve(DataFetchingEnvironment env, String typeName, String fieldName) {
        GraphQlRequestContext rc = env.getGraphQlContext().get(GraphQlRequestContext.CONTEXT_KEY);
        if (rc == null) {
            return toResult(FieldOutcome.ofError(FieldError.of("InternalFailure",
                    "GraphQL request context is missing")), env);
        }
        Optional<Resolver> resolver = appSyncService.get().findResolver(rc.apiId(), typeName, fieldName);
        if (resolver.isEmpty()) {
            return PropertyDataFetcher.fetching(fieldName).get(env);
        }

        DataSource dataSource = null;
        String dataSourceName = resolver.get().getDataSourceName();
        if (dataSourceName != null && !dataSourceName.isBlank()) {
            dataSource = appSyncService.get().findDataSource(rc.apiId(), dataSourceName).orElse(null);
        }
        ResolverInvocation invocation = new ResolverInvocation(rc, resolver.get(), dataSource,
                env.getArguments(), sourceMap(env), infoMap(env, rc));
        return toResult(runtime.execute(invocation), env);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sourceMap(DataFetchingEnvironment env) {
        Object source = env.getSource();
        return source instanceof Map<?, ?> ? (Map<String, Object>) source : Map.of();
    }

    private static Map<String, Object> infoMap(DataFetchingEnvironment env, GraphQlRequestContext rc) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("fieldName", env.getField().getName());
        info.put("parentTypeName", env.getExecutionStepInfo().getObjectType().getName());
        info.put("variables", rc.variables() != null ? rc.variables() : Map.of());
        info.put("selectionSetList", SelectionSetPaths.of(env.getSelectionSet()));
        info.put("selectionSetGraphQL", env.getField().getSelectionSet() != null
                ? AstPrinter.printAst(env.getField().getSelectionSet()) : "");
        return info;
    }

    private static DataFetcherResult<Object> toResult(FieldOutcome outcome, DataFetchingEnvironment env) {
        List<GraphQLError> errors = new ArrayList<>();
        for (FieldError appended : outcome.appended()) {
            errors.add(toGraphQlError(appended, env));
        }
        if (outcome.isError()) {
            errors.add(toGraphQlError(outcome.error(), env));
            return DataFetcherResult.newResult().data(null).errors(errors).build();
        }
        return DataFetcherResult.newResult().data(outcome.data()).errors(errors).build();
    }

    private static AppSyncGraphQlError toGraphQlError(FieldError error, DataFetchingEnvironment env) {
        List<Object> path = env.getExecutionStepInfo().getPath().toList();
        SourceLocation location = env.getField().getSourceLocation();
        List<SourceLocation> locations = location != null ? List.of(location) : List.of();
        Object filteredData = SelectionSetFilter.filter(error.data(), env.getSelectionSet());
        return new AppSyncGraphQlError(error.errorType(), error.message(), filteredData, error.errorInfo(),
                path, locations);
    }
}
