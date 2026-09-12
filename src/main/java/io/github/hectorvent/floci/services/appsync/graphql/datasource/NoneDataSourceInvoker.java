package io.github.hectorvent.floci.services.appsync.graphql.datasource;

import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The {@code NONE} data source: no backing store, the request is the result.
 *
 * <p>AppSync passes the request payload straight through, which is what makes NONE the way to write
 * a local resolver: a pipeline step that only shapes data, or a mutation that just publishes to
 * subscribers. Real AppSync unwraps a {@code payload} member if there is one, and so does this.
 */
@ApplicationScoped
public class NoneDataSourceInvoker implements AppSyncDataSourceInvoker {

    @Override
    public DataSourceType type() {
        return DataSourceType.NONE;
    }

    @Override
    public Object invoke(DataSource dataSource, Object request, String region) {
        if (request instanceof java.util.Map<?, ?> map && map.containsKey("payload")) {
            return map.get("payload");
        }
        return request;
    }
}
