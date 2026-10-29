package io.github.hectorvent.floci.services.redshift.metrics;

import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * The metrics Floci can report from its own state: health and maintenance follow the cluster status, and
 * DatabaseConnections is the number of clients open on the cluster's auth proxy. Data API connections
 * reach the database around that proxy and are not counted.
 */
@ApplicationScoped
public class RedshiftClusterStateMetricCollector implements RedshiftMetricCollector {

    private static final String COUNT = "Count";

    private final RedshiftProxyManager proxyManager;

    @Inject
    public RedshiftClusterStateMetricCollector(RedshiftProxyManager proxyManager) {
        this.proxyManager = proxyManager;
    }

    @Override
    public List<MetricDatum> collect(RedshiftMetricTarget target, long timestampSeconds) {
        if (!"available".equals(target.cluster().getClusterStatus())) {
            return List.of();
        }
        String identifier = target.cluster().getClusterIdentifier();
        List<Dimension> dimensions = List.of(new Dimension("ClusterIdentifier", identifier));
        List<MetricDatum> datums = new ArrayList<>(List.of(
                MetricDatum.of("HealthStatus", COUNT, 1, dimensions, timestampSeconds),
                MetricDatum.of("MaintenanceMode", COUNT, 0, dimensions, timestampSeconds)));
        OptionalInt connections = proxyManager.activeConnections(target.proxyKey());
        if (connections.isPresent()) {
            datums.add(MetricDatum.of("DatabaseConnections", COUNT, connections.getAsInt(), dimensions,
                    timestampSeconds));
        }
        return datums;
    }
}
