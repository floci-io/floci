package io.github.hectorvent.floci.services.redshift.metrics;

import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftClusterStateMetricCollectorTest {

    private static final String KEY = "proxy-key-supplied-by-redshift-service";
    private static final long TIMESTAMP = 1_793_102_340L;

    private final RedshiftProxyManager proxies = mock(RedshiftProxyManager.class);
    private final RedshiftClusterStateMetricCollector collector = new RedshiftClusterStateMetricCollector(proxies);

    private static RedshiftMetricTarget target(String status) {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("warehouse");
        cluster.setClusterStatus(status);
        return new RedshiftMetricTarget("111111111111", "us-east-1", cluster, KEY);
    }

    private static Map<String, MetricDatum> byName(List<MetricDatum> datums) {
        return datums.stream().collect(Collectors.toMap(MetricDatum::getMetricName, Function.identity()));
    }

    @Test
    void availableClusterReportsHealthMaintenanceAndConnections() {
        when(proxies.activeConnections(KEY)).thenReturn(OptionalInt.of(3));

        Map<String, MetricDatum> datums = byName(collector.collect(target("available"), TIMESTAMP));

        assertEquals(3, datums.size());
        assertEquals(1.0, datums.get("HealthStatus").getValue());
        assertEquals(0.0, datums.get("MaintenanceMode").getValue());
        assertEquals(3.0, datums.get("DatabaseConnections").getValue());
        for (MetricDatum datum : datums.values()) {
            assertEquals("Count", datum.getUnit());
            assertEquals(TIMESTAMP, datum.getTimestamp());
            assertEquals(List.of(new Dimension("ClusterIdentifier", "warehouse")), datum.getDimensions());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"creating", "deleting", "failed", "unavailable"})
    void clusterNotAvailableReportsNothing(String status) {
        when(proxies.activeConnections(KEY)).thenReturn(OptionalInt.of(3));

        assertTrue(collector.collect(target(status), TIMESTAMP).isEmpty());
    }

    @Test
    void missingProxyOmitsDatabaseConnections() {
        when(proxies.activeConnections(KEY)).thenReturn(OptionalInt.empty());

        Map<String, MetricDatum> datums = byName(collector.collect(target("available"), TIMESTAMP));

        assertEquals(2, datums.size());
        assertTrue(datums.containsKey("HealthStatus"));
        assertTrue(datums.containsKey("MaintenanceMode"));
    }
}
