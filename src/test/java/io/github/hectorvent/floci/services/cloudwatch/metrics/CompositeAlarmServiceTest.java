package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Alarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.AlarmHistoryItem;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.CompositeAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.testing.ThreadStates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CompositeAlarmServiceTest {
    private static final String REGION = "cn-north-1";
    /** A child's transition time in a composite alarm's StateReason, as CloudWatch prints it. */
    private static final DateTimeFormatter REASON_TIME = DateTimeFormatter
            .ofPattern("EEEE dd MMMM, yyyy HH:mm:ss 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    /** A child's state time in triggeringAlarms, as CloudWatch writes it. */
    private static final DateTimeFormatter DATA_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT).withZone(ZoneOffset.UTC);
    private final CloudWatchMetricsService service = new CloudWatchMetricsService(
            new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver(REGION, "123456789012"));

    @Test
    void metricTransitionsPropagateThroughNamesArnsAndNestedComposites() {
        MetricAlarm metric = new MetricAlarm();
        metric.setAlarmName("latency");
        service.putMetricAlarm(metric, REGION);
        put("maintenance", "FALSE");
        put("combined", "ALARM(\"" + metric.getAlarmArn() + "\") AND NOT ALARM(\"maintenance\")");
        put("outer", "ALARM(combined) OR (TRUE AND FALSE)");
        assertEquals("OK", state("outer"));
        service.setAlarmState("latency", "ALARM", "high latency", null, REGION);
        assertEquals("ALARM", state("combined"));
        assertEquals("ALARM", state("outer"));
        service.setAlarmState("latency", "OK", "recovered", null, REGION);
        assertEquals("OK", state("outer"));
        assertEquals(List.of(metric), service.describeAlarms(null, null, REGION));
        assertTrue(service.describeCompositeAlarms(null, null, "us-east-1").isEmpty());
        assertTrue(service.describeCompositeAlarms(List.of("combined"), null, REGION)
                .getFirst().getAlarmArn().startsWith("arn:aws-cn:cloudwatch:"));
    }

    @Test
    void updatesPreserveStateAndTagsUntilTheNextDependencyChange() {
        put("source", "FALSE");
        CompositeAlarm alarm = put("aggregate", "ALARM(source)");
        service.tagResource(alarm.getAlarmArn(), Map.of("owner", "retained"), REGION);
        service.setAlarmState("aggregate", "ALARM", "manual", null, REGION);
        CompositeAlarm replacement = put("aggregate", "OK(source)");
        assertEquals("ALARM", replacement.getStateValue());
        assertEquals(Map.of("owner", "retained"), replacement.getTags());
        service.setAlarmState("source", "ALARM", "dependency changed", null, REGION);
        assertEquals("OK", state("aggregate"));
    }

    @Test
    void unrelatedWritesPreserveManualCompositeStatesAndParentStates() {
        put("source", "FALSE");
        put("aggregate", "ALARM(source)");
        put("parent", "ALARM(aggregate)");
        service.setAlarmState("aggregate", "ALARM", "manual", null, REGION);
        assertEquals("ALARM", state("parent"));
        put("unrelated", "TRUE");
        assertEquals("ALARM", state("aggregate"));
        assertEquals("ALARM", state("parent"));
        MetricAlarm metric = new MetricAlarm();
        metric.setAlarmName("unrelated-metric");
        service.putMetricAlarm(metric, REGION);
        assertEquals("ALARM", state("aggregate"));
        service.setAlarmState("unrelated-metric", "ALARM", "unrelated", null, REGION);
        assertEquals("ALARM", state("aggregate"));
        service.deleteAlarms(List.of("unrelated"), REGION);
        assertEquals("ALARM", state("aggregate"));
        assertEquals("ALARM", state("parent"));
        service.setAlarmState("source", "ALARM", "child changed", null, REGION);
        service.setAlarmState("source", "OK", "child recovered", null, REGION);
        assertEquals("OK", state("aggregate"));
        assertEquals("OK", state("parent"));
    }

    @Test
    void unchangedChildStateDoesNotResetManuallySetParent() {
        put("source", "FALSE");
        put("child", "ALARM(source) AND FALSE");
        put("parent", "ALARM(child)");
        service.setAlarmState("parent", "ALARM", "manual", null, REGION);
        service.setAlarmState("source", "ALARM", "child condition remains false", null, REGION);
        assertEquals("OK", state("child"));
        assertEquals("ALARM", state("parent"));
    }

    @Test
    void aRuleMustNameExistingAlarmsWhichThenCannotBeDeleted() {
        assertRejected("Could not save the composite alarm as alarms [" + arn("late")
                + "] in the alarm rule do not exist", () -> put("watcher", "INSUFFICIENT_DATA(late)"));
        assertTrue(service.describeCompositeAlarms(List.of("watcher"), null, REGION).isEmpty());
        metric("late");
        put("watcher", "INSUFFICIENT_DATA(late)");
        put("outer", "ALARM(watcher)");
        assertEquals("ALARM", state("watcher"));
        assertEquals("ALARM", state("outer"));
        assertRejected("Cannot delete late as there are composite alarm(s) depending on it.", () -> delete("late"));
        assertRejected("Cannot delete watcher as there are composite alarm(s) depending on it.",
                () -> delete("watcher"));
        assertEquals(1, service.describeAlarms(List.of("late"), null, REGION).size());
        assertEquals("ALARM", state("watcher"));
        assertEquals("ALARM", state("outer"));
    }

    @Test
    void tagReadIsAnIndependentSnapshotOfTheOwnedAlarm() {
        CompositeAlarm alarm = put("tag-snapshot", "TRUE");
        service.tagResource(alarm.getAlarmArn(), Map.of("team", "old"), REGION);
        Map<String, String> read = service.listTagsForResource(alarm.getAlarmArn(), REGION);
        service.tagResource(alarm.getAlarmArn(), Map.of("team", "new"), REGION);
        assertEquals(Map.of("team", "old"), read);
        read.put("injected", "unowned");
        assertEquals(Map.of("team", "new"), service.listTagsForResource(alarm.getAlarmArn(), REGION));
    }

    @Test
    void cyclesStopEvaluationEvenBehindShortCircuitAndMustBeBrokenBeforeDelete() {
        put("a", "FALSE");
        put("b", "ALARM(a)");
        put("a", "FALSE AND ALARM(b)");
        service.setAlarmState("b", "ALARM", "manual", null, REGION);
        assertRejected("Cannot delete a as there are composite alarm(s) depending on it.", () -> delete("a"));
        assertEquals(2, service.describeCompositeAlarms(null, null, REGION).size());
        put("a", "FALSE");
        service.deleteAlarms(List.of("b"), REGION);
        assertEquals("OK", state("a"));
        service.deleteAlarms(List.of("a"), REGION);
        assertTrue(service.describeCompositeAlarms(null, null, REGION).isEmpty());
    }

    @Test
    void manuallyChangingOneMemberDoesNotResumeACyclicDependency() {
        put("b", "FALSE");
        put("a", "ALARM(b)");
        put("b", "ALARM(a)");
        assertEquals("OK", state("a"));
        service.setAlarmState("b", "ALARM", "manual", null, REGION);
        assertEquals("OK", state("a"));
        put("b", "TRUE");
        service.setAlarmState("b", "ALARM", "same state", null, REGION);
        assertEquals("OK", state("a"));
        service.setAlarmState("b", "OK", "cycle broken", null, REGION);
        service.setAlarmState("b", "ALARM", "cycle broken", null, REGION);
        assertEquals("ALARM", state("a"));
    }

    @Test
    void parentQuotaTreatsAChildNameAndItsArnAsTheSameAlarm() {
        CompositeAlarm child = put("child", "FALSE");
        for (int index = 0; index < 150; index++) {
            put("parent-" + index, "ALARM(child)");
        }
        assertThrows(AwsException.class, () -> put("overflow", "ALARM(\"" + child.getAlarmArn() + "\")"));
        service.deleteAlarms(List.of("parent-0"), REGION);
        assertEquals("OK", put("replacement", "ALARM(\"" + child.getAlarmArn() + "\")").getStateValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ALARM()", "TRUE junk", "TRUE AND", "ALARM(\"unterminated)",
            "(TRUE", "ALARM(x) XOR FALSE", "ALARM(\"x\\z\")"})
    void invalidRulesDoNotReplaceAnExistingAlarm(String invalid) {
        put("saved", "TRUE");
        assertThrows(AwsException.class, () -> put("saved", invalid));
        assertEquals("TRUE", service.describeCompositeAlarms(List.of("saved"), null, REGION).getFirst().getAlarmRule());
        assertEquals("ALARM", state("saved"));
    }

    @Test
    void quotasAndAtomicCreatePreventExcessReferencesAndForeignNameReplacement() {
        String many = IntStream.range(0, 101).mapToObj(i -> "ALARM(a" + i + ")")
                .collect(Collectors.joining(" OR "));
        assertThrows(AwsException.class, () -> put("too-many", many));
        put("owned", "TRUE");
        CompositeAlarm foreign = new CompositeAlarm();
        foreign.setAlarmName("owned");
        foreign.setAlarmRule("FALSE");
        assertThrows(AwsException.class, () -> service.putCompositeAlarm(foreign, REGION, false));
        assertEquals("ALARM", state("owned"));
        assertThrows(AwsException.class, () -> put("deep", "(".repeat(250) + "TRUE" + ")".repeat(250)));
        assertEquals("ALARM", put("many-not", "NOT ".repeat(502) + "TRUE").getStateValue());
        assertThrows(AwsException.class, () -> put("elements", "FALSE OR ".repeat(500) + "TRUE"));
    }

    @Test
    void mixedPersistenceLoadsLegacyMetricsAndRoundTripsComposites() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TypeReference<Map<String, Alarm>> type = new TypeReference<>() {};
        Map<String, Alarm> legacy = mapper.readValue(
                "{\"legacy\":{\"alarmName\":\"old\",\"metricName\":\"CPU\",\"stateValue\":\"OK\"}}", type);
        assertInstanceOf(MetricAlarm.class, legacy.get("legacy"));
        CompositeAlarm alarm = put("new", "FALSE");
        Map<String, Alarm> restored = mapper.readValue(mapper.writeValueAsBytes(Map.of("new", alarm)), type);
        assertEquals("FALSE", assertInstanceOf(CompositeAlarm.class, restored.get("new")).getAlarmRule());
        assertEquals("OK", restored.get("new").getStateValue());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentTagChangesPreserveTheUpdatedDefinition(boolean remove) throws Exception {
        CountDownLatch updating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryStorage<String, Alarm> storage = new InMemoryStorage<>() {
            @Override
            public void put(String key, Alarm value) {
                if (value instanceof CompositeAlarm composite && "TRUE".equals(composite.getAlarmRule())) {
                    updating.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                }
                super.put(key, value);
            }
        };
        CloudWatchMetricsService concurrent = new CloudWatchMetricsService(new InMemoryStorage<>(), storage,
                new RegionResolver(REGION, "123456789012"));
        CompositeAlarm original = new CompositeAlarm();
        original.setAlarmName("concurrent");
        original.setAlarmRule("FALSE");
        original.setTags(new HashMap<>(Map.of("team", "old")));
        concurrent.putCompositeAlarm(original, REGION);
        CompositeAlarm replacement = new CompositeAlarm();
        replacement.setAlarmName("concurrent");
        replacement.setAlarmRule("TRUE");
        AtomicReference<Thread> tagThread = new AtomicReference<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> update = executor.submit(() -> concurrent.putCompositeAlarm(replacement, REGION));
            Future<?> tags;
            try {
                assertTrue(updating.await(2, TimeUnit.SECONDS));
                tags = executor.submit(() -> {
                    tagThread.set(Thread.currentThread());
                    if (remove) {
                        concurrent.untagResource(original.getAlarmArn(), List.of("team"), REGION);
                    } else {
                        concurrent.tagResource(original.getAlarmArn(), Map.of("team", "new"), REGION);
                    }
                });
                ThreadStates.awaitBlockedOrDone(tagThread, tags);
                assertFalse(tags.isDone(), "tag mutation must share the definition update lock");
            } finally {
                release.countDown();
            }
            update.get(2, TimeUnit.SECONDS);
            tags.get(2, TimeUnit.SECONDS);
        }
        CompositeAlarm result = concurrent.describeCompositeAlarms(List.of("concurrent"), null, REGION).getFirst();
        assertEquals("TRUE", result.getAlarmRule());
        assertEquals(remove ? Map.of() : Map.of("team", "new"), result.getTags());
    }

    @Test
    void supplementaryCharactersRespectAlarmAndRuleLengthBounds() {
        String scalar = "\uD83D\uDE00";
        put(scalar.repeat(255), "TRUE");
        assertThrows(AwsException.class, () -> put(scalar.repeat(256), "TRUE"));
        String rule = "ALARM(\"" + scalar.repeat(10231) + "\")";
        assertDoesNotThrow(() -> CompositeAlarmRule.parse(rule));
        assertThrows(AwsException.class, () -> CompositeAlarmRule.parse(rule + " "));
    }

    @Test
    void blankAlarmFieldsRetainLengthErrorPrecedence() {
        for (String name : List.of("", "   ", " ".repeat(256))) {
            AwsException error = assertThrows(AwsException.class, () -> put(name, "TRUE"));
            String bound = name.length() > 255 ? "less than or equal to 255" : "greater than or equal to 1";
            assertEquals("1 validation error detected: Value '" + name
                    + "' at 'alarmName' failed to satisfy constraint: Member must have length " + bound,
                    error.getMessage());
        }
        for (String rule : List.of("", "   ", " ".repeat(10241))) {
            AwsException error = assertThrows(AwsException.class, () -> CompositeAlarmRule.parse(rule));
            String bound = rule.length() > 10240 ? "less than or equal to 10240" : "greater than or equal to 1";
            assertEquals("1 validation error detected: Value '" + rule
                    + "' at 'alarmRule' failed to satisfy constraint: Member must have length " + bound,
                    error.getMessage());
        }
        assertEquals("1 validation error detected: Value null at 'alarmName' failed to satisfy constraint: "
                + "Member must not be null", assertThrows(AwsException.class, () -> put(null, "TRUE")).getMessage());
        assertEquals("1 validation error detected: Value null at 'alarmRule' failed to satisfy constraint: "
                + "Member must not be null", assertThrows(AwsException.class,
                        () -> CompositeAlarmRule.parse(null)).getMessage());
    }

    @Test
    void compositeHistoryIsListedOnlyForTheCompositeAlarmType() {
        put("aggregate", "TRUE");
        assertTrue(service.describeAlarmHistory("aggregate", null, null, null, null, null, null, null, REGION)
                .items().isEmpty());
        List<AlarmHistoryItem> items = service.describeAlarmHistory("aggregate", List.of("CompositeAlarm"), null,
                null, null, null, null, null, REGION).items();
        assertEquals(List.of("Alarm updated from INSUFFICIENT_DATA to ALARM", "Alarm \"aggregate\" created"),
                items.stream().map(AlarmHistoryItem::historySummary).toList());
        assertEquals(List.of("CompositeAlarm", "CompositeAlarm"), items.stream().map(AlarmHistoryItem::alarmType).toList());
        assertEquals(List.of("Alarm \"aggregate\" created", "Alarm updated from INSUFFICIENT_DATA to ALARM"),
                service.describeAlarmHistory("aggregate", List.of("CompositeAlarm"), null, null, null, null, null,
                        "TimestampAscending", REGION).items().stream().map(AlarmHistoryItem::historySummary).toList());
    }

    @Test
    void childTransitionsRecordCompositeStateUpdates() throws Exception {
        MetricAlarm metric = new MetricAlarm();
        metric.setAlarmName("child");
        service.putMetricAlarm(metric, REGION);
        put("parent", "ALARM(child)");
        service.setAlarmState("child", "ALARM", "breached", null, REGION);

        List<AlarmHistoryItem> items = service.describeAlarmHistory(null, List.of("MetricAlarm", "CompositeAlarm"),
                "StateUpdate", null, null, null, null, null, REGION).items();
        assertEquals(List.of("parent:Alarm updated from OK to ALARM", "child:Alarm updated from INSUFFICIENT_DATA to ALARM",
                "parent:Alarm updated from INSUFFICIENT_DATA to OK"),
                items.stream().map(item -> item.alarmName() + ":" + item.historySummary()).toList());
        JsonNode data = new ObjectMapper().readTree(items.getFirst().historyData());
        assertEquals(arn("child") + " transitioned to ALARM at " + reasonTime("child"),
                data.path("newState").path("stateReason").asText());
        assertEquals(arn("child"), data.path("newState").path("stateReasonData")
                .path("triggeringAlarms").path(0).path("arn").asText());
        assertEquals(arn("parent") + " was created and its alarm rule evaluates to OK",
                data.path("oldState").path("stateReason").asText());
        assertEquals("INSUFFICIENT_DATA", data.path("oldState").path("stateReasonData")
                .path("triggeringAlarms").path(0).path("state").path("value").asText());
    }

    /**
     * Replays a published probe of CloudWatch in eu-central-1, leaving out its AT_LEAST rules: at every
     * step the composite alarms take the state, StateReason and triggeringAlarms that CloudWatch
     * reported. The probe and its two runs of 2026-10-15, with the same results, are at
     * https://github.com/ministackorg/ministack/pull/2260.
     */
    @Test
    void evaluationMatchesWhatCloudWatchReportedForTheSameSteps() {
        for (String name : List.of("m-a", "m-b", "m-c")) {
            metric(name);
        }
        Map<String, String> rules = new LinkedHashMap<>();
        rules.put("c-and", "ALARM(\"m-a\") AND ALARM(\"m-b\")");
        rules.put("c-or", "ALARM(" + arn("m-a") + ") OR ALARM(m-b)");
        rules.put("c-not", "NOT ALARM(\"m-a\")");
        rules.put("c-ok", "OK(\"m-a\")");
        rules.put("c-ins", "INSUFFICIENT_DATA(\"m-b\")");
        rules.put("c-true", "TRUE");
        rules.put("c-false", "FALSE");
        rules.put("c-nest", "ALARM(\"c-or\")");
        rules.put("c-mix", "(ALARM(\"m-a\") OR OK(\"m-b\")) AND NOT INSUFFICIENT_DATA(\"m-b\")");
        rules.put("c-rev", "ALARM(\"m-b\") AND ALARM(\"m-a\")");
        rules.put("c-prec", "TRUE OR TRUE AND FALSE");
        rules.put("c-notprec", "NOT TRUE AND FALSE");
        rules.forEach(this::put);

        assertStates("c-and OK, c-false OK, c-ins ALARM, c-mix OK, c-nest OK, c-not ALARM, c-notprec OK, c-ok OK, "
                + "c-or OK, c-prec ALARM, c-rev OK, c-true ALARM");
        for (String name : rules.keySet()) {
            assertEquals(arn(name) + " was created and its alarm rule evaluates to " + state(name),
                    alarm(name).getStateReason());
        }
        assertTriggering("c-and", "m-b INSUFFICIENT_DATA", "m-a INSUFFICIENT_DATA");
        assertTriggering("c-false");
        assertTriggering("c-ins", "m-b INSUFFICIENT_DATA");
        assertTriggering("c-mix", "m-b INSUFFICIENT_DATA", "m-a INSUFFICIENT_DATA");
        assertTriggering("c-nest", "c-or OK");
        assertTriggering("c-not", "m-a INSUFFICIENT_DATA");
        assertTriggering("c-ok", "m-a INSUFFICIENT_DATA");
        assertTriggering("c-or", "m-b INSUFFICIENT_DATA", "m-a INSUFFICIENT_DATA");
        assertTriggering("c-rev", "m-b INSUFFICIENT_DATA", "m-a INSUFFICIENT_DATA");
        assertTriggering("c-true");

        set("m-a", "ALARM");
        assertStates("c-and OK, c-mix OK, c-nest ALARM, c-not OK, c-ok OK, c-or ALARM, c-rev OK");
        assertTransitionedBecause("c-or", "m-a", "m-a ALARM");
        assertTransitionedBecause("c-nest", "c-or", "c-or ALARM");
        assertTransitionedBecause("c-not", "m-a", "m-a ALARM");
        assertEquals(arn("c-and") + " was created and its alarm rule evaluates to OK", alarm("c-and").getStateReason());

        set("m-b", "ALARM");
        assertStates("c-and ALARM, c-ins OK, c-mix ALARM, c-nest ALARM, c-or ALARM, c-rev ALARM");
        assertTransitionedBecause("c-and", "m-b", "m-b ALARM", "m-a ALARM");
        assertTransitionedBecause("c-ins", "m-b", "m-b ALARM");
        assertTransitionedBecause("c-mix", "m-b", "m-b ALARM", "m-a ALARM");
        assertTransitionedBecause("c-rev", "m-b", "m-b ALARM", "m-a ALARM");

        set("m-a", "OK");
        assertStates("c-and OK, c-mix OK, c-nest ALARM, c-not ALARM, c-ok ALARM, c-or ALARM, c-rev OK");
        for (String name : List.of("c-and", "c-mix", "c-not", "c-ok", "c-rev")) {
            assertTransitionedBecause(name, "m-a", "m-a OK");
        }

        set("m-b", "OK");
        assertStates("c-and OK, c-ins OK, c-mix ALARM, c-nest OK, c-or OK, c-rev OK");
        assertTransitionedBecause("c-mix", "m-b", "m-b OK");
        assertTransitionedBecause("c-nest", "c-or", "c-or OK");
        assertTransitionedBecause("c-or", "m-b", "m-b OK", "m-a OK");

        set("m-a", "ALARM");
        put("c-late", "ALARM(\"m-a\")");
        assertEquals("ALARM", state("c-late"));
        assertTriggering("c-late", "m-a ALARM");

        set("c-true", "OK");
        assertEquals("probe", alarm("c-true").getStateReason());
        assertNull(alarm("c-true").getStateReasonData());

        set("c-and", "ALARM");
        set("m-b", "INSUFFICIENT_DATA");
        assertEquals("OK", state("c-and"));
        assertTransitionedBecause("c-and", "m-b", "m-b INSUFFICIENT_DATA");

        put("c-false", "TRUE");
        assertEquals("ALARM", state("c-false"));
        assertEquals(arn("c-false") + " was updated and its new alarm rule evaluates to ALARM",
                alarm("c-false").getStateReason());
        assertTriggering("c-false");

        Alarm sameState = alarm("c-ok");
        String reason = sameState.getStateReason();
        String data = sameState.getStateReasonData();
        long updated = sameState.getStateUpdatedTimestamp();
        put("c-ok", "NOT ALARM(\"m-a\")");
        assertEquals("OK", state("c-ok"));
        assertEquals(reason, alarm("c-ok").getStateReason());
        assertEquals(data, alarm("c-ok").getStateReasonData());
        assertEquals(updated, alarm("c-ok").getStateUpdatedTimestamp());

        put("c-not", "ALARM(\"m-a\") OR ALARM(\"m-b\")");
        assertEquals("ALARM", state("c-not"));
        assertEquals(arn("c-not") + " was updated and its new alarm rule evaluates to ALARM",
                alarm("c-not").getStateReason());
        assertTriggering("c-not", "m-a ALARM");

        set("m-b", "OK");
        set("m-a", "OK");
        put("c-dist", "ALARM(\"m-a\") OR ALARM(\"m-b\")");
        put("c-ord", "ALARM(\"m-b\") AND ALARM(\"m-a\")");
        assertTriggering("c-dist", "m-a OK", "m-b OK");
        assertTriggering("c-ord", "m-a OK", "m-b OK");
        set("m-b", "INSUFFICIENT_DATA");
        assertEquals(arn("c-dist") + " was created and its alarm rule evaluates to OK", alarm("c-dist").getStateReason());
        set("m-a", "ALARM");
        assertEquals("ALARM", state("c-dist"));
        assertTransitionedBecause("c-dist", "m-a", "m-a ALARM");
        assertEquals("OK", state("c-ord"));

        assertRejected("Could not save the composite alarm as alarms [" + arn("does-not-exist")
                + "] in the alarm rule do not exist", () -> put("c-bad1", "ALARM(\"does-not-exist\")"));
        assertTrue(service.describeCompositeAlarms(List.of("c-bad1"), null, REGION).isEmpty());

        put("c-or", "ALARM(\"c-nest\")");
        assertStates("c-nest ALARM, c-or ALARM");
        assertRejected("Cannot delete m-a as there are composite alarm(s) depending on it.", () -> delete("m-a"));
        assertRejected("Cannot delete c-or as there are composite alarm(s) depending on it.", () -> delete("c-or"));
        assertRejected("Cannot delete [2] Composite Alarms as only one composite alarm delete is allowed in single "
                + "request", () -> delete("c-true", "c-false"));
        delete("does-not-exist", "c-ok");
        assertTrue(service.describeCompositeAlarms(List.of("c-ok"), null, REGION).isEmpty());
        delete("does-not-exist");
        assertRejected("Cannot delete [2] Composite Alarms as only one composite alarm delete is allowed in single "
                + "request", () -> delete("c-nest", "c-or"));
        put("c-solo", "ALARM(\"m-c\")");
        assertRejected("Cannot delete m-c as there are composite alarm(s) depending on it.", () -> delete("m-c", "c-solo"));
        assertEquals(1, service.describeAlarms(List.of("m-c"), null, REGION).size());
        assertEquals("ALARM(\"m-c\")", ((CompositeAlarm) alarm("c-solo")).getAlarmRule());
    }

    @Test
    void deletingSeveralReferencedAlarmsNamesThemAllAndDeletesNothing() {
        metric("x");
        metric("y");
        metric("free");
        put("both", "ALARM(x) OR ALARM(\"" + arn("y") + "\")");
        assertRejected("Cannot delete x,y as there are composite alarm(s) depending on them.",
                () -> delete("free", "x", "y", "x"));
        assertEquals(3, service.describeAlarms(null, null, REGION).size());
        assertRejected("Cannot delete y as there are composite alarm(s) depending on it.", () -> delete("y", "both"));
        delete(arn("both"));
        assertEquals(1, service.describeCompositeAlarms(null, null, REGION).size());
        delete("both", "both");
        delete("x", "y", "free");
        assertTrue(service.describeAlarms(null, null, REGION).isEmpty());
    }

    @Test
    void aRuleNamingMissingAlarmsIsRefusedWithTheirArnsInRuleOrder() {
        metric("present");
        assertRejected("Could not save the composite alarm as alarms [" + arn("first") + ", " + arn("second")
                + "] in the alarm rule do not exist", () -> put("orphan", "ALARM(first) OR ALARM(present) OR OK(\""
                + arn("second") + "\") OR ALARM(\"first\")"));
        assertTrue(service.describeCompositeAlarms(null, null, REGION).isEmpty());

        put("kept", "ALARM(present)");
        assertRejected("Could not save the composite alarm as alarms [" + arn("gone")
                + "] in the alarm rule do not exist", () -> put("kept", "ALARM(gone)"));
        assertEquals("ALARM(present)", ((CompositeAlarm) alarm("kept")).getAlarmRule());
    }

    @Test
    void anUpdateIsEvaluatedAndATransitionReachesItsParents() {
        metric("child");
        set("child", "ALARM");
        put("middle", "OK(child)");
        put("top", "ALARM(middle)");
        assertEquals("OK", state("middle"));
        assertEquals("OK", state("top"));

        put("middle", "ALARM(child)");
        assertEquals("ALARM", state("middle"));
        assertEquals(arn("middle") + " was updated and its new alarm rule evaluates to ALARM",
                alarm("middle").getStateReason());
        assertTriggering("middle", "child ALARM");
        assertEquals("ALARM", state("top"));
        assertTransitionedBecause("top", "middle", "middle ALARM");

        Alarm before = alarm("top");
        String reason = before.getStateReason();
        long transitioned = before.getStateTransitionedTimestamp();
        put("top", "ALARM(middle) OR ALARM(child)");
        assertEquals(reason, alarm("top").getStateReason());
        assertEquals(transitioned, alarm("top").getStateTransitionedTimestamp());

        set("top", "OK");
        put("top", "ALARM(middle) OR ALARM(child)");
        assertEquals("ALARM", state("top"));
        assertEquals(arn("top") + " was updated and its new alarm rule evaluates to ALARM", alarm("top").getStateReason());
        assertTriggering("top", "middle ALARM", "child ALARM");
    }

    /**
     * A composite alarm set with SetAlarmState "returns to its actual state only once any of its
     * children alarms change state"
     * (https://docs.aws.amazon.com/AmazonCloudWatch/latest/APIReference/API_SetAlarmState.html):
     * a metric alarm child's update or a SetAlarmState to the state it already has does not change
     * its state.
     */
    @Test
    void aManualStateHoldsThroughAChildsUpdateAndSameStateSet() {
        metric("m");
        set("m", "ALARM");
        put("c", "ALARM(m)");
        set("c", "OK");
        MetricAlarm update = new MetricAlarm();
        update.setAlarmName("m");
        update.setThreshold(5);
        service.putMetricAlarm(update, REGION);
        set("m", "ALARM");
        assertEquals("OK", state("c"));
        assertEquals("probe", alarm("c").getStateReason());

        set("m", "OK");
        set("m", "ALARM");
        assertEquals("ALARM", state("c"));
        assertTransitionedBecause("c", "m", "m ALARM");
    }

    /** The cycle between c and d is not evaluated, but p above it follows its other child. */
    @Test
    void aCompositeAlarmAboveACycleIsStillEvaluated() {
        metric("m");
        put("c", "ALARM(m)");
        put("d", "ALARM(c)");
        put("c", "ALARM(d) OR ALARM(m)");
        put("p", "ALARM(c) OR ALARM(m)");
        set("m", "ALARM");
        assertEquals("ALARM", state("p"));
        assertTransitionedBecause("p", "m", "m ALARM");
    }

    /** Rules saved before missing children were refused can name an alarm that is created later. */
    @Test
    void aCompositeAlarmStoredOverAMissingAlarmIsEvaluatedWhenThatAlarmIsCreated() {
        InMemoryStorage<String, Alarm> alarms = new InMemoryStorage<>();
        CloudWatchMetricsService stored = new CloudWatchMetricsService(new InMemoryStorage<>(), alarms,
                new RegionResolver(REGION, "123456789012"));
        CompositeAlarm watcher = new CompositeAlarm();
        watcher.setAlarmName("watcher");
        watcher.setAlarmArn(arn("watcher"));
        watcher.setAlarmRule("INSUFFICIENT_DATA(late)");
        watcher.setRegion(REGION);
        watcher.setStateValue("OK");
        alarms.put(REGION + "::watcher", watcher);
        MetricAlarm late = new MetricAlarm();
        late.setAlarmName("late");
        stored.putMetricAlarm(late, REGION);
        CompositeAlarm evaluated = stored.describeCompositeAlarms(List.of("watcher"), null, REGION).getFirst();
        assertEquals("ALARM", evaluated.getStateValue());
        assertTrue(evaluated.getStateReason().startsWith(arn("late") + " transitioned to INSUFFICIENT_DATA at "),
                evaluated.getStateReason());
    }

    @Test
    void aParentOfTwoChildrenThatChangedTogetherNamesTheLastOfThem() {
        metric("m");
        put("c", "ALARM(m)");
        put("p", "ALARM(c) OR ALARM(m)");
        set("m", "ALARM");
        assertTransitionedBecause("c", "m", "m ALARM");
        assertTransitionedBecause("p", "c", "c ALARM", "m ALARM");
    }

    @Test
    void stateReasonDataStaysWithinItsDocumentedLengthAndKeepsTheNewestChildren() throws Exception {
        String prefix = "child-with-a-long-name-to-fill-state-reason-data-";
        IntStream.range(0, 100).forEach(index -> metric(prefix + index));
        put("wide", IntStream.range(0, 100).mapToObj(index -> "ALARM(" + prefix + index + ")")
                .collect(Collectors.joining(" OR ")));
        String data = alarm("wide").getStateReasonData();
        assertTrue(data.length() <= 4000, "StateReasonData is " + data.length() + " characters");
        List<String> listed = triggering("wide");
        assertTrue(listed.size() > 10 && listed.size() < 100, listed.size() + " children listed");
        assertEquals(prefix + "99 INSUFFICIENT_DATA", listed.getFirst());
    }

    /**
     * StateReasonData's 4000 is counted in Unicode code points, as its length check counts it. An emoji is one
     * code point but two UTF-16 units, so each emoji child here adds 347 code points to the list, its comma
     * included, but 547 units. The newest child's 17-character name brings 12 entries to exactly 4000 code
     * points, which still fit; counted in units, only 7 entries would.
     */
    @Test
    void stateReasonDataCountsCodePointsAsItsLengthLimitDoes() {
        String emoji = "\uD83D\uDE00";
        List<String> names = new ArrayList<>(IntStream.range(0, 15)
                .mapToObj(index -> emoji.repeat(200) + String.format("-%02d", index)).toList());
        names.add("exactly-4000-edge");
        names.forEach(this::metric);
        put("wide", names.stream().map(name -> "ALARM(\"" + name + "\")").collect(Collectors.joining(" OR ")));
        List<String> listed = triggering("wide");
        assertEquals(12, listed.size(), "children listed");
        assertEquals("exactly-4000-edge INSUFFICIENT_DATA", listed.getFirst());
        assertEquals(names.get(4) + " INSUFFICIENT_DATA", listed.getLast());
        String data = alarm("wide").getStateReasonData();
        assertEquals(4000, data.codePointCount(0, data.length()));
        assertTrue(data.length() > 4000, data.length() + " UTF-16 units");
    }

    private CompositeAlarm put(String name, String rule) {
        CompositeAlarm alarm = new CompositeAlarm();
        alarm.setAlarmName(name);
        alarm.setAlarmRule(rule);
        alarm.setActionsEnabled(true);
        service.putCompositeAlarm(alarm, REGION);
        return alarm;
    }

    private String state(String name) {
        return service.describeCompositeAlarms(List.of(name), null, REGION).getFirst().getStateValue();
    }

    private void metric(String name) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName(name);
        service.putMetricAlarm(alarm, REGION);
    }

    private void set(String name, String state) {
        service.setAlarmState(name, state, "probe", null, REGION);
    }

    private void delete(String... names) {
        service.deleteAlarms(List.of(names), REGION);
    }

    private Alarm alarm(String name) {
        return Stream.concat(service.describeAlarms(List.of(name), null, REGION).stream(),
                service.describeCompositeAlarms(List.of(name), null, REGION).stream()).findFirst().orElseThrow();
    }

    private static String arn(String name) {
        return "arn:aws-cn:cloudwatch:" + REGION + ":123456789012:alarm:" + name;
    }

    /** The child's last transition as a composite alarm's StateReason prints it, "Monday 14 September, 2026 06:29:58 UTC". */
    private String reasonTime(String child) {
        return REASON_TIME.format(Instant.ofEpochSecond(alarm(child).getStateTransitionedTimestamp()));
    }

    private void assertStates(String expected) {
        Map<String, String> wanted = new LinkedHashMap<>();
        for (String entry : expected.split(",")) {
            String[] parts = entry.trim().split(" ");
            wanted.put(parts[0], parts[1]);
        }
        Map<String, String> actual = new LinkedHashMap<>();
        wanted.keySet().forEach(name -> actual.put(name, state(name)));
        assertEquals(wanted, actual);
    }

    /** The composite last changed state because {@code child} did, and lists these triggering children. */
    private void assertTransitionedBecause(String name, String child, String... triggering) {
        assertEquals(arn(child) + " transitioned to " + alarm(child).getStateValue() + " at " + reasonTime(child),
                alarm(name).getStateReason());
        assertTriggering(name, triggering);
    }

    private void assertTriggering(String name, String... expected) {
        assertEquals(List.of(expected), triggering(name));
    }

    /** The StateReasonData children, newest first, each as "name STATE" after checking its timestamp. */
    private List<String> triggering(String name) {
        JsonNode data;
        try {
            data = new ObjectMapper().readTree(alarm(name).getStateReasonData());
        } catch (Exception e) {
            throw new AssertionError("StateReasonData of " + name + " is not JSON", e);
        }
        List<String> children = new ArrayList<>();
        for (JsonNode child : data.path("triggeringAlarms")) {
            String childArn = child.path("arn").asText();
            String childName = childArn.substring(childArn.lastIndexOf(':') + 1);
            assertEquals(DATA_TIME.format(Instant.ofEpochSecond(alarm(childName).getStateTransitionedTimestamp())),
                    child.path("state").path("timestamp").asText(), data.toString());
            children.add(childName + " " + child.path("state").path("value").asText());
        }
        return children;
    }

    private static void assertRejected(String message, Runnable call) {
        AwsException error = assertThrows(AwsException.class, call::run);
        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(message, error.getMessage());
    }
}
