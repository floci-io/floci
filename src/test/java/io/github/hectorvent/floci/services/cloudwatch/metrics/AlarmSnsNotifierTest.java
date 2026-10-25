package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.CompositeAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.sns.SnsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlarmSnsNotifierTest {
    private static final String REGION = "us-east-1";
    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000000:alarms";
    private final ObjectMapper mapper = new ObjectMapper();
    private SnsService sns;
    private CloudWatchMetricsService service;
    private MetricAlarm alarm;

    @BeforeEach
    void setUp() {
        sns = mock(SnsService.class);
        service = new CloudWatchMetricsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"), new AlarmSnsNotifier(sns, mapper));
        alarm = new MetricAlarm();
        alarm.setAlarmName("test-alarm");
        alarm.setActionsEnabled(true);
        alarm.setAlarmActions(List.of(TOPIC));
        alarm.setOkActions(List.of(TOPIC));
        alarm.setInsufficientDataActions(List.of(TOPIC));
        alarm.setEvaluationPeriods(3);
        alarm.setStatistic("Average");
        alarm.setMetricName("Errors");
        alarm.setNamespace("Local/PoC");
        service.putMetricAlarm(alarm, REGION);
    }

    @Test
    void publishesAwsPayloadForEachTransitionButNotRepeatedState() throws Exception {
        service.setAlarmState("test-alarm", "ALARM", "breached", null, REGION);
        service.setAlarmState("test-alarm", "ALARM", "still breached", null, REGION);
        service.setAlarmState("test-alarm", "OK", "recovered", null, REGION);
        service.setAlarmState("test-alarm", "INSUFFICIENT_DATA", "missing", null, REGION);
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(sns, times(3)).publish(eq(TOPIC), isNull(), messages.capture(), anyString(), eq(REGION));
        JsonNode first = mapper.readTree(messages.getAllValues().getFirst());
        assertEquals("test-alarm", first.path("AlarmName").asText());
        assertEquals("INSUFFICIENT_DATA", first.path("OldStateValue").asText());
        assertEquals("ALARM", first.path("NewStateValue").asText());
        assertEquals("000000000000", first.path("AWSAccountId").asText());
        assertEquals(alarm.getAlarmArn(), first.path("AlarmArn").asText());
        assertEquals("Errors", first.path("Trigger").path("MetricName").asText());
        assertFalse(first.path("Trigger").has("DatapointsToAlarm"));
        assertEquals("AVERAGE", first.path("Trigger").path("Statistic").asText());
        assertEquals("", first.path("Trigger").path("EvaluateLowSampleCountPercentile").asText());
        assertTrue(first.path("Trigger").path("EvaluateLowSampleCountPercentile").isTextual());
        assertNull(alarm.getDatapointsToAlarm());
        assertEquals("OK", mapper.readTree(messages.getAllValues().get(2)).path("OldStateValue").asText());
    }

    @Test
    void formatsBothTimestampsWithMillisAndUtcOffset() throws Exception {
        alarm.setAlarmConfigurationUpdatedTimestamp(1716280510L);
        service.setAlarmState("test-alarm", "ALARM", "breached", null, REGION);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sns).publish(eq(TOPIC), isNull(), message.capture(), anyString(), eq(REGION));
        JsonNode payload = mapper.readTree(message.getValue());
        assertEquals("2024-05-21T08:35:10.000+0000",
                payload.path("AlarmConfigurationUpdatedTimestamp").asText());
        String stateChangeTime = payload.path("StateChangeTime").asText();
        assertTrue(stateChangeTime
                .matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}\\+0000"));
        assertEquals(alarm.getStateUpdatedTimestamp(),
                Instant.parse(stateChangeTime.replace("+0000", "Z")).getEpochSecond());
    }

    @Test
    void preservesExplicitDatapointsToAlarmInPayload() throws Exception {
        alarm.setDatapointsToAlarm(2);
        alarm.setStatistic("Maximum");
        alarm.setEvaluateLowSampleCountPercentile("ignore");
        service.setAlarmState("test-alarm", "ALARM", "breached", null, REGION);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sns).publish(eq(TOPIC), isNull(), message.capture(), anyString(), eq(REGION));
        assertEquals(2, mapper.readTree(message.getValue()).path("Trigger").path("DatapointsToAlarm").intValue());
        JsonNode trigger = mapper.readTree(message.getValue()).path("Trigger");
        assertEquals("MAXIMUM", trigger.path("Statistic").asText());
        assertEquals("ignore", trigger.path("EvaluateLowSampleCountPercentile").asText());
        assertEquals(2, alarm.getDatapointsToAlarm());
    }

    @Test
    void disabledActionsDoNotPublish() {
        alarm.setActionsEnabled(false);
        service.setAlarmState("test-alarm", "ALARM", "breached", null, REGION);
        verifyNoInteractions(sns);
        assertEquals("ALARM", alarm.getStateValue());
    }

    @Test
    void failedTargetDoesNotPreventOtherTargetsOrStateChange() {
        String missing = "arn:aws:sns:us-east-1:000000000000:missing";
        alarm.setAlarmActions(List.of(missing, "arn:aws:autoscaling:us-east-1:000000000000:scalingPolicy:x", TOPIC));
        doThrow(new IllegalStateException("missing topic"))
                .when(sns).publish(eq(missing), isNull(), anyString(), anyString(), eq(REGION));
        assertDoesNotThrow(() -> service.setAlarmState("test-alarm", "ALARM", "breached", null, REGION));
        verify(sns).publish(eq(TOPIC), isNull(), anyString(), anyString(), eq(REGION));
        assertEquals("ALARM", alarm.getStateValue());
    }

    @Test
    void usesTopicRegionAndPreservesChinaPartition() {
        String topic = "arn:aws-cn:sns:cn-north-1:000000000000:alarms";
        alarm.setAlarmArn("arn:aws-cn:cloudwatch:cn-north-1:000000000000:alarm:test-alarm");
        alarm.setAlarmActions(List.of(topic));
        service.setAlarmState("test-alarm", "ALARM", "breached", null, REGION);
        verify(sns).publish(eq(topic), isNull(), contains("arn:aws-cn:cloudwatch:"), anyString(), eq("cn-north-1"));
    }

    @Test
    void blockedNotificationDoesNotHoldTheAlarmMutationLock() throws Exception {
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            if (call.<String>getArgument(3).startsWith("ALARM:")) {
                publishing.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return "message-id";
        }).when(sns).publish(eq(TOPIC), isNull(), anyString(), anyString(), eq(REGION));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> transition = executor.submit(() ->
                    service.setAlarmState("test-alarm", "ALARM", "breached", null, REGION));
            try {
                assertTrue(publishing.await(2, TimeUnit.SECONDS));
                Future<?> unrelated = executor.submit(() -> {
                    CompositeAlarm composite = new CompositeAlarm();
                    composite.setAlarmName("unrelated");
                    composite.setAlarmRule("FALSE");
                    service.putCompositeAlarm(composite, REGION);
                    service.setAlarmState("test-alarm", "OK", "recovered", null, REGION);
                });
                unrelated.get(2, TimeUnit.SECONDS);
                assertEquals("OK", alarm.getStateValue());
            } finally {
                release.countDown();
            }
            transition.get(2, TimeUnit.SECONDS);
        }
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(sns, times(2)).publish(eq(TOPIC), isNull(), messages.capture(), anyString(), eq(REGION));
        assertEquals("ALARM", mapper.readTree(messages.getAllValues().getFirst()).path("NewStateValue").asText());
        assertEquals("OK", mapper.readTree(messages.getAllValues().getLast()).path("NewStateValue").asText());
    }
}
