package io.github.hectorvent.floci.services.redshift.metrics;

import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService.Datapoint;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService.MetricIdentity;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RedshiftMetricsIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String NAMESPACE = "AWS/Redshift";

    @Inject
    RedshiftService redshift;

    @Inject
    Instance<RedshiftMetricCollector> collectors;

    @Inject
    CloudWatchMetricsService cloudWatch;

    private RedshiftMetricsPublisher publisher;
    private String clusterId;

    @BeforeEach
    void setUp() {
        publisher = new RedshiftMetricsPublisher(redshift, cloudWatch, collectors, true);
    }

    @AfterEach
    void cleanUp() {
        publisher.onStop(null);
        if (clusterId != null) {
            redshift.deleteCluster(clusterId);
            clusterId = null;
        }
    }

    /** The minute a tick stamps its samples with, read back alone so the scheduler's own ticks cannot interfere. */
    private List<Datapoint> statisticsAt(String metricName, String identifier, Instant tick) {
        Instant minute = tick.minusSeconds(30).truncatedTo(ChronoUnit.MINUTES);
        return cloudWatch.getMetricStatistics(NAMESPACE, metricName,
                List.of(new Dimension("ClusterIdentifier", identifier)), minute, minute.plusSeconds(59), 60,
                List.of("Sum"), null, REGION);
    }

    @Test
    void createdClusterPublishesItsStateMetricsReadableThroughCloudWatch() {
        clusterId = "it-metrics-state";
        redshift.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        Instant tick = Instant.now().plusSeconds(100);

        publisher.publish(tick);

        Set<String> names = cloudWatch.listMetrics(NAMESPACE, null,
                        List.of(new Dimension("ClusterIdentifier", clusterId)), REGION).stream()
                .map(MetricIdentity::metricName).collect(Collectors.toSet());
        assertEquals(Set.of("HealthStatus", "MaintenanceMode", "DatabaseConnections"), names);

        List<Datapoint> health = statisticsAt("HealthStatus", clusterId, tick);
        assertEquals(1, health.size());
        assertEquals(1.0, health.getFirst().sum());
        assertEquals("Count", health.getFirst().unit());
        assertEquals(0.0, statisticsAt("DatabaseConnections", clusterId, tick).getFirst().sum());
    }

    @Test
    void deletedClusterStopsPublishing() {
        clusterId = "it-metrics-deleted";
        redshift.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        Instant whileAvailable = Instant.now().plusSeconds(100);
        publisher.publish(whileAvailable);
        assertEquals(1, statisticsAt("HealthStatus", clusterId, whileAvailable).size());

        redshift.deleteCluster(clusterId);
        String deleted = clusterId;
        clusterId = null;
        Instant afterDelete = whileAvailable.plusSeconds(600);
        publisher.publish(afterDelete);

        assertTrue(statisticsAt("HealthStatus", deleted, afterDelete).isEmpty(),
                "a deleted cluster must publish no samples");
    }
}
