package io.github.hectorvent.floci.services.appsync.graphql.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Executes an HTTP resolver request and preserves its status, headers and body for the response handler. */
@ApplicationScoped
public class HttpDataSourceInvoker implements AppSyncDataSourceInvoker {

    private final HttpDataSourceTransport transport;
    private final ObjectMapper mapper;

    @Inject
    public HttpDataSourceInvoker(HttpDataSourceTransport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper;
    }

    @Override
    public DataSourceType type() {
        return DataSourceType.HTTP;
    }

    @Override
    public Object invoke(DataSource dataSource, Object request, String region) {
        HttpDataSourceRequest outgoing = HttpDataSourceRequest.parse(dataSource, request, mapper);
        if (dataSource.getHttpConfig().get("authorizationConfig") != null) {
            throw new AwsException("MappingTemplate", "Unsupported HTTP data source authorization configuration", 400);
        }
        return transport.send(outgoing);
    }
}
