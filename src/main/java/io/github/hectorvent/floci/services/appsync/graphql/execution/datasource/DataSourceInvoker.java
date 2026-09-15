package io.github.hectorvent.floci.services.appsync.graphql.execution.datasource;

import io.github.hectorvent.floci.services.appsync.graphql.execution.GraphQlRequestContext;
import io.github.hectorvent.floci.services.appsync.model.DataSource;

import java.util.Map;

/** Executes the request document produced by a request mapping template against a data source. */
public interface DataSourceInvoker {

    /**
     * @param request the parsed request document (always a JSON object whose {@code version} was
     *                already validated by the caller)
     */
    DataSourceResult invoke(DataSource dataSource, Map<String, Object> request, GraphQlRequestContext context);
}
