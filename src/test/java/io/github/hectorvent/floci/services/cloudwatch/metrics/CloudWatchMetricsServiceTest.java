package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @Test
    void metricDefinitionUpdatePreservesStateAndTags() {
        MetricAlarm original = new MetricAlarm();
        original.setAlarmName("updated");
        original.setTags(new HashMap<>(Map.of("team", "original")));
        service.putMetricAlarm(original, REGION);
        service.setAlarmState("updated", "ALARM", "manual", "{}", REGION);
        service.tagResource(original.getAlarmArn(), Map.of("owner", "retained"), REGION);
        service.untagResource(original.getAlarmArn(), List.of("team"), REGION);
        MetricAlarm replacement = new MetricAlarm();
        replacement.setAlarmName("updated");
        replacement.setThreshold(42.0);
        replacement.setTags(new HashMap<>(Map.of("ignored", "update")));
        service.putMetricAlarm(replacement, REGION);
        MetricAlarm result = service.describeAlarms(List.of("updated"), null, REGION).getFirst();
        assertEquals("ALARM", result.getStateValue());
        assertEquals("manual", result.getStateReason());
        assertEquals("{}", result.getStateReasonData());
        assertEquals(original.getStateUpdatedTimestamp(), result.getStateUpdatedTimestamp());
        assertEquals(42.0, result.getThreshold());
        assertEquals(Map.of("owner", "retained"), result.getTags());
        assertNotSame(original.getTags(), result.getTags());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "alarm", "UNKNOWN"})
    void invalidManualStatePreservesAlarm(String value) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("manual");
        service.putMetricAlarm(alarm, REGION);
        service.setAlarmState("manual", "OK", "preserved", "{}", REGION);
        long timestamp = alarm.getStateUpdatedTimestamp();
        AwsException error = assertThrows(AwsException.class,
                () -> service.setAlarmState("manual", value, "rejected", "changed", REGION));
        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(value == null
                ? "1 validation error detected: Value null at 'stateValue' failed to satisfy constraint: Member must not be null"
                : "1 validation error detected: Value '" + value + "' at 'stateValue' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: [OK, ALARM, INSUFFICIENT_DATA]", error.getMessage());
        assertEquals("OK", alarm.getStateValue());
        assertEquals("preserved", alarm.getStateReason());
        assertEquals("{}", alarm.getStateReasonData());
        assertEquals(timestamp, alarm.getStateUpdatedTimestamp());
    }

    @Test
    void missingManualStateReasonPreservesAlarm() {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("reason-required");
        service.putMetricAlarm(alarm, REGION);
        service.setAlarmState(alarm.getAlarmName(), "OK", "preserved", "{}", REGION);
        long timestamp = alarm.getStateUpdatedTimestamp();
        AwsException error = assertThrows(AwsException.class,
                () -> service.setAlarmState(alarm.getAlarmName(), "ALARM", null, "changed", REGION));
        assertEquals("ValidationError", error.getErrorCode());
        assertEquals("1 validation error detected: Value null at 'stateReason' failed to satisfy constraint: "
                + "Member must not be null", error.getMessage());
        MetricAlarm stored = service.describeAlarms(List.of(alarm.getAlarmName()), null, REGION).getFirst();
        assertEquals("OK", stored.getStateValue());
        assertEquals("preserved", stored.getStateReason());
        assertEquals("{}", stored.getStateReasonData());
        assertEquals(timestamp, stored.getStateUpdatedTimestamp());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "manual reason"})
    void suppliedManualStateReasonIsPreserved(String reason) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("reason-supplied");
        service.putMetricAlarm(alarm, REGION);
        service.setAlarmState(alarm.getAlarmName(), "ALARM", reason, null, REGION);
        assertEquals("ALARM", alarm.getStateValue());
        assertEquals(reason, alarm.getStateReason());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void manualReasonLengthLimitsPreserveRejectedState(boolean data) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("reason-length");
        service.putMetricAlarm(alarm, REGION);
        int maximum = data ? 4000 : 1023;
        String path = data ? "stateReasonData" : "stateReason";
        for (String unit : List.of("x", " ", "\uD83D\uDE00")) {
            String boundary = unit.repeat(maximum);
            service.setAlarmState("reason-length", "OK", data ? "accepted" : boundary,
                    data ? boundary : "{}", REGION);
            long timestamp = alarm.getStateUpdatedTimestamp();
            String rejected = boundary + unit;
            AwsException error = assertThrows(AwsException.class,
                    () -> service.setAlarmState("reason-length", "ALARM", data ? "rejected" : rejected,
                            data ? rejected : "changed", REGION));
            assertEquals("ValidationError", error.getErrorCode());
            assertEquals("1 validation error detected: Value '" + rejected + "' at '" + path
                    + "' failed to satisfy constraint: Member must have length less than or equal to " + maximum,
                    error.getMessage());
            assertEquals("OK", alarm.getStateValue());
            assertEquals(data ? "accepted" : boundary, alarm.getStateReason());
            assertEquals(data ? boundary : "{}", alarm.getStateReasonData());
            assertEquals(timestamp, alarm.getStateUpdatedTimestamp());
        }
        service.setAlarmState("reason-length", "OK", "", "", REGION);
        assertEquals("", alarm.getStateReason());
        assertEquals("", alarm.getStateReasonData());
        service.setAlarmState("reason-length", "OK", " ", null, REGION);
        assertEquals(" ", alarm.getStateReason());
        assertNull(alarm.getStateReasonData());
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
    void getMetricStatisticsFiltersByUnit() {
        MetricDatum count = datum("Requests", 10);
        count.setUnit("Count");
        MetricDatum bytes = datum("Requests", 20);
        bytes.setUnit("Bytes");
        service.putMetricData(NAMESPACE, List.of(count, bytes), REGION);

        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now().plusSeconds(60);

        List<CloudWatchMetricsService.Datapoint> matching = service.getMetricStatistics(
                NAMESPACE, "Requests", null, start, end, 60, List.of("Sum"), "Count", REGION);
        assertEquals(1, matching.size());
        assertEquals(10.0, matching.getFirst().sum());
        assertEquals("Count", matching.getFirst().unit());

        List<CloudWatchMetricsService.Datapoint> otherUnit = service.getMetricStatistics(
                NAMESPACE, "Requests", null, start, end, 60, List.of("Sum"), "Bytes", REGION);
        assertEquals(1, otherUnit.size());
        assertEquals(20.0, otherUnit.getFirst().sum());
        assertEquals("Bytes", otherUnit.getFirst().unit());

        List<CloudWatchMetricsService.Datapoint> noMatch = service.getMetricStatistics(
                NAMESPACE, "Requests", null, start, end, 60, List.of("Sum"), "Percent", REGION);
        assertTrue(noMatch.isEmpty());
    }

    @Test
    void getMetricStatisticsTreatsNullOrNoneOrBlankUnitAsNoFilter() {
        MetricDatum count = datum("Requests", 10);
        count.setUnit("Count");
        MetricDatum bytes = datum("Requests", 20);
        bytes.setUnit("Bytes");
        service.putMetricData(NAMESPACE, List.of(count, bytes), REGION);

        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now().plusSeconds(60);

        List<CloudWatchMetricsService.Datapoint> allNull = service.getMetricStatistics(
                NAMESPACE, "Requests", null, start, end, 60, List.of("Sum"), null, REGION);
        assertEquals(1, allNull.size());
        assertEquals(30.0, allNull.getFirst().sum());

        List<CloudWatchMetricsService.Datapoint> allNone = service.getMetricStatistics(
                NAMESPACE, "Requests", null, start, end, 60, List.of("Sum"), "None", REGION);
        assertEquals(1, allNone.size());
        assertEquals(30.0, allNone.getFirst().sum());

        List<CloudWatchMetricsService.Datapoint> allBlank = service.getMetricStatistics(
                NAMESPACE, "Requests", null, start, end, 60, List.of("Sum"), "  ", REGION);
        assertEquals(1, allBlank.size());
    }

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
    void setAlarmStateOnNonexistentAlarmThrowsResourceNotFound() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.setAlarmState("missing-alarm", "ALARM", "reason", null, REGION));

        assertEquals(404, ex.getHttpStatus());
        assertEquals("ResourceNotFound", ex.getErrorCode());
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

    /** A writer outside any request can store datums in the account it acts for, not the default one. */
    @Test
    void putMetricDataForAccountStoresInThatAccountsPartition() {
        AccountAwareStorageBackend<MetricDatum> store = AccountAwareStorageBackend.inMemory("000000000000");
        CloudWatchMetricsService accountAware = new CloudWatchMetricsService(store, new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"));

        accountAware.putMetricDataForAccount("111111111111", NAMESPACE, List.of(datum("Requests", 1)), REGION);
        accountAware.putMetricData(NAMESPACE, List.of(datum("Requests", 2)), REGION);

        assertEquals(1, store.scanForAccount("111111111111", k -> true).size());
        assertEquals(1.0, store.scanForAccount("111111111111", k -> true).getFirst().getValue());
        assertEquals(1, store.scan(k -> true).size(), "the default partition holds only the ambient put");
        assertEquals(2.0, store.scan(k -> true).getFirst().getValue());
    }
}
