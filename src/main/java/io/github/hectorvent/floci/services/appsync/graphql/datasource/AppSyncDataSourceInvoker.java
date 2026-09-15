package io.github.hectorvent.floci.services.appsync.graphql.datasource;

import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;

/**
 * Carries out the request a resolver's {@code request()} handler returned, against one kind of data
 * source, and hands back what the {@code response()} handler will see as {@code ctx.result}.
 *
 * <p>One implementation per {@link DataSourceType}, discovered by CDI through
 * {@link AppSyncDataSourceInvokers}, the same shape as the CloudFormation provisioner registry, so
 * a new data source type is a new class rather than another arm of a switch.
 *
 * <p>Both ends speak plain JSON-compatible data. That is not incidental: AppSync's JS resolvers
 * build their request as a plain object and read the result as one, so a DynamoDB invoker has to
 * unmarshal attribute values rather than pass them through.
 */
public interface AppSyncDataSourceInvoker {

    DataSourceType type();

    /**
     * @param dataSource the resolved data source, for its config (table name, cluster, function)
     * @param request    whatever the {@code request()} handler returned
     * @param region     the region the API lives in
     * @return the value to expose as {@code ctx.result}
     */
    Object invoke(DataSource dataSource, Object request, String region);
}
