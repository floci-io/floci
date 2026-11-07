package io.floci.aws.services.appsync.graphql.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.appsync.model.DataSource;
import io.floci.aws.services.appsync.model.DataSourceType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Executes an HTTP resolver request and preserves its status, headers and body for the response handler. */
@ApplicationScoped
public class HttpDataSourceInvoker implements AppSyncDataSourceInvoker {

    private final HttpDataSourceTransport transport;
    private final ObjectMapper mapper;
    private final AwsIamHttpDataSourceInvoker aws;

    @Inject
    public HttpDataSourceInvoker(HttpDataSourceTransport transport, ObjectMapper mapper, AwsIamHttpDataSourceInvoker aws) {
        this.transport = transport;
        this.mapper = mapper;
        this.aws = aws;
    }

    @Override
    public DataSourceType type() {
        return DataSourceType.HTTP;
    }

    @Override
    public Object invoke(DataSource dataSource, Object request, String region) {
        HttpDataSourceRequest outgoing = HttpDataSourceRequest.parse(dataSource, request, mapper);
        if (dataSource.getHttpConfig().get("authorizationConfig") != null) {
            return aws.invoke(dataSource, outgoing, region);
        }
        return transport.send(outgoing);
    }
}
