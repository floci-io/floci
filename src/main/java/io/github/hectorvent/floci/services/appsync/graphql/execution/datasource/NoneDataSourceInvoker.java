package io.github.hectorvent.floci.services.appsync.graphql.execution.datasource;

import io.github.hectorvent.floci.services.appsync.graphql.execution.GraphQlRequestContext;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;

/**
 * The NONE data source passes the request document's {@code payload} straight to the response
 * template. AWS defines only {@code version} and {@code payload}; anything else is a template bug.
 */
@ApplicationScoped
public class NoneDataSourceInvoker implements DataSourceInvoker {

    private static final Set<String> ALLOWED_FIELDS = Set.of("version", "payload");

    @Override
    public DataSourceResult invoke(DataSource dataSource, Map<String, Object> request, GraphQlRequestContext context) {
        for (String field : request.keySet()) {
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new InvalidRequestDocumentException("Unsupported field '" + field
                        + "' in request for NONE data source; only version and payload are allowed");
            }
        }
        return DataSourceResult.of(request.get("payload"));
    }
}
