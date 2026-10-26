package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Alarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.CompositeAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.testing.ThreadStates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CompositeAlarmServiceTest {
    private static final String REGION = "cn-north-1";
    private final CloudWatchMetricsService service = new CloudWatchMetricsService(
            new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver(REGION, "123456789012"));

    @Test
    void metricTransitionsPropagateThroughNamesArnsAndNestedComposites() {
        MetricAlarm metric = new MetricAlarm();
        metric.setAlarmName("latency");
        service.putMetricAlarm(metric, REGION);
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
    void creationAndDeletionReevaluateOnlyReferencingComposites() {
        MetricAlarm metric = new MetricAlarm();
        metric.setAlarmName("late");
        put("watcher", "INSUFFICIENT_DATA(late)");
        put("outer", "ALARM(watcher)");
        assertEquals("OK", state("watcher"));
        service.putMetricAlarm(metric, REGION);
        assertEquals("ALARM", state("watcher"));
        assertEquals("ALARM", state("outer"));
        service.deleteAlarms(List.of("late"), REGION);
        assertEquals("OK", state("watcher"));
        assertEquals("OK", state("outer"));
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
        put("a", "FALSE AND ALARM(b)");
        put("b", "ALARM(a)");
        service.setAlarmState("b", "ALARM", "manual", null, REGION);
        assertThrows(AwsException.class, () -> service.deleteAlarms(List.of("a"), REGION));
        assertEquals(2, service.describeCompositeAlarms(null, null, REGION).size());
        put("a", "FALSE");
        service.deleteAlarms(List.of("b"), REGION);
        assertEquals("OK", state("a"));
        service.deleteAlarms(List.of("a"), REGION);
        assertTrue(service.describeCompositeAlarms(null, null, REGION).isEmpty());
    }

    @Test
    void manuallyChangingOneMemberDoesNotResumeACyclicDependency() {
        put("a", "ALARM(b)");
        put("b", "ALARM(a)");
        assertEquals("OK", state("a"));
        service.setAlarmState("b", "ALARM", "manual", null, REGION);
        assertEquals("OK", state("a"));
        put("b", "TRUE");
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
}
