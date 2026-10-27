package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Alarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.AlarmHistoryItem;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.CompositeAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudWatchMetricsServiceTest {

    private static final String REGION = "us-east-1";
    private static final String NAMESPACE = "MyApp/Metrics";
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    // AWS: StateTransitionedTimestamp is when StateValue last changed; StateUpdatedTimestamp moves
    // on any state update, even one that keeps the value.
    @Test
    void stateTransitionedTimestampMovesOnlyWhenTheStateValueChanges() throws Exception {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("transitions");
        service.putMetricAlarm(alarm, REGION);
        assertEquals(alarm.getStateUpdatedTimestamp(), alarm.getStateTransitionedTimestamp());

        alarm.setStateUpdatedTimestamp(100);
        alarm.setStateTransitionedTimestamp(100);
        service.setAlarmState("transitions", "INSUFFICIENT_DATA", "same state", null, REGION);
        assertTrue(alarm.getStateUpdatedTimestamp() > 100);
        assertEquals(100, alarm.getStateTransitionedTimestamp());

        service.setAlarmState("transitions", "ALARM", "breached", null, REGION);
        long transitioned = alarm.getStateTransitionedTimestamp();
        assertTrue(transitioned > 100);

        alarm.setStateTransitionedTimestamp(200);
        MetricAlarm replacement = new MetricAlarm();
        replacement.setAlarmName("transitions");
        service.putMetricAlarm(replacement, REGION);
        assertEquals(200, service.describeAlarms(List.of("transitions"), null, REGION).getFirst()
                .getStateTransitionedTimestamp());
    }

    @Test
    void aCompositeTransitionMovesItsStateTransitionedTimestamp() {
        MetricAlarm child = new MetricAlarm();
        child.setAlarmName("child");
        service.putMetricAlarm(child, REGION);
        CompositeAlarm parent = new CompositeAlarm();
        parent.setAlarmName("parent");
        parent.setAlarmRule("ALARM(child)");
        service.putCompositeAlarm(parent, REGION);
        CompositeAlarm stored = service.describeCompositeAlarms(List.of("parent"), null, REGION).getFirst();
        assertEquals("OK", stored.getStateValue());
        stored.setStateTransitionedTimestamp(100);

        service.setAlarmState("child", "ALARM", "breached", null, REGION);
        assertEquals("ALARM", stored.getStateValue());
        assertTrue(stored.getStateTransitionedTimestamp() > 100);
    }

    // A stored alarm without a transition time keeps reporting its last state update as the
    // transition when a same-state SetAlarmState moves StateUpdatedTimestamp.
    @Test
    void aSameStateUpdateOfAnAlarmStoredWithoutATransitionKeepsItsLastTransition() {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("legacy");
        service.putMetricAlarm(alarm, REGION);
        service.setAlarmState("legacy", "ALARM", "breached", null, REGION);
        alarm.setStateTransitionedTimestamp(0);
        alarm.setStateUpdatedTimestamp(1000);

        service.setAlarmState("legacy", "ALARM", "still breached", null, REGION);

        assertTrue(alarm.getStateUpdatedTimestamp() > 1000);
        assertEquals(1000, alarm.lastTransitionTimestamp());
    }

    // Alarms stored before StateTransitionedTimestamp existed report their last state update.
    @Test
    void anAlarmStoredWithoutATransitionTimestampReportsItsLastStateUpdate() throws Exception {
        Alarm legacy = new ObjectMapper().readValue(
                "{\"alarmType\":\"MetricAlarm\",\"alarmName\":\"old\",\"stateUpdatedTimestamp\":123}", Alarm.class);
        assertEquals(0, legacy.getStateTransitionedTimestamp());
        assertEquals(123, legacy.lastTransitionTimestamp());
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
                        + "Member must satisfy enum value set: [INSUFFICIENT_DATA, ALARM, OK]", error.getMessage());
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

    // ──────────────────────────── Alarm history ────────────────────────────

    @Test
    void stateTransitionsAreRecordedNewestFirst() {
        putAlarm("history");
        service.setAlarmState("history", "OK", "initial", null, REGION);
        service.setAlarmState("history", "ALARM", "breached", "{\"value\":3}", REGION);
        service.setAlarmState("history", "OK", "recovered", null, REGION);

        List<AlarmHistoryItem> items = history("history", null, "StateUpdate", null, null, null, null);
        assertEquals(List.of("Alarm updated from ALARM to OK", "Alarm updated from OK to ALARM",
                "Alarm updated from INSUFFICIENT_DATA to OK"), summaries(items));
        assertEquals("MetricAlarm", items.getFirst().alarmType());
        assertEquals("history", items.getFirst().alarmName());
        assertEquals("{\"version\":\"1.0\",\"oldState\":{\"stateValue\":\"OK\",\"stateReason\":\"initial\"},"
                + "\"newState\":{\"stateValue\":\"ALARM\",\"stateReason\":\"breached\",\"stateReasonData\":{\"value\":3}}}",
                items.get(1).historyData());
        assertEquals("{\"version\":\"1.0\",\"oldState\":{\"stateValue\":\"ALARM\",\"stateReason\":\"breached\","
                + "\"stateReasonData\":{\"value\":3}},\"newState\":{\"stateValue\":\"OK\",\"stateReason\":\"recovered\"}}",
                items.getFirst().historyData());
        assertTrue(items.get(0).timestamp() >= items.get(1).timestamp());
    }

    @Test
    void settingTheCurrentStateRecordsNothingButUpdatesTheReason() {
        putAlarm("steady");
        service.setAlarmState("steady", "OK", "first", "not json", REGION);
        service.setAlarmState("steady", "OK", "again", null, REGION);

        List<AlarmHistoryItem> items = history("steady", null, "StateUpdate", null, null, null, null);
        assertEquals(1, items.size());
        assertEquals("{\"version\":\"1.0\",\"oldState\":{\"stateValue\":\"INSUFFICIENT_DATA\",\"stateReason\":\"Unchecked\"},"
                + "\"newState\":{\"stateValue\":\"OK\",\"stateReason\":\"first\",\"stateReasonData\":\"not json\"}}",
                items.getFirst().historyData());
        assertEquals("again", service.describeAlarms(List.of("steady"), null, REGION).getFirst().getStateReason());
    }

    @Test
    void configurationChangesAreRecordedAndSurviveDeletion() throws Exception {
        MetricAlarm original = new MetricAlarm();
        original.setAlarmName("config");
        original.setAlarmDescription("original");
        original.setThreshold(1.0);
        original.setTags(new HashMap<>(Map.of("team", "metrics")));
        service.putMetricAlarm(original, REGION);
        String created = AlarmSnsNotifier.TIMESTAMP_FORMAT.format(
                Instant.ofEpochSecond(original.getAlarmConfigurationUpdatedTimestamp()));
        MetricAlarm updated = new MetricAlarm();
        updated.setAlarmName("config");
        updated.setAlarmDescription("updated");
        updated.setThreshold(2.0);
        updated.setUnit("Count");
        service.putMetricAlarm(updated, REGION);
        service.deleteAlarms(List.of("config", "never-existed"), REGION);

        assertTrue(service.describeAlarms(List.of("config"), null, REGION).isEmpty());
        List<AlarmHistoryItem> items = history("config", null, "ConfigurationUpdate", null, null, null, null);
        assertEquals(List.of("Alarm \"config\" deleted", "Alarm \"config\" updated", "Alarm \"config\" created"),
                summaries(items));
        assertTrue(history("never-existed", null, null, null, null, null, null).isEmpty());

        JsonNode create = MAPPER.readTree(items.get(2).historyData());
        assertEquals("1.0", create.path("version").asText());
        assertEquals("Create", create.path("type").asText());
        JsonNode createdAlarm = create.path("createdAlarm");
        assertEquals("original", createdAlarm.path("alarmDescription").asText());
        assertEquals(original.getAlarmArn(), createdAlarm.path("alarmArn").asText());
        assertEquals("INSUFFICIENT_DATA", createdAlarm.path("stateValue").asText());
        assertEquals(created, createdAlarm.path("alarmConfigurationUpdatedTimestamp").asText());
        assertTrue(created.endsWith("+0000"));
        for (String hidden : List.of("region", "tags", "stateReason", "stateReasonData", "alarmType", "unit",
                "stateTransitionedTimestamp")) {
            assertFalse(createdAlarm.has(hidden), hidden);
        }

        JsonNode update = MAPPER.readTree(items.get(1).historyData());
        assertEquals("Update", update.path("type").asText());
        assertEquals(2.0, update.path("updatedAlarm").path("threshold").asDouble());
        JsonNode originalFields = update.path("originalUpdatedFields");
        assertEquals("original", originalFields.path("alarmDescription").asText());
        assertEquals(1.0, originalFields.path("threshold").asDouble());
        assertTrue(originalFields.path("unit").isNull());
        assertFalse(originalFields.has("alarmName"));
        assertFalse(originalFields.has("stateValue"));

        JsonNode delete = MAPPER.readTree(items.getFirst().historyData());
        assertEquals("Delete", delete.path("type").asText());
        assertEquals("updated", delete.path("deletedAlarm").path("alarmDescription").asText());
    }

    @Test
    void historyFiltersByItemTypeAndScansInEitherDirection() {
        putAlarm("filtered");
        service.setAlarmState("filtered", "OK", "initial", null, REGION);
        service.setAlarmState("filtered", "ALARM", "breached", null, REGION);

        List<String> newestFirst = List.of("Alarm updated from OK to ALARM",
                "Alarm updated from INSUFFICIENT_DATA to OK", "Alarm \"filtered\" created");
        assertEquals(newestFirst, summaries(history("filtered", null, null, null, null, null, null)));
        assertEquals(newestFirst, summaries(history("filtered", List.of("MetricAlarm"), null, null, null, null,
                "TimestampDescending")));
        assertEquals(newestFirst.reversed(), summaries(history("filtered", null, null, null, null, null,
                "TimestampAscending")));
        assertEquals(List.of("Alarm \"filtered\" created"),
                summaries(history("filtered", null, "ConfigurationUpdate", null, null, null, null)));
        for (String neverRecorded : List.of("Action", "AlarmContributorStateUpdate", "AlarmContributorAction")) {
            assertTrue(history("filtered", null, neverRecorded, null, null, null, null).isEmpty());
        }
        assertTrue(history("filtered", List.of("CompositeAlarm"), null, null, null, null, null).isEmpty());
        assertTrue(service.describeAlarmHistory("filtered", null, null, null, null, null, null, null, "eu-west-1")
                .items().isEmpty());
    }

    // A state change's history entry and the alarm's StateTransitionedTimestamp come from the same instant.
    @Test
    void aStateUpdateHistoryEntryIsStampedWithTheAlarmsTransitionTime() {
        putAlarm("stamped");
        service.setAlarmState("stamped", "ALARM", "breached", null, REGION);

        MetricAlarm alarm = service.describeAlarms(List.of("stamped"), null, REGION).getFirst();
        AlarmHistoryItem entry = history("stamped", null, "StateUpdate", null, null, null, null).getFirst();
        assertEquals(alarm.getStateTransitionedTimestamp(), entry.timestamp() / 1000);
        assertEquals(alarm.getStateUpdatedTimestamp(), entry.timestamp() / 1000);
    }

    @Test
    void historyPagesFollowNextTokenInBothDirections() {
        putAlarm("paged");
        for (int i = 0; i < 55; i++) {
            service.setAlarmState("paged", i % 2 == 0 ? "ALARM" : "OK", "toggle " + i, null, REGION);
        }
        PaginatedResult<AlarmHistoryItem> first = service.describeAlarmHistory("paged", null, null, null, null,
                null, null, null, REGION);
        assertEquals(50, first.items().size());
        assertNotNull(first.nextToken());

        for (String scanBy : List.of("TimestampDescending", "TimestampAscending")) {
            List<AlarmHistoryItem> collected = new ArrayList<>();
            String token = null;
            do {
                PaginatedResult<AlarmHistoryItem> page = service.describeAlarmHistory("paged", null, null, null, null,
                        7, token, scanBy, REGION);
                assertTrue(page.items().size() <= 7);
                collected.addAll(page.items());
                token = page.nextToken();
            } while (token != null);
            List<AlarmHistoryItem> all = history("paged", null, null, null, null, 100, scanBy);
            assertEquals(56, all.size());
            assertEquals(all, collected);
        }
        assertEquals("Alarm \"paged\" created", history("paged", null, null, null, null, 1, "TimestampAscending")
                .getFirst().historySummary());
    }

    @Test
    void foreignOrInvalidTokensAreHandledLikeAws() {
        putAlarm("tokens");
        service.setAlarmState("tokens", "OK", "initial", null, REGION);
        String descending = service.describeAlarmHistory("tokens", null, null, null, null, 1, null,
                null, REGION).nextToken();
        String ascending = service.describeAlarmHistory("tokens", null, null, null, null, 1, null,
                "TimestampAscending", REGION).nextToken();

        PaginatedResult<AlarmHistoryItem> crossed = service.describeAlarmHistory("tokens", null, null, null, null,
                1, descending, "TimestampAscending", REGION);
        assertTrue(crossed.items().isEmpty());
        assertNull(crossed.nextToken());
        assertTrue(service.describeAlarmHistory("tokens", null, null, null, null, 1, ascending, null, REGION)
                .items().isEmpty());

        AwsException error = assertThrows(AwsException.class, () -> service.describeAlarmHistory("tokens", null,
                null, null, null, null, "bogus", null, REGION));
        assertEquals("InvalidNextToken", error.getErrorCode());
        assertEquals("InvalidNextToken", error.jsonType());
        assertEquals("Invalid NextToken : bogus", error.getMessage());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void historyRequestsAreValidatedWithSmithyMessages() {
        String prefix = "1 validation error detected: ";
        assertHistoryRejected(prefix + "Value '0' at 'maxRecords' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 1", null, null, null, 0, null);
        assertHistoryRejected(prefix + "Value '101' at 'maxRecords' failed to satisfy constraint: "
                + "Member must have value less than or equal to 100", null, null, null, 101, null);
        assertHistoryRejected(prefix + "Value 'Bogus' at 'historyItemType' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [AlarmContributorAction, AlarmContributorStateUpdate, Action, "
                + "StateUpdate, ConfigurationUpdate]", null, null, "Bogus", null, null);
        assertHistoryRejected(prefix + "Value 'Bogus' at 'scanBy' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [TimestampAscending, TimestampDescending]",
                null, null, null, null, "Bogus");
        assertHistoryRejected(prefix + "Value '[Bogus]' at 'alarmTypes' failed to satisfy constraint: "
                + "Member must satisfy constraint: [Member must satisfy enum value set: [MetricAlarm, CompositeAlarm]]",
                null, List.of("Bogus"), null, null, null);
        assertHistoryRejected(prefix + "Value '[LogAlarm]' at 'alarmTypes' failed to satisfy constraint: "
                + "Member must satisfy constraint: [Member must satisfy enum value set: [MetricAlarm, CompositeAlarm]]",
                null, List.of("LogAlarm"), null, null, null);
        assertHistoryRejected(prefix + "Value '' at 'alarmName' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 1", "", null, null, null, null);
        assertHistoryRejected("2 validation errors detected: Value '' at 'alarmName' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 1; Value 'Bogus' at 'scanBy' failed to satisfy "
                + "constraint: Member must satisfy enum value set: [TimestampAscending, TimestampDescending]",
                "", null, null, null, "Bogus");
        assertHistoryRejected("Alarm name must be provided to query history for alarm contributors.",
                null, null, "AlarmContributorStateUpdate", null, null);
        assertHistoryRejected("Alarm name must be provided to query history for alarm contributors.",
                null, null, "AlarmContributorAction", null, null);
        assertTrue(history("unknown", null, "AlarmContributorStateUpdate", null, null, null, null).isEmpty());
    }

    private void assertHistoryRejected(String message, String name, List<String> types, String itemType,
                                       Integer max, String scanBy) {
        AwsException error = assertThrows(AwsException.class,
                () -> service.describeAlarmHistory(name, types, itemType, null, null, max, null, scanBy, REGION));
        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(message, error.getMessage());
    }

    @Test
    void startDateAfterEndDateIsAnInvalidParameterValue() {
        AwsException error = assertThrows(AwsException.class, () -> history(null, null, null,
                Instant.parse("2026-10-26T16:23:10Z"), Instant.parse("2026-10-26T16:22:10Z"), null, null));
        assertEquals("InvalidParameterValue", error.getErrorCode());
        assertEquals("InvalidParameterValueException", error.jsonType());
        assertEquals(400, error.getHttpStatus());
        assertEquals("Start date Mon Oct 26 16:23:10 UTC 2026 must be before end date Mon Oct 26 16:22:10 UTC 2026",
                error.getMessage());
        assertTrue(history(null, null, null, Instant.parse("2026-10-26T16:26:55.549900Z"),
                Instant.parse("2026-10-26T16:26:55.549100Z"), null, null).isEmpty(), "AWS compares whole milliseconds");
    }

    @Test
    void datesFilterRecordedTimestampsAndExpiredHistoryIsDropped() {
        InMemoryStorage<String, AlarmHistoryItem> store = new InMemoryStorage<>();
        Instant now = Instant.now();
        String expiredKey = null;
        for (int daysAgo : List.of(40, 3, 2, 1)) {
            Instant at = now.minus(Duration.ofDays(daysAgo));
            long sequence = at.getEpochSecond() * 1_000_000 + at.getNano() / 1_000;
            String key = REGION + "::" + String.format("%020d", sequence);
            store.put(key, new AlarmHistoryItem(sequence, "dated",
                    "MetricAlarm", at.toEpochMilli(), "StateUpdate", daysAgo + " days ago", "{}"));
            if (daysAgo == 40) {
                expiredKey = key;
            }
        }
        service = new CloudWatchMetricsService(new InMemoryStorage<>(), new InMemoryStorage<>(), store,
                new RegionResolver(REGION, "000000000000"), null);

        assertEquals(List.of("1 days ago", "2 days ago", "3 days ago"),
                summaries(history("dated", null, null, null, null, null, null)));
        assertEquals(4, store.keys().size(), "a read hides expired history without deleting it");
        assertTrue(store.keys().contains(expiredKey));
        assertEquals(List.of("2 days ago"), summaries(history("dated", null, null,
                now.minus(Duration.ofDays(2)).minusSeconds(60), now.minus(Duration.ofDays(1)).minusSeconds(60),
                null, null)));
        assertEquals(List.of("1 days ago"), summaries(history("dated", null, null,
                now.minus(Duration.ofDays(1)).minusSeconds(60), null, null, null)));
        assertEquals(List.of("2 days ago", "3 days ago"), summaries(history("dated", null, null,
                null, now.minus(Duration.ofDays(2)).plusSeconds(60), null, null)));

        putAlarm("pruner");
        assertFalse(store.keys().contains(expiredKey), "a write prunes expired history");
        assertEquals(4, store.keys().size());

        store.put(expiredKey, new AlarmHistoryItem(0, "dated", "MetricAlarm",
                now.minus(Duration.ofDays(40)).toEpochMilli(), "StateUpdate", "40 days ago", "{}"));
        putAlarm("within-the-hour");
        assertTrue(store.keys().contains(expiredKey), "pruning runs at most once an hour");
        assertEquals(List.of("1 days ago", "2 days ago", "3 days ago"),
                summaries(history("dated", null, null, null, null, null, null)));
    }

    @Test
    void aWriteInOneAccountPrunesExpiredHistoryOfEveryAccount() {
        InMemoryStorage<String, AlarmHistoryItem> raw = new InMemoryStorage<>();
        String foreignKey = "111111111111/" + REGION + "::00000000000000000001";
        raw.put(foreignKey, new AlarmHistoryItem(1, "foreign", "MetricAlarm",
                Instant.now().minus(Duration.ofDays(40)).toEpochMilli(), "StateUpdate", "expired", "{}"));
        service = new CloudWatchMetricsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(raw, null, "000000000000"),
                new RegionResolver(REGION, "000000000000"), null);

        putAlarm("writer");

        assertFalse(raw.keys().contains(foreignKey));
        assertEquals(1, raw.keys().size());
    }

    private MetricAlarm putAlarm(String name) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName(name);
        service.putMetricAlarm(alarm, REGION);
        return alarm;
    }

    private List<AlarmHistoryItem> history(String name, List<String> types, String itemType,
                                           Instant start, Instant end, Integer max, String scanBy) {
        return service.describeAlarmHistory(name, types, itemType, start, end, max, null, scanBy, REGION).items();
    }

    private static List<String> summaries(List<AlarmHistoryItem> items) {
        return items.stream().map(AlarmHistoryItem::historySummary).toList();
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
