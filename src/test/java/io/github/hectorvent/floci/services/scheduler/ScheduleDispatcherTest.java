package io.github.hectorvent.floci.services.scheduler;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.scheduler.model.DeadLetterConfig;
import io.github.hectorvent.floci.services.scheduler.model.RetryPolicy;
import io.github.hectorvent.floci.services.scheduler.model.Target;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScheduleDispatcherTest {

    private static final String ARN_PREFIX = "arn:aws:scheduler:eu-central-1:000000000000:schedule/default/";
    private static final String SQS_TARGET_ARN = "arn:aws:sqs:eu-central-1:000000000000:test-queue";
    private static final String DLQ_TARGET_ARN = "arn:aws:sqs:eu-central-1:000000000000:dead-letter";
    private static final String DLQ_QUEUE_URL = "http://localhost:4566/000000000000/dead-letter";
    private static final String TARGET_INPUT = "{\"hello\":\"world\"}";
    private static final String TARGET_REQUEST = "{\"MessageBody\":\"{\\\"hello\\\":\\\"world\\\"}\",\"QueueUrl\":\"http://localhost:4566/000000000000/test-queue\"}";

    private SchedulerService schedulerService;
    private ScheduleInvoker invoker;
    private SqsService sqsService;
    private ScheduleDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        schedulerService = mock(SchedulerService.class);
        invoker = mock(ScheduleInvoker.class);
        sqsService = mock(SqsService.class);

        EmulatorConfig.SchedulerServiceConfig schedulerCfg = mock(EmulatorConfig.SchedulerServiceConfig.class);
        when(schedulerCfg.enabled()).thenReturn(true);
        when(schedulerCfg.invocationEnabled()).thenReturn(true);
        when(schedulerCfg.tickIntervalSeconds()).thenReturn(10L);
        EmulatorConfig.ServicesConfig servicesCfg = mock(EmulatorConfig.ServicesConfig.class);
        when(servicesCfg.scheduler()).thenReturn(schedulerCfg);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.services()).thenReturn(servicesCfg);
        when(config.baseUrl()).thenReturn("http://localhost:4566");

        dispatcher = new ScheduleDispatcher(schedulerService, invoker, sqsService, config);
    }

    private Schedule newSchedule(String name, String expression, String state) {
        Schedule s = new Schedule();
        s.setName(name);
        s.setGroupName("default");
        s.setArn(ARN_PREFIX + name);
        s.setState(state);
        s.setScheduleExpression(expression);
        Target target = new Target();
        target.setArn(SQS_TARGET_ARN);
        target.setRoleArn("arn:aws:iam::000000000000:role/test");
        target.setInput(TARGET_INPUT);
        s.setTarget(target);
        s.setCreationDate(Instant.parse("2026-04-21T09:00:00Z"));
        return s;
    }

    private Schedule failingSchedule(String name, String expression, RetryPolicy retryPolicy) {
        Schedule s = newSchedule(name, expression, "ENABLED");
        s.getTarget().setRetryPolicy(retryPolicy);
        s.getTarget().setDeadLetterConfig(new DeadLetterConfig(DLQ_TARGET_ARN));
        when(schedulerService.listAllSchedules()).thenReturn(List.of(s));
        doThrow(new RuntimeException("target unavailable")).when(invoker)
                .invoke(eq(s.getTarget()), anyString());
        when(invoker.materializeRequest(eq(s.getTarget()), anyString())).thenReturn(TARGET_REQUEST);
        return s;
    }

    private void tickEveryTenSeconds(String start, int ticks) {
        Instant first = Instant.parse(start);
        for (int i = 0; i < ticks; i++) {
            dispatcher.tick(first.plusSeconds(10L * i));
        }
    }

    private List<Map<String, MessageAttributeValue>> deadLetters(int count) {
        ArgumentCaptor<Map<String, MessageAttributeValue>> attributes = ArgumentCaptor.captor();
        verify(sqsService, times(count)).sendMessage(eq(DLQ_QUEUE_URL), eq(TARGET_REQUEST), eq(0),
                isNull(), isNull(), attributes.capture(), eq("eu-central-1"));
        return attributes.getAllValues();
    }

    private static String attribute(Map<String, MessageAttributeValue> attributes, String name) {
        MessageAttributeValue value = attributes.get(name);
        assertNotNull(value, name);
        assertEquals("String", value.getDataType(), name);
        return value.getStringValue();
    }

    @Test
    void firesAtScheduleWhenDue() {
        Schedule s = newSchedule("at1", "at(2026-04-21T09:17:54)", "ENABLED");
        when(schedulerService.listAllSchedules()).thenReturn(List.of(s));

        dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z"));

        verify(invoker, times(1)).invoke(s.getTarget(), "eu-central-1");
    }

    @Test
    void skipsAtScheduleBeforeFireTime() {
        Schedule s = newSchedule("at1", "at(2026-04-21T09:17:54)", "ENABLED");
        when(schedulerService.listAllSchedules()).thenReturn(List.of(s));

        dispatcher.tick(Instant.parse("2026-04-21T09:17:00Z"));

        verify(invoker, never()).invoke(any(), anyString());
    }

    @Test
    void firesAtOnlyOncePerSchedule() {
        Schedule s = newSchedule("at1", "at(2026-04-21T09:17:54)", "ENABLED");
        when(schedulerService.listAllSchedules()).thenReturn(List.of(s));

        dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z"));
        dispatcher.tick(Instant.parse("2026-04-21T09:19:00Z"));
        dispatcher.tick(Instant.parse("2026-04-21T09:20:00Z"));

        verify(invoker, times(1)).invoke(eq(s.getTarget()), anyString());
    }

    @Test
    void deletesAtScheduleWhenActionAfterCompletionIsDelete() {
        Schedule s = newSchedule("at1", "at(2026-04-21T09:17:54)", "ENABLED");
        s.setActionAfterCompletion("DELETE");
        s.setAccountId("000000000000");
        when(schedulerService.listAllSchedules()).thenReturn(List.of(s));

        dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z"));

        verify(schedulerService, times(1)).deleteScheduleForAccount("000000000000", "at1", "default", "eu-central-1");
    }

    @Test
    void leavesAtScheduleInPlaceWhenActionAfterCompletionIsNotDelete() {
        Schedule s = newSchedule("at1", "at(2026-04-21T09:17:54)", "ENABLED");
        s.setActionAfterCompletion("NONE");
        when(schedulerService.listAllSchedules()).thenReturn(List.of(s));

        dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z"));

        verify(schedulerService, never()).deleteSchedule(anyString(), anyString(), anyString());
    }

    @Test
    void skipsDisabledSchedules() {
        Schedule s = newSchedule("at1", "at(2026-04-21T09:17:54)", "DISABLED");
        when(schedulerService.listAllSchedules()).thenReturn(List.of(s));

        dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z"));

        verify(invoker, never()).invoke(any(), anyString());
    }

    @Test
    void skipsBeforeStartDate() {
        Schedule s = newSchedule("at1", "at(2026-04-21T09:17:54)", "ENABLED");
        s.setStartDate(Instant.parse("2026-04-22T00:00:00Z"));
        when(schedulerService.listAllSchedules()).thenReturn(List.of(s));

        dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z"));

        verify(invoker, never()).invoke(any(), anyString());
    }

    @Test
    void skipsAfterEndDate() {
        Schedule s = newSchedule("at1", "at(2026-04-21T09:17:54)", "ENABLED");
        s.setEndDate(Instant.parse("2026-04-21T09:17:00Z"));
        when(schedulerService.listAllSchedules()).thenReturn(List.of(s));

        dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z"));

        verify(invoker, never()).invoke(any(), anyString());
    }

    @Test
    void ratesFireOnceIntervalHasPassed() {
        Schedule s = newSchedule("rate1", "rate(5 minutes)", "ENABLED");
        s.setCreationDate(Instant.parse("2026-04-21T09:00:00Z"));
        when(schedulerService.listAllSchedules()).thenReturn(List.of(s));

        dispatcher.tick(Instant.parse("2026-04-21T09:04:00Z"));
        verify(invoker, never()).invoke(any(), anyString());

        dispatcher.tick(Instant.parse("2026-04-21T09:06:00Z"));
        verify(invoker, times(1)).invoke(eq(s.getTarget()), anyString());

        dispatcher.tick(Instant.parse("2026-04-21T09:11:01Z"));
        verify(invoker, times(2)).invoke(eq(s.getTarget()), anyString());
    }

    @Test
    void unsupportedExpressionIsSkippedNotThrown() {
        Schedule s = newSchedule("weird", "every 5 minutes", "ENABLED");
        when(schedulerService.listAllSchedules()).thenReturn(List.of(s));

        assertDoesNotThrow(() -> dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z")));
        verify(invoker, never()).invoke(any(), anyString());
    }

    @Test
    void missingTargetIsSkipped() {
        Schedule s = newSchedule("at1", "at(2026-04-21T09:17:54)", "ENABLED");
        s.setTarget(null);
        when(schedulerService.listAllSchedules()).thenReturn(List.of(s));

        dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z"));

        verify(invoker, never()).invoke(any(), anyString());
    }

    @Test
    void successfulDeliveryIsNotRetriedOrDeadLettered() {
        Schedule s = newSchedule("delivered", "at(2026-04-21T09:17:54)", "ENABLED");
        s.getTarget().setDeadLetterConfig(new DeadLetterConfig(DLQ_TARGET_ARN));
        when(schedulerService.listAllSchedules()).thenReturn(List.of(s));

        tickEveryTenSeconds("2026-04-21T09:18:00Z", 3);

        verify(invoker, times(1)).invoke(s.getTarget(), "eu-central-1");
        verifyNoInteractions(sqsService);
    }

    @Test
    void disabledScheduleIsNeitherInvokedNorDeadLettered() {
        Schedule s = failingSchedule("disabled", "at(2026-04-21T09:17:54)", new RetryPolicy(3600, 0));
        s.setState("DISABLED");

        tickEveryTenSeconds("2026-04-21T09:18:00Z", 3);

        verify(invoker, never()).invoke(any(), anyString());
        verifyNoInteractions(sqsService);
    }

    @Test
    void retriesFailedInvocationUntilMaximumRetryAttempts() {
        Schedule s = newSchedule("retry", "at(2026-04-21T09:17:54)", "ENABLED");
        s.getTarget().setRetryPolicy(new RetryPolicy(3600, 1));
        when(schedulerService.listAllSchedules()).thenReturn(List.of(s));
        doThrow(new RuntimeException("target unavailable")).when(invoker)
                .invoke(eq(s.getTarget()), anyString());

        dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z"));
        dispatcher.tick(Instant.parse("2026-04-21T09:19:00Z"));
        dispatcher.tick(Instant.parse("2026-04-21T09:21:00Z"));

        verify(invoker, times(2)).invoke(eq(s.getTarget()), anyString());
    }

    @Test
    void doesNotRetryUntilTheCappedBackoffIsDue() {
        Schedule s = failingSchedule("backoff", "at(2026-04-21T09:17:54)", new RetryPolicy(3600, 2));

        dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z"));
        dispatcher.tick(Instant.parse("2026-04-21T09:18:59Z"));
        verify(invoker, times(1)).invoke(eq(s.getTarget()), anyString());

        dispatcher.tick(Instant.parse("2026-04-21T09:19:00Z"));
        verify(invoker, times(2)).invoke(eq(s.getTarget()), anyString());

        dispatcher.tick(Instant.parse("2026-04-21T09:20:59Z"));
        verify(invoker, times(2)).invoke(eq(s.getTarget()), anyString());
        dispatcher.tick(Instant.parse("2026-04-21T09:21:00Z"));
        verify(invoker, times(3)).invoke(eq(s.getTarget()), anyString());
    }

    @Test
    void appliesAwsDefaultRetryPolicyWhenTargetHasNone() {
        Schedule s = failingSchedule("no-policy", "at(2026-04-21T09:17:54)", null);

        dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z"));
        dispatcher.tick(Instant.parse("2026-04-21T09:19:00Z"));

        verify(invoker, times(2)).invoke(eq(s.getTarget()), anyString());
        verifyNoInteractions(sqsService);
    }

    @Test
    void missingMaximumEventAgeDefaultsToOneDay() {
        Schedule s = failingSchedule("default-age", "at(2026-04-21T09:17:54)", new RetryPolicy(null, 1));

        dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z"));
        dispatcher.tick(Instant.parse("2026-04-21T11:18:00Z"));

        verify(invoker, times(2)).invoke(eq(s.getTarget()), anyString());
        Map<String, MessageAttributeValue> attributes = deadLetters(1).get(0);
        assertEquals("MaximumRetryAttempts", attribute(attributes, "EXHAUSTED_RETRY_CONDITION"));
        assertEquals("1", attribute(attributes, "RETRY_ATTEMPTS"));
    }

    @Test
    void retriesStopAtMaximumEventAgeAfterFailedAttempts() {
        Schedule s = failingSchedule("max-age", "at(2026-04-21T09:17:54)", new RetryPolicy(60, null));

        dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z"));
        dispatcher.tick(Instant.parse("2026-04-21T09:19:00Z"));
        dispatcher.tick(Instant.parse("2026-04-21T09:21:00Z"));

        verify(invoker, times(1)).invoke(eq(s.getTarget()), anyString());
        Map<String, MessageAttributeValue> attributes = deadLetters(1).get(0);
        assertEquals("MaximumEventAgeInSeconds", attribute(attributes, "EXHAUSTED_RETRY_CONDITION"));
        assertEquals("0", attribute(attributes, "RETRY_ATTEMPTS"));
    }

    @Test
    void sendsExhaustedOccurrenceToDeadLetterQueueWithSchedulerAttributes() {
        Schedule s = failingSchedule("dead-letter", "at(2026-04-21T09:17:54)", new RetryPolicy(3600, 2));
        doThrow(new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                "The specified queue does not exist.", 400)).when(invoker).invoke(eq(s.getTarget()), anyString());

        dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z"));
        dispatcher.tick(Instant.parse("2026-04-21T09:19:00Z"));
        dispatcher.tick(Instant.parse("2026-04-21T09:21:00Z"));

        verify(invoker, times(3)).invoke(eq(s.getTarget()), anyString());
        Map<String, MessageAttributeValue> attributes = deadLetters(1).get(0);
        assertEquals(9, attributes.size());
        assertEquals("AWS.SimpleQueueService.NonExistentQueue", attribute(attributes, "ERROR_CODE"));
        assertEquals("The specified queue does not exist.", attribute(attributes, "ERROR_MESSAGE"));
        assertFalse(attribute(attributes, "EXECUTION_ID").isBlank());
        assertEquals("MaximumRetryAttempts", attribute(attributes, "EXHAUSTED_RETRY_CONDITION"));
        assertEquals("false", attribute(attributes, "IS_PAYLOAD_TRUNCATED"));
        assertEquals("2", attribute(attributes, "RETRY_ATTEMPTS"));
        assertEquals("2026-04-21T09:17:54Z", attribute(attributes, "SCHEDULED_TIME"));
        assertEquals(s.getArn(), attribute(attributes, "SCHEDULE_ARN"));
        assertEquals(SQS_TARGET_ARN, attribute(attributes, "TARGET_ARN"));
        verify(invoker, never()).invoke(argThat(target -> DLQ_TARGET_ARN.equals(target.getArn())), anyString());
    }

    @Test
    void skipsDeadLetterArnsThatAreNotStandardSqsQueues() {
        Schedule fifo = newSchedule("fifo-dlq", "at(2026-04-21T09:17:54)", "ENABLED");
        fifo.getTarget().setRetryPolicy(new RetryPolicy(3600, 0));
        fifo.getTarget().setDeadLetterConfig(new DeadLetterConfig(DLQ_TARGET_ARN + ".fifo"));
        Schedule topic = newSchedule("sns-dlq", "at(2026-04-21T09:17:54)", "ENABLED");
        topic.getTarget().setRetryPolicy(new RetryPolicy(3600, 0));
        topic.getTarget().setDeadLetterConfig(new DeadLetterConfig("arn:aws:sns:eu-central-1:000000000000:dead-letter"));
        when(schedulerService.listAllSchedules()).thenReturn(List.of(fifo, topic));
        doThrow(new RuntimeException("target unavailable")).when(invoker).invoke(any(), anyString());

        tickEveryTenSeconds("2026-04-21T09:18:00Z", 2);

        verify(invoker, times(2)).invoke(any(), anyString());
        verifyNoInteractions(sqsService);
    }

    @Test
    void failingRateOccurrenceDoesNotBlockNextOccurrence() {
        Schedule s = failingSchedule("rate-retry", "rate(5 minutes)", new RetryPolicy(3600, 1));

        dispatcher.tick(Instant.parse("2026-04-21T09:05:00Z"));
        dispatcher.tick(Instant.parse("2026-04-21T09:10:00Z"));
        dispatcher.tick(Instant.parse("2026-04-21T09:12:00Z"));

        verify(invoker, times(4)).invoke(eq(s.getTarget()), anyString());
        List<Map<String, MessageAttributeValue>> deadLetters = deadLetters(2);
        assertEquals("2026-04-21T09:05:00Z", attribute(deadLetters.get(0), "SCHEDULED_TIME"));
        assertEquals("2026-04-21T09:10:00Z", attribute(deadLetters.get(1), "SCHEDULED_TIME"));
        assertNotEquals(attribute(deadLetters.get(0), "EXECUTION_ID"), attribute(deadLetters.get(1), "EXECUTION_ID"));
    }

    @Test
    void deletesAtScheduleOnlyAfterRetriedDeliverySucceeds() {
        Schedule s = failingSchedule("at-delete", "at(2026-04-21T09:17:54)", new RetryPolicy(3600, 1));
        s.setActionAfterCompletion("DELETE");
        s.setAccountId("000000000000");
        when(invoker.invoke(eq(s.getTarget()), anyString()))
                .thenThrow(new RuntimeException("target unavailable"))
                .thenReturn(TARGET_REQUEST);

        dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z"));
        verify(schedulerService, never()).deleteScheduleForAccount(anyString(), anyString(), anyString(), anyString());

        dispatcher.tick(Instant.parse("2026-04-21T09:19:00Z"));
        verify(schedulerService, times(1)).deleteScheduleForAccount("000000000000", "at-delete", "default", "eu-central-1");
        verifyNoInteractions(sqsService);
    }

    @Test
    void deletesAtScheduleAfterOccurrenceIsExhausted() {
        Schedule s = failingSchedule("at-exhausted", "at(2026-04-21T09:17:54)", new RetryPolicy(3600, 1));
        s.setActionAfterCompletion("DELETE");
        s.setAccountId("000000000000");

        dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z"));
        verify(schedulerService, never()).deleteScheduleForAccount(anyString(), anyString(), anyString(), anyString());

        dispatcher.tick(Instant.parse("2026-04-21T09:19:00Z"));
        verify(schedulerService, times(1)).deleteScheduleForAccount("000000000000", "at-exhausted", "default", "eu-central-1");
        deadLetters(1);
    }

    @Test
    void disablingScheduleMidRetryDropsPendingRetry() {
        Schedule s = failingSchedule("disable-mid-retry", "at(2026-04-21T09:17:54)", new RetryPolicy(3600, 3));

        dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z"));
        s.setState("DISABLED");
        dispatcher.tick(Instant.parse("2026-04-21T09:18:10Z"));
        s.setState("ENABLED");
        dispatcher.tick(Instant.parse("2026-04-21T09:18:20Z"));

        verify(invoker, times(1)).invoke(eq(s.getTarget()), anyString());
        verifyNoInteractions(sqsService);
    }

    @Test
    void deletingScheduleMidRetryDropsPendingRetry() {
        Schedule s = failingSchedule("delete-mid-retry", "at(2026-04-21T09:17:54)", new RetryPolicy(3600, 3));
        when(schedulerService.listAllSchedules()).thenReturn(List.of(s), List.of(), List.of(s));

        tickEveryTenSeconds("2026-04-21T09:18:00Z", 3);

        verify(invoker, times(1)).invoke(eq(s.getTarget()), anyString());
        verifyNoInteractions(sqsService);
    }

    @Test
    void updatingScheduleMidRetryDropsPendingRetry() {
        Schedule s = failingSchedule("update-mid-retry", "at(2026-04-21T09:17:54)", new RetryPolicy(3600, 3));
        s.setLastModificationDate(Instant.parse("2026-04-21T09:00:00Z"));

        dispatcher.tick(Instant.parse("2026-04-21T09:18:00Z"));
        s.setLastModificationDate(Instant.parse("2026-04-21T09:18:05Z"));
        dispatcher.tick(Instant.parse("2026-04-21T09:18:10Z"));

        verify(invoker, times(1)).invoke(eq(s.getTarget()), anyString());
        verifyNoInteractions(sqsService);
    }
}
