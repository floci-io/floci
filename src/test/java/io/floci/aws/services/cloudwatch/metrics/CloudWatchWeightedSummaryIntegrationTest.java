package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.cloudwatch.model.StatisticSet;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CloudWatchWeightedSummaryIntegrationTest {
    @Test
    void sdkWeightedSummaryIsCountedOnce() {
        String namespace = "WeightedSummary/" + UUID.randomUUID();
        Instant timestamp = Instant.now().minusSeconds(60);
        try (CloudWatchClient client = AwsTestClients.create(CloudWatchClient.builder(), "test", Region.US_EAST_1)) {
            client.putMetricData(request -> request.namespace(namespace).metricData(MetricDatum.builder()
                    .metricName("Latency").timestamp(timestamp).values(7.0, 11.0).counts(3.0, 2.0)
                    .statisticValues(StatisticSet.builder().sampleCount(5.0).sum(43.0)
                            .minimum(7.0).maximum(11.0).build()).build()));
            Datapoint point = client.getMetricStatistics(request -> request.namespace(namespace)
                    .metricName("Latency").startTime(timestamp.minusSeconds(1)).endTime(timestamp.plusSeconds(1))
                    .period(60).statistics(Statistic.SAMPLE_COUNT, Statistic.SUM, Statistic.MINIMUM,
                            Statistic.MAXIMUM, Statistic.AVERAGE)).datapoints().getFirst();
            assertEquals(5.0, point.sampleCount());
            assertEquals(43.0, point.sum());
            assertEquals(7.0, point.minimum());
            assertEquals(11.0, point.maximum());
            assertEquals(8.6, point.average());
        }
    }
}
