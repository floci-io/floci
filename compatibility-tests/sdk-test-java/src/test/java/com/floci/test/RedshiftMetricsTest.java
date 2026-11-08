package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.redshift.RedshiftClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Redshift CloudWatch metrics")
class RedshiftMetricsTest {

    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(150);

    @Test
    @DisplayName("SDK reads HealthStatus and DatabaseConnections of an available cluster from AWS/Redshift")
    void availableClusterPublishesStateMetrics() throws InterruptedException {
        assumeFalse(TestFixtures.isRealAws(), "Relies on Floci publishing within its one-minute tick");
        String clusterId = "compat-rsmetric-" + UUID.randomUUID().toString().substring(0, 8);
        Dimension dimension = Dimension.builder().name("ClusterIdentifier").value(clusterId).build();
        try (RedshiftClient redshift = TestFixtures.redshiftClient();
             CloudWatchClient cloudWatch = TestFixtures.cloudWatchClient()) {
            redshift.createCluster(request -> request.clusterIdentifier(clusterId).nodeType("dc2.large")
                    .masterUsername("admin").masterUserPassword("Secret123"));
            try {
                List<Datapoint> health = awaitDatapoints(cloudWatch, "HealthStatus", dimension);
                assertThat(health).isNotEmpty();
                assertThat(health.get(0).sum()).isEqualTo(1.0);
                assertThat(health.get(0).unitAsString()).isEqualTo("Count");

                List<Datapoint> connections = awaitDatapoints(cloudWatch, "DatabaseConnections", dimension);
                assertThat(connections).isNotEmpty();
                assertThat(connections.get(0).sum()).isGreaterThanOrEqualTo(0.0);
            } finally {
                redshift.deleteCluster(request -> request.clusterIdentifier(clusterId)
                        .skipFinalClusterSnapshot(true));
            }
        }
    }

    private static List<Datapoint> awaitDatapoints(CloudWatchClient cloudWatch, String metricName,
                                                   Dimension dimension) throws InterruptedException {
        TestFixtures.await(() -> !datapoints(cloudWatch, metricName, dimension).isEmpty(),
                dimension.value(), "AWS/Redshift " + metricName + " datapoints",
                PUBLISH_TIMEOUT.toNanos(), 1_000);
        return datapoints(cloudWatch, metricName, dimension);
    }

    private static List<Datapoint> datapoints(CloudWatchClient cloudWatch, String metricName, Dimension dimension) {
        return cloudWatch.getMetricStatistics(request -> request.namespace("AWS/Redshift")
                .metricName(metricName).dimensions(dimension)
                .startTime(Instant.now().minus(Duration.ofMinutes(10)))
                .endTime(Instant.now().plus(Duration.ofMinutes(2)))
                .period(60).statistics(Statistic.SUM)).datapoints();
    }
}
