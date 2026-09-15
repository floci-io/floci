package io.github.hectorvent.floci.services.appsync.graphql.resolver;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.github.hectorvent.floci.services.appsync.model.Resolver;

/**
 * The data fetcher on every field of an AppSync schema: runs the field's resolver if it has one,
 * and otherwise defers to the fetcher that was there before.
 *
 * <p>The resolver is looked up per call rather than bound when the schema is registered, because
 * the two are created independently and in either order. A CloudFormation stack registers the
 * schema first and attaches nineteen resolvers to it afterwards; binding at registration time would
 * leave every one of them unreachable until the schema happened to be re-registered.
 *
 * <p>Deferring to the previous fetcher is what keeps a field with no resolver working: a nested
 * object's fields are read off the parent's value that the parent's resolver returned, which is
 * graphql-java's property fetcher doing its normal job.
 */
public class ResolverDataFetcher implements DataFetcher<Object> {

    private final String apiId;
    private final String typeName;
    private final String fieldName;
    private final DataFetcher<?> delegate;
    private final AppSyncResolverExecutor executor;

    public ResolverDataFetcher(String apiId, String typeName, String fieldName,
                               DataFetcher<?> delegate, AppSyncResolverExecutor executor) {
        this.apiId = apiId;
        this.typeName = typeName;
        this.fieldName = fieldName;
        this.delegate = delegate;
        this.executor = executor;
    }

    @Override
    public Object get(DataFetchingEnvironment environment) throws Exception {
        Resolver resolver = executor.findResolver(apiId, typeName, fieldName);
        if (resolver == null) {
            return delegate == null ? null : delegate.get(environment);
        }
        return executor.execute(apiId, resolver, environment);
    }

    /** The fetcher this one falls back to, for tests and for re-wrapping. */
    public DataFetcher<?> delegate() {
        return delegate;
    }
}
