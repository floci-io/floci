package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.cloudwatch.metrics.model.MetricDatum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudWatchMetricEndTimeTest {
    private final CloudWatchMetricsService service = new CloudWatchMetricsService(
            new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver("us-east-1", "123456789012"));

    private void seed() {
        service.putMetricData("Boundary", List.of(
                MetricDatum.of("Counter", "Count", 3, List.of(), 120),
                MetricDatum.of("Counter", "Count", 7, List.of(), 180),
                MetricDatum.of("Counter", "Count", 11, List.of(), 240)), "us-east-1");
    }

    private List<CloudWatchMetricsService.Datapoint> read(long start, Instant end) {
        return service.getMetricStatistics("Boundary", "Counter", List.of(), Instant.ofEpochSecond(start),
                end, 60, List.of("Sum"), "Count", "us-east-1");
    }

    @Test
    void adjacentWindowsIncludeTheirStartAndExcludeTheirEnd() {
        seed();
        List<CloudWatchMetricsService.Datapoint> first = read(120, Instant.ofEpochSecond(180));
        List<CloudWatchMetricsService.Datapoint> second = read(180, Instant.ofEpochSecond(240));
        assertEquals(List.of(3.0), first.stream().map(CloudWatchMetricsService.Datapoint::sum).toList());
        assertEquals(List.of(7.0), second.stream().map(CloudWatchMetricsService.Datapoint::sum).toList());
        assertEquals(Instant.ofEpochSecond(120), first.getFirst().timestamp());
        assertEquals(Instant.ofEpochSecond(180), second.getFirst().timestamp());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 500_000_000})
    void fractionalExclusiveEndStillIncludesAnEarlierWholeSecond(int nanos) {
        seed();
        Instant end = Instant.ofEpochSecond(180, nanos);
        assertEquals(List.of(7.0), read(180, end).stream()
                .map(CloudWatchMetricsService.Datapoint::sum).toList());
        CloudWatchMetricsService.MetricStat metric = new CloudWatchMetricsService.MetricStat(
                "Boundary", "Counter", List.of(), 60, "Sum", "Count");
        List<CloudWatchMetricsService.MetricDataResult> results = service.getMetricData(
                List.of(new CloudWatchMetricsService.MetricDataQuery("m", metric, null, null, true)),
                Instant.ofEpochSecond(180), end, "us-east-1");
        assertEquals(List.of(7.0), results.getFirst().values());
    }
}
