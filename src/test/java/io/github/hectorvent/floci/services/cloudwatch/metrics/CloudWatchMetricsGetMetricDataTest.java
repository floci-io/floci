package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CloudWatchMetricsGetMetricDataTest {

    private static final String REGION = "us-east-1";
    private static final String NAMESPACE = "AWS/EC2";

    private CloudWatchMetricsService service;

    @BeforeEach
    void setUp() {
        service = new CloudWatchMetricsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000")
        );
    }

    private MetricDatum datumAt(String name, double value, long epochSecond) {
        MetricDatum d = new MetricDatum();
        d.setMetricName(name);
        d.setValue(value);
        d.setTimestamp(epochSecond);
        return d;
    }

    private MetricDatum datumWithDimAt(String name, double value, long epochSecond,
                                        String dimName, String dimValue) {
        MetricDatum d = datumAt(name, value, epochSecond);
        d.setDimensions(List.of(new Dimension(dimName, dimValue)));
        return d;
    }

    private CloudWatchMetricsService.MetricDataQuery metricStatQuery(
            String id, String namespace, String metricName,
            List<Dimension> dims, int period, String stat) {
        CloudWatchMetricsService.MetricStat ms = new CloudWatchMetricsService.MetricStat(
                namespace, metricName, dims, period, stat, null);
        return new CloudWatchMetricsService.MetricDataQuery(id, ms, null, null, true);
    }

    // ──────────────────────────── Basic correctness ────────────────────────────

    @Test
    void getMetricDataReturnsSeededDataPoint() {
        long ts = Instant.now().getEpochSecond();
        service.putMetricData(NAMESPACE, List.of(
                datumWithDimAt("CPUUtilization", 75.5, ts, "InstanceId", "i-1234")
        ), REGION);

        List<CloudWatchMetricsService.MetricDataQuery> queries = List.of(
                metricStatQuery("m0", NAMESPACE, "CPUUtilization",
                        List.of(new Dimension("InstanceId", "i-1234")), 60, "Average")
        );

        Instant start = Instant.ofEpochSecond(ts - 60);
        Instant end = Instant.ofEpochSecond(ts + 60);
        List<CloudWatchMetricsService.MetricDataResult> results =
                service.getMetricData(queries, start, end, REGION);

        assertEquals(1, results.size());
        CloudWatchMetricsService.MetricDataResult r = results.getFirst();
        assertEquals("m0", r.id());
        assertEquals("CPUUtilization", r.label());
        assertEquals("Complete", r.statusCode());
        assertEquals(1, r.values().size());
        assertEquals(75.5, r.values().getFirst(), 0.001);
    }

    // ──────────────────────────── Multiple queries ────────────────────────────

    @Test
    void getMetricDataHandlesMultipleQueries() {
        long ts = Instant.now().getEpochSecond();
        service.putMetricData(NAMESPACE, List.of(
                datumWithDimAt("CPUUtilization", 50.0, ts, "InstanceId", "i-1234"),
                datumWithDimAt("NetworkIn", 1024.0, ts, "InstanceId", "i-1234")
        ), REGION);

        List<CloudWatchMetricsService.MetricDataQuery> queries = List.of(
                metricStatQuery("cpu", NAMESPACE, "CPUUtilization",
                        List.of(new Dimension("InstanceId", "i-1234")), 60, "Average"),
                metricStatQuery("net", NAMESPACE, "NetworkIn",
                        List.of(new Dimension("InstanceId", "i-1234")), 60, "Sum")
        );

        Instant start = Instant.ofEpochSecond(ts - 60);
        Instant end = Instant.ofEpochSecond(ts + 60);
        List<CloudWatchMetricsService.MetricDataResult> results =
                service.getMetricData(queries, start, end, REGION);

        assertEquals(2, results.size());
        assertEquals("cpu", results.get(0).id());
        assertEquals("net", results.get(1).id());
        assertEquals(50.0, results.get(0).values().getFirst(), 0.001);
        assertEquals(1024.0, results.get(1).values().getFirst(), 0.001);
    }

    // ──────────────────────────── Time range filtering ────────────────────────────

    @Test
    void getMetricDataFiltersOutsideTimeRange() {
        long oldTs = Instant.now().minusSeconds(7200).getEpochSecond();
        long newTs = Instant.now().getEpochSecond();

        service.putMetricData(NAMESPACE, List.of(
                datumAt("CPUUtilization", 90.0, oldTs),
                datumAt("CPUUtilization", 30.0, newTs)
        ), REGION);

        List<CloudWatchMetricsService.MetricDataQuery> queries = List.of(
                metricStatQuery("m0", NAMESPACE, "CPUUtilization", List.of(), 60, "Average")
        );

        // Only include the recent point
        Instant start = Instant.ofEpochSecond(newTs - 60);
        Instant end = Instant.ofEpochSecond(newTs + 60);
        List<CloudWatchMetricsService.MetricDataResult> results =
                service.getMetricData(queries, start, end, REGION);

        assertEquals(1, results.size());
        assertEquals(1, results.getFirst().values().size());
        assertEquals(30.0, results.getFirst().values().getFirst(), 0.001);
    }

    // ──────────────────────────── Empty results ────────────────────────────

    @Test
    void getMetricDataReturnsEmptyWhenNoMatchingData() {
        List<CloudWatchMetricsService.MetricDataQuery> queries = List.of(
                metricStatQuery("m0", NAMESPACE, "CPUUtilization", List.of(), 60, "Average")
        );

        Instant start = Instant.now().minusSeconds(300);
        Instant end = Instant.now();
        List<CloudWatchMetricsService.MetricDataResult> results =
                service.getMetricData(queries, start, end, REGION);

        assertEquals(1, results.size());
        assertTrue(results.getFirst().timestamps().isEmpty());
        assertTrue(results.getFirst().values().isEmpty());
        assertEquals("Complete", results.getFirst().statusCode());
    }

    // ──────────────────────────── ReturnData=false ────────────────────────────

    @Test
    void getMetricDataSkipsQueriesWithReturnDataFalse() {
        long ts = Instant.now().getEpochSecond();
        service.putMetricData(NAMESPACE, List.of(
                datumAt("CPUUtilization", 55.0, ts)
        ), REGION);

        CloudWatchMetricsService.MetricStat ms = new CloudWatchMetricsService.MetricStat(
                NAMESPACE, "CPUUtilization", List.of(), 60, "Average", null);
        CloudWatchMetricsService.MetricDataQuery query =
                new CloudWatchMetricsService.MetricDataQuery("m0", ms, null, null, false);

        List<CloudWatchMetricsService.MetricDataResult> results =
                service.getMetricData(List.of(query),
                        Instant.ofEpochSecond(ts - 60), Instant.ofEpochSecond(ts + 60), REGION);

        assertTrue(results.isEmpty());
    }

    // ──────────────────────────── Stat variants ────────────────────────────

    @Test
    void getMetricDataResolvesSumStat() {
        long ts = Instant.now().getEpochSecond();
        // Put two datums in the same period bucket — their sum should aggregate
        service.putMetricData(NAMESPACE, List.of(
                datumAt("RequestCount", 10.0, ts),
                datumAt("RequestCount", 20.0, ts)
        ), REGION);

        List<CloudWatchMetricsService.MetricDataQuery> queries = List.of(
                metricStatQuery("m0", NAMESPACE, "RequestCount", List.of(), 60, "Sum")
        );

        Instant start = Instant.ofEpochSecond(ts - 60);
        Instant end = Instant.ofEpochSecond(ts + 60);
        List<CloudWatchMetricsService.MetricDataResult> results =
                service.getMetricData(queries, start, end, REGION);

        assertEquals(1, results.size());
        assertEquals(1, results.getFirst().values().size());
        assertEquals(30.0, results.getFirst().values().getFirst(), 0.001);
    }

    @Test
    void getMetricDataResolvesMaximumStat() {
        long ts = Instant.now().getEpochSecond();
        service.putMetricData(NAMESPACE, List.of(
                datumAt("Latency", 5.0, ts),
                datumAt("Latency", 99.0, ts)
        ), REGION);

        List<CloudWatchMetricsService.MetricDataQuery> queries = List.of(
                metricStatQuery("m0", NAMESPACE, "Latency", List.of(), 60, "Maximum")
        );

        Instant start = Instant.ofEpochSecond(ts - 60);
        Instant end = Instant.ofEpochSecond(ts + 60);
        List<CloudWatchMetricsService.MetricDataResult> results =
                service.getMetricData(queries, start, end, REGION);

        assertEquals(99.0, results.getFirst().values().getFirst(), 0.001);
    }

    // ──────────────────────────── Label ────────────────────────────

    @Test
    void getMetricDataUsesMetricNameAsDefaultLabel() {
        long ts = Instant.now().getEpochSecond();
        service.putMetricData(NAMESPACE, List.of(datumAt("CPUUtilization", 50.0, ts)), REGION);

        List<CloudWatchMetricsService.MetricDataQuery> queries = List.of(
                metricStatQuery("m0", NAMESPACE, "CPUUtilization", List.of(), 60, "Average")
        );

        List<CloudWatchMetricsService.MetricDataResult> results =
                service.getMetricData(queries,
                        Instant.ofEpochSecond(ts - 60), Instant.ofEpochSecond(ts + 60), REGION);

        assertEquals("CPUUtilization", results.getFirst().label());
    }

    @Test
    void getMetricDataUsesCustomLabel() {
        long ts = Instant.now().getEpochSecond();
        service.putMetricData(NAMESPACE, List.of(datumAt("CPUUtilization", 50.0, ts)), REGION);

        CloudWatchMetricsService.MetricStat ms = new CloudWatchMetricsService.MetricStat(
                NAMESPACE, "CPUUtilization", List.of(), 60, "Average", null);
        CloudWatchMetricsService.MetricDataQuery query =
                new CloudWatchMetricsService.MetricDataQuery("m0", ms, null, "My CPU", true);

        List<CloudWatchMetricsService.MetricDataResult> results =
                service.getMetricData(List.of(query),
                        Instant.ofEpochSecond(ts - 60), Instant.ofEpochSecond(ts + 60), REGION);

        assertEquals("My CPU", results.getFirst().label());
    }
}
