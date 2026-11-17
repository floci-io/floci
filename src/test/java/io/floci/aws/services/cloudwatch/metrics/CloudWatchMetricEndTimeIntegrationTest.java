package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CloudWatchMetricEndTimeIntegrationTest {
    @Test
    void sdkHourlyWindowsDoNotDoubleCountTheSharedBoundary() {
        String namespace = "EndBoundary/" + UUID.randomUUID();
        Instant start = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(2, ChronoUnit.HOURS);
        Instant middle = start.plus(1, ChronoUnit.HOURS);
        Instant end = middle.plus(1, ChronoUnit.HOURS);
        try (CloudWatchClient client = AwsTestClients.create(CloudWatchClient.builder(), "test", Region.US_EAST_1)) {
            client.putMetricData(request -> request.namespace(namespace).metricData(
                    MetricDatum.builder().metricName("Counter").timestamp(start).value(3.0).build(),
                    MetricDatum.builder().metricName("Counter").timestamp(middle).value(7.0).build(),
                    MetricDatum.builder().metricName("Counter").timestamp(end).value(11.0).build()));
            assertEquals(List.of(3.0), client.getMetricStatistics(request -> request.namespace(namespace)
                    .metricName("Counter").startTime(start).endTime(middle).period(3600).statistics(Statistic.SUM))
                    .datapoints().stream().map(point -> point.sum()).toList());
            assertEquals(List.of(7.0), client.getMetricStatistics(request -> request.namespace(namespace)
                    .metricName("Counter").startTime(middle).endTime(end).period(3600).statistics(Statistic.SUM))
                    .datapoints().stream().map(point -> point.sum()).toList());
            assertEquals(List.of(3.0), client.getMetricData(request -> request.startTime(start).endTime(middle)
                    .metricDataQueries(query -> query.id("m").returnData(true).metricStat(stat -> stat
                            .metric(metric -> metric.namespace(namespace).metricName("Counter"))
                            .period(3600).stat("Sum")))).metricDataResults().getFirst().values());
        }
    }
}
