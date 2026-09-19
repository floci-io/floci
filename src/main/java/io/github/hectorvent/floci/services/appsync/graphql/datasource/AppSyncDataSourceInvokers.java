package io.github.hectorvent.floci.services.appsync.graphql.datasource;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.EnumMap;
import java.util.Map;

/** Maps a data source type to the invoker that serves it. */
@ApplicationScoped
public class AppSyncDataSourceInvokers {

    private final Map<DataSourceType, AppSyncDataSourceInvoker> byType = new EnumMap<>(DataSourceType.class);

    @Inject
    public AppSyncDataSourceInvokers(Instance<AppSyncDataSourceInvoker> invokers) {
        invokers.forEach(invoker -> {
            AppSyncDataSourceInvoker existing = byType.put(invoker.type(), invoker);
            if (existing != null) {
                throw new IllegalStateException("Two AppSync data source invokers for " + invoker.type()
                        + ": " + existing.getClass().getSimpleName() + " and "
                        + invoker.getClass().getSimpleName());
            }
        });
    }

    /** Test constructor: an explicit set, bypassing CDI. */
    public AppSyncDataSourceInvokers(Iterable<AppSyncDataSourceInvoker> invokers) {
        invokers.forEach(invoker -> byType.put(invoker.type(), invoker));
    }

    /**
     * Runs the request against its data source.
     *
     * <p>A resolver whose data source type Floci cannot drive fails the field with that fact.
     * Returning null instead would be indistinguishable from an empty result, which is the failure
     * mode this whole layer exists to remove.
     */
    public Object invoke(DataSource dataSource, Object request, String region) {
        DataSourceType type = dataSource.getType();
        AppSyncDataSourceInvoker invoker = byType.get(type);
        if (invoker == null) {
            throw new AwsException("InternalFailureException",
                    "Floci cannot invoke AppSync data sources of type " + type
                            + " (data source " + dataSource.getName() + ")", 500);
        }
        return invoker.invoke(dataSource, request, region);
    }

    public boolean supports(DataSourceType type) {
        return byType.containsKey(type);
    }
}
