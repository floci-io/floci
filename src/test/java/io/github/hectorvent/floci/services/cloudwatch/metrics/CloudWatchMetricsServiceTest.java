package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CloudWatchMetricsServiceTest {

    private static final String REGION = "us-east-1";
    private static final String NAMESPACE = "MyApp/Metrics";

    private CloudWatchMetricsService service;

    @BeforeEach
    void setUp() {
        service = new CloudWatchMetricsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000")
        );
    }

    private MetricDatum datum(String name, double value) {
        MetricDatum d = new MetricDatum();
        d.setMetricName(name);
        d.setValue(value);
        return d;
    }

    private MetricDatum datumWithDimension(String name, double value, String dimName, String dimValue) {
        MetricDatum d = datum(name, value);
        d.setDimensions(List.of(new Dimension(dimName, dimValue)));
        return d;
    }

    // ──────────────────────────── PutMetricData / ListMetrics ────────────────────────────

    @Test
    void putMetricDataAndList() {
        service.putMetricData(NAMESPACE, List.of(datum("RequestCount", 42)), REGION);

        List<CloudWatchMetricsService.MetricIdentity> metrics = service.listMetrics(NAMESPACE, null, null, REGION);
        assertEquals(1, metrics.size());
        assertEquals("RequestCount", metrics.getFirst().metricName());
        assertEquals(NAMESPACE, metrics.getFirst().namespace());
    }

    @Test
    void listMetricsDeduplicates() {
        service.putMetricData(NAMESPACE, List.of(datum("RequestCount", 1)), REGION);
        service.putMetricData(NAMESPACE, List.of(datum("RequestCount", 2)), REGION);

        List<CloudWatchMetricsService.MetricIdentity> metrics = service.listMetrics(NAMESPACE, null, null, REGION);
        assertEquals(1, metrics.size());
    }

    @Test
    void listMetricsFilterByName() {
        service.putMetricData(NAMESPACE, List.of(datum("RequestCount", 1), datum("ErrorCount", 1)), REGION);

        List<CloudWatchMetricsService.MetricIdentity> result = service.listMetrics(NAMESPACE, "ErrorCount", null, REGION);
        assertEquals(1, result.size());
        assertEquals("ErrorCount", result.getFirst().metricName());
    }

    @Test
    void listMetricsFilterByDimension() {
        service.putMetricData(NAMESPACE, List.of(
                datumWithDimension("Latency", 100, "Service", "auth"),
                datumWithDimension("Latency", 200, "Service", "api")
        ), REGION);

        List<CloudWatchMetricsService.MetricIdentity> result = service.listMetrics(
                NAMESPACE, "Latency", List.of(new Dimension("Service", "auth")), REGION);
        assertEquals(1, result.size());
    }

    @Test
    void putMetricDataWithStatisticValues() {
        MetricDatum d = new MetricDatum();
        d.setMetricName("AggregatedMetric");
        d.setSampleCount(5.0);
        d.setSum(150.0);
        d.setMinimum(20.0);
        d.setMaximum(40.0);
        service.putMetricData(NAMESPACE, List.of(d), REGION);

        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now().plusSeconds(60);
        List<CloudWatchMetricsService.Datapoint> points = service.getMetricStatistics(
                NAMESPACE, "AggregatedMetric", null, start, end, 60,
                List.of("Sum", "SampleCount", "Average", "Minimum", "Maximum"), null, REGION);

        assertFalse(points.isEmpty());
        assertEquals(150.0, points.getFirst().sum());
        assertEquals(5.0, points.getFirst().sampleCount());
        assertEquals(20.0, points.getFirst().minimum());
        assertEquals(40.0, points.getFirst().maximum());
        assertEquals(30.0, points.getFirst().average());
    }

    @Test
    void putMetricDataAutoFillsStatistics() {
        service.putMetricData(NAMESPACE, List.of(datum("Requests", 5.0)), REGION);

        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now().plusSeconds(60);
        List<CloudWatchMetricsService.Datapoint> points = service.getMetricStatistics(
                NAMESPACE, "Requests", null, start, end, 60, List.of("Sum"), null, REGION);

        assertFalse(points.isEmpty());
        assertEquals(5.0, points.getFirst().sum());
        assertEquals(1.0, points.getFirst().sampleCount());
    }

    // ──────────────────────────── GetMetricStatistics ────────────────────────────

    @Test
    void getMetricStatisticsBucketsByPeriod() {
        long now = Instant.now().getEpochSecond();
        long bucket1 = (now / 60) * 60;
        long bucket2 = bucket1 + 60;

        MetricDatum d1 = datum("Requests", 10);
        d1.setTimestamp(bucket1);
        MetricDatum d2 = datum("Requests", 20);
        d2.setTimestamp(bucket2);
        service.putMetricData(NAMESPACE, List.of(d1, d2), REGION);

        List<CloudWatchMetricsService.Datapoint> points = service.getMetricStatistics(
                NAMESPACE, "Requests", null,
                Instant.ofEpochSecond(bucket1 - 1),
                Instant.ofEpochSecond(bucket2 + 1),
                60, List.of("Sum"), null, REGION);

        assertEquals(2, points.size());
    }

    @Test
    void getMetricStatisticsReturnsEmptyOutsideRange() {
        MetricDatum d = datum("Requests", 10);
        d.setTimestamp(Instant.now().minusSeconds(7200).getEpochSecond());
        service.putMetricData(NAMESPACE, List.of(d), REGION);

        List<CloudWatchMetricsService.Datapoint> result = service.getMetricStatistics(
                NAMESPACE, "Requests", null,
                Instant.now().minusSeconds(60),
                Instant.now(),
                60, List.of("Sum"), null, REGION);

        assertTrue(result.isEmpty());
    }

    // ──────────────────────────── Alarms ────────────────────────────

    @Test
    void putAndDescribeAlarm() {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("high-error-rate");
        alarm.setMetricName("ErrorCount");
        alarm.setNamespace(NAMESPACE);
        service.putMetricAlarm(alarm, REGION);

        List<MetricAlarm> alarms = service.describeAlarms(null, null, REGION);
        assertEquals(1, alarms.size());
        assertEquals("high-error-rate", alarms.getFirst().getAlarmName());
        assertNotNull(alarms.getFirst().getAlarmArn());
    }

    @Test
    void describeAlarmsByName() {
        MetricAlarm a1 = new MetricAlarm();
        a1.setAlarmName("alarm-1");
        MetricAlarm a2 = new MetricAlarm();
        a2.setAlarmName("alarm-2");
        service.putMetricAlarm(a1, REGION);
        service.putMetricAlarm(a2, REGION);

        List<MetricAlarm> result = service.describeAlarms(List.of("alarm-1"), null, REGION);
        assertEquals(1, result.size());
        assertEquals("alarm-1", result.getFirst().getAlarmName());
    }

    @Test
    void describeAlarmsByPrefix() {
        MetricAlarm a1 = new MetricAlarm();
        a1.setAlarmName("prod-errors");
        MetricAlarm a2 = new MetricAlarm();
        a2.setAlarmName("prod-latency");
        MetricAlarm a3 = new MetricAlarm();
        a3.setAlarmName("dev-errors");
        service.putMetricAlarm(a1, REGION);
        service.putMetricAlarm(a2, REGION);
        service.putMetricAlarm(a3, REGION);

        List<MetricAlarm> result = service.describeAlarms(null, "prod-", REGION);
        assertEquals(2, result.size());
    }

    @Test
    void deleteAlarms() {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("to-delete");
        service.putMetricAlarm(alarm, REGION);
        service.deleteAlarms(List.of("to-delete"), REGION);

        assertTrue(service.describeAlarms(null, null, REGION).isEmpty());
    }

    @Test
    void setAlarmState() {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("my-alarm");
        service.putMetricAlarm(alarm, REGION);

        service.setAlarmState("my-alarm", "ALARM", "Threshold breached", null, REGION);

        MetricAlarm updated = service.describeAlarms(List.of("my-alarm"), null, REGION).getFirst();
        assertEquals("ALARM", updated.getStateValue());
        assertEquals("Threshold breached", updated.getStateReason());
    }

    @Test
    void buildDimKeyIsDeterministic() {
        List<Dimension> dims1 = List.of(new Dimension("B", "2"), new Dimension("A", "1"));
        List<Dimension> dims2 = List.of(new Dimension("A", "1"), new Dimension("B", "2"));

        assertEquals(CloudWatchMetricsService.buildDimKey(dims1),
                CloudWatchMetricsService.buildDimKey(dims2));
    }

    @Test
    void buildDimKeyEmptyDimensions() {
        assertEquals("", CloudWatchMetricsService.buildDimKey(List.of()));
        assertEquals("", CloudWatchMetricsService.buildDimKey(null));
    }
}