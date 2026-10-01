package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TargetDispatcherTest {

    private static final Instant T0 = Instant.parse("2026-06-15T12:00:00Z");
    private static final String BASE_URL = "http://localhost:4566";
    private static final String REGION = "us-east-1";
    private static final String RULE_ARN = "arn:aws:events:us-east-1:000000000000:rule/orders";
    private static final String TARGET_ARN = "arn:aws:sqs:us-east-1:000000000000:orders";
    private static final String TARGET_URL = BASE_URL + "/000000000000/orders";
    private static final String DLQ_ARN = "arn:aws:sqs:us-east-1:000000000000:orders-dlq";
    private static final String DLQ_URL = BASE_URL + "/000000000000/orders-dlq";
    private static final String EVENT = "{\"id\":\"e-1\",\"source\":\"orders\",\"detail\":{\"orderId\":\"o-1\"}}";

    private LambdaService lambdaService;
    private SqsService sqsService;
    private AtomicReference<Instant> clockNow;
    private EventBridgeInvoker invoker;
    private Clock clock;
    private TargetDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        lambdaService = mock(LambdaService.class);
        sqsService = mock(SqsService.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.baseUrl()).thenReturn(BASE_URL);
        invoker = new EventBridgeInvoker(
                lambdaService, sqsService, mock(SnsService.class), new ObjectMapper(), config);
        clockNow = new AtomicReference<>(T0);
        clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> clockNow.get());
        dispatcher = new TargetDispatcher(invoker, sqsService, BASE_URL, clock, null);
    }

    @Test
    void transientRefusalIsRetriedAfterBackoffUntilAccepted() {
        when(sqsService.sendMessage(TARGET_URL, EVENT, 0, null, null, REGION))
                .thenThrow(unavailable())
                .thenReturn(null);
        Target target = sqsTarget(null, DLQ_ARN);

        dispatcher.dispatch(RULE_ARN, target, EVENT, REGION, () -> List.of(target));
        verify(sqsService, times(1)).sendMessage(TARGET_URL, EVENT, 0, null, null, REGION);

        tickAt(T0.plusMillis(999));
        verify(sqsService, times(1)).sendMessage(TARGET_URL, EVENT, 0, null, null, REGION);

        tickAt(T0.plusSeconds(1));
        verify(sqsService, times(2)).sendMessage(TARGET_URL, EVENT, 0, null, null, REGION);

        tickAt(T0.plusSeconds(3600));
        verify(sqsService, times(2)).sendMessage(TARGET_URL, EVENT, 0, null, null, REGION);
        verifyNoDeadLetter();
    }

    @Test
    void queuedRetryWakesItselfUpOnTheExecutor() {
        when(sqsService.sendMessage(TARGET_URL, EVENT, 0, null, null, REGION))
                .thenThrow(unavailable())
                .thenReturn(null);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.baseUrl()).thenReturn(BASE_URL);
        EventBridgeInvoker invoker = new EventBridgeInvoker(
                lambdaService, sqsService, mock(SnsService.class), new ObjectMapper(), config);
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        TargetDispatcher scheduled = new TargetDispatcher(invoker, sqsService, BASE_URL, Clock.systemUTC(), executor);
        Target target = sqsTarget(null, DLQ_ARN);
        try {
            scheduled.dispatch(RULE_ARN, target, EVENT, REGION, () -> List.of(target));

            verify(sqsService, timeout(5000).times(2)).sendMessage(TARGET_URL, EVENT, 0, null, null, REGION);
        } finally {
            executor.shutdownNow();
        }
        verifyNoDeadLetter();
    }

    @Test
    void retriesRunFromOnePeriodicTickRatherThanATimerEach() {
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        TargetDispatcher scheduled = new TargetDispatcher(invoker, sqsService, BASE_URL, clock, executor);
        when(sqsService.sendMessage(TARGET_URL, EVENT, 0, null, null, REGION)).thenThrow(unavailable());
        Target target = sqsTarget(null, DLQ_ARN);

        scheduled.dispatch(RULE_ARN, target, EVENT, REGION, () -> List.of(target));
        scheduled.dispatch(RULE_ARN, target, EVENT, REGION, () -> List.of(target));

        verify(executor).scheduleWithFixedDelay(any(Runnable.class), eq(1L), eq(1L), eq(TimeUnit.SECONDS));
        verifyNoMoreInteractions(executor);
    }

    @Test
    void exhaustedRetryAttemptsSendTheOriginalEventToTheDeadLetterQueue() {
        when(sqsService.sendMessage(TARGET_URL, EVENT, 0, null, null, REGION)).thenThrow(unavailable());
        Target target = sqsTarget(new Target.RetryPolicy(2, 3600), DLQ_ARN);

        dispatcher.dispatch(RULE_ARN, target, EVENT, REGION, () -> List.of(target));
        tickAt(T0.plusSeconds(10));
        tickAt(T0.plusSeconds(20));
        tickAt(T0.plusSeconds(600));

        verify(sqsService, times(3)).sendMessage(TARGET_URL, EVENT, 0, null, null, REGION);
        Map<String, MessageAttributeValue> attributes = deadLetterAttributes();
        assertAttribute(attributes, "RULE_ARN", RULE_ARN);
        assertAttribute(attributes, "TARGET_ARN", TARGET_ARN);
        assertAttribute(attributes, "ERROR_CODE", "ERROR_FROM_TARGET");
        assertAttribute(attributes, "ERROR_MESSAGE", "Service is unavailable.");
        assertAttribute(attributes, "EXHAUSTED_RETRY_CONDITION", "MaximumRetryAttempts");
        assertAttribute(attributes, "RETRY_ATTEMPTS", "2");
    }

    @Test
    void expiredEventAgeSendsToTheDeadLetterQueueWithoutAnotherAttempt() {
        when(sqsService.sendMessage(TARGET_URL, EVENT, 0, null, null, REGION)).thenThrow(unavailable());
        Target target = sqsTarget(new Target.RetryPolicy(185, 60), DLQ_ARN);

        dispatcher.dispatch(RULE_ARN, target, EVENT, REGION, () -> List.of(target));
        tickAt(T0.plusSeconds(61));

        verify(sqsService, times(1)).sendMessage(TARGET_URL, EVENT, 0, null, null, REGION);
        Map<String, MessageAttributeValue> attributes = deadLetterAttributes();
        assertAttribute(attributes, "EXHAUSTED_RETRY_CONDITION", "MaximumEventAgeInSeconds");
        assertAttribute(attributes, "RETRY_ATTEMPTS", "0");
    }

    @Test
    void nonRetryableRefusalIsDeadLetteredImmediately() {
        when(sqsService.sendMessage(TARGET_URL, EVENT, 0, null, null, REGION))
                .thenThrow(new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                        "The specified queue does not exist.", 400));
        Target target = sqsTarget(null, DLQ_ARN);

        dispatcher.dispatch(RULE_ARN, target, EVENT, REGION, () -> List.of(target));

        Map<String, MessageAttributeValue> attributes = deadLetterAttributes();
        assertAttribute(attributes, "ERROR_CODE", "NO_RESOURCE");
        assertAttribute(attributes, "ERROR_MESSAGE", "The specified queue does not exist.");
        assertAttribute(attributes, "RETRY_ATTEMPTS", "0");
        assertFalse(attributes.containsKey("EXHAUSTED_RETRY_CONDITION"));

        tickAt(T0.plusSeconds(1));
        tickAt(T0.plusSeconds(3600));
        verify(sqsService, times(1)).sendMessage(TARGET_URL, EVENT, 0, null, null, REGION);
    }

    @Test
    void failedDeadLetterDeliveryDoesNotEscapeOrRetry() {
        when(sqsService.sendMessage(TARGET_URL, EVENT, 0, null, null, REGION))
                .thenThrow(new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                        "The specified queue does not exist.", 400));
        when(sqsService.sendMessage(eq(DLQ_URL), anyString(), anyInt(), any(), any(), anyMap(), anyString()))
                .thenThrow(new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                        "The specified queue does not exist.", 400));
        Target target = sqsTarget(null, DLQ_ARN);

        assertDoesNotThrow(() -> dispatcher.dispatch(RULE_ARN, target, EVENT, REGION, () -> List.of(target)));
        tickAt(T0.plusSeconds(3600));

        verify(sqsService, times(1)).sendMessage(TARGET_URL, EVENT, 0, null, null, REGION);
        verify(sqsService, times(1)).sendMessage(eq(DLQ_URL), anyString(), anyInt(), any(), any(), anyMap(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws:sqs:us-east-1:000000000000:orders-dlq.fifo",
            "arn:aws:sns:us-east-1:000000000000:orders-dlq",
            "arn:aws:sqs:eu-west-1:000000000000:orders-dlq",
            "   "
    })
    void unsupportedDeadLetterQueueIsSkipped(String deadLetterArn) {
        when(sqsService.sendMessage(TARGET_URL, EVENT, 0, null, null, REGION))
                .thenThrow(new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                        "The specified queue does not exist.", 400));
        Target target = sqsTarget(null, deadLetterArn);

        dispatcher.dispatch(RULE_ARN, target, EVENT, REGION, () -> List.of(target));

        verifyNoDeadLetter();
    }

    @Test
    void retryUsesTheCurrentTargetDefinition() {
        when(sqsService.sendMessage(TARGET_URL, EVENT, 0, null, null, REGION)).thenThrow(unavailable());
        Target original = sqsTarget(null, null);
        Target updated = new Target("orders-target", "arn:aws:sqs:us-east-1:000000000000:orders-v2", null, null);
        AtomicReference<List<Target>> current = new AtomicReference<>(List.of(original));

        dispatcher.dispatch(RULE_ARN, original, EVENT, REGION, current::get);
        current.set(List.of(updated));
        tickAt(T0.plusSeconds(1));

        verify(sqsService, times(1)).sendMessage(TARGET_URL, EVENT, 0, null, null, REGION);
        verify(sqsService).sendMessage(BASE_URL + "/000000000000/orders-v2", EVENT, 0, null, null, REGION);
    }

    @Test
    void loweredRetryLimitDeadLettersAQueuedRetryWithoutAnotherAttempt() {
        when(sqsService.sendMessage(TARGET_URL, EVENT, 0, null, null, REGION)).thenThrow(unavailable());
        Target original = sqsTarget(new Target.RetryPolicy(2, 3600), DLQ_ARN);
        AtomicReference<List<Target>> current = new AtomicReference<>(List.of(original));

        dispatcher.dispatch(RULE_ARN, original, EVENT, REGION, current::get);
        current.set(List.of(sqsTarget(new Target.RetryPolicy(0, 3600), DLQ_ARN)));
        tickAt(T0.plusSeconds(1));

        verify(sqsService, times(1)).sendMessage(TARGET_URL, EVENT, 0, null, null, REGION);
        Map<String, MessageAttributeValue> attributes = deadLetterAttributes();
        assertAttribute(attributes, "EXHAUSTED_RETRY_CONDITION", "MaximumRetryAttempts");
        assertAttribute(attributes, "RETRY_ATTEMPTS", "0");
    }

    @Test
    void slowRetryDoesNotLetALaterDueRetryOutliveItsEventAge() {
        String slowUrl = BASE_URL + "/000000000000/slow";
        Target slow = new Target("slow-target", "arn:aws:sqs:us-east-1:000000000000:slow", null, null);
        Target aged = sqsTarget(new Target.RetryPolicy(185, 60), DLQ_ARN);
        when(sqsService.sendMessage(slowUrl, EVENT, 0, null, null, REGION))
                .thenThrow(unavailable())
                .thenAnswer(invocation -> {
                    clockNow.set(T0.plusSeconds(100));
                    return null;
                });
        when(sqsService.sendMessage(TARGET_URL, EVENT, 0, null, null, REGION)).thenThrow(unavailable());

        dispatcher.dispatch(RULE_ARN, slow, EVENT, REGION, () -> List.of(slow, aged));
        clockNow.set(T0.plusMillis(500));
        dispatcher.dispatch(RULE_ARN, aged, EVENT, REGION, () -> List.of(slow, aged));
        tickAt(T0.plusSeconds(2));

        verify(sqsService, times(2)).sendMessage(slowUrl, EVENT, 0, null, null, REGION);
        verify(sqsService, times(1)).sendMessage(TARGET_URL, EVENT, 0, null, null, REGION);
        assertAttribute(deadLetterAttributes(), "EXHAUSTED_RETRY_CONDITION", "MaximumEventAgeInSeconds");
    }

    @Test
    void clearDuringATickStopsTheRemainingRetries() {
        String firstUrl = BASE_URL + "/000000000000/first";
        Target first = new Target("first-target", "arn:aws:sqs:us-east-1:000000000000:first", null, null);
        Target second = sqsTarget(null, DLQ_ARN);
        when(sqsService.sendMessage(firstUrl, EVENT, 0, null, null, REGION))
                .thenThrow(unavailable())
                .thenAnswer(invocation -> {
                    dispatcher.clear();
                    return null;
                });
        when(sqsService.sendMessage(TARGET_URL, EVENT, 0, null, null, REGION)).thenThrow(unavailable());

        dispatcher.dispatch(RULE_ARN, first, EVENT, REGION, () -> List.of(first, second));
        clockNow.set(T0.plusMillis(500));
        dispatcher.dispatch(RULE_ARN, second, EVENT, REGION, () -> List.of(first, second));
        tickAt(T0.plusSeconds(2));

        verify(sqsService, times(2)).sendMessage(firstUrl, EVENT, 0, null, null, REGION);
        verify(sqsService, times(1)).sendMessage(TARGET_URL, EVENT, 0, null, null, REGION);
        verifyNoDeadLetter();
    }

    @Test
    void removedTargetsPendingRetriesAreDroppedEvenIfItsIdReturns() {
        String otherUrl = BASE_URL + "/000000000000/other";
        Target removed = sqsTarget(null, DLQ_ARN);
        Target kept = new Target("other-target", "arn:aws:sqs:us-east-1:000000000000:other", null, null);
        when(sqsService.sendMessage(TARGET_URL, EVENT, 0, null, null, REGION)).thenThrow(unavailable());
        when(sqsService.sendMessage(otherUrl, EVENT, 0, null, null, REGION)).thenThrow(unavailable()).thenReturn(null);

        dispatcher.dispatch(RULE_ARN, removed, EVENT, REGION, () -> List.of(removed, kept));
        dispatcher.dispatch(RULE_ARN, kept, EVENT, REGION, () -> List.of(removed, kept));
        dispatcher.dropPendingRetries(RULE_ARN, List.of("orders-target"));
        tickAt(T0.plusSeconds(1));

        verify(sqsService, times(1)).sendMessage(TARGET_URL, EVENT, 0, null, null, REGION);
        verify(sqsService, times(2)).sendMessage(otherUrl, EVENT, 0, null, null, REGION);
        verifyNoDeadLetter();
    }

    @Test
    void retryIsDroppedWhenTheTargetWasRemoved() {
        when(sqsService.sendMessage(TARGET_URL, EVENT, 0, null, null, REGION)).thenThrow(unavailable());
        Target target = sqsTarget(null, DLQ_ARN);
        AtomicReference<List<Target>> current = new AtomicReference<>(List.of(target));

        dispatcher.dispatch(RULE_ARN, target, EVENT, REGION, current::get);
        current.set(List.of());
        tickAt(T0.plusSeconds(1));
        tickAt(T0.plusSeconds(3600));

        verify(sqsService, times(1)).sendMessage(TARGET_URL, EVENT, 0, null, null, REGION);
        verifyNoDeadLetter();
    }

    @Test
    void retriesStayWithinTheirOwnAccountAndRegion() {
        String ruleA = "arn:aws:events:us-east-1:111111111111:rule/orders";
        String ruleB = "arn:aws:events:eu-west-1:222222222222:rule/orders";
        String urlA = BASE_URL + "/111111111111/orders";
        String urlB = BASE_URL + "/222222222222/orders";
        Target targetA = new Target("orders-target", "arn:aws:sqs:us-east-1:111111111111:orders", null, null);
        Target targetB = new Target("orders-target", "arn:aws:sqs:eu-west-1:222222222222:orders", null, null);
        when(sqsService.sendMessage(urlA, EVENT, 0, null, null, "us-east-1"))
                .thenThrow(unavailable())
                .thenReturn(null);
        when(sqsService.sendMessage(urlB, EVENT, 0, null, null, "eu-west-1"))
                .thenThrow(unavailable())
                .thenReturn(null);

        dispatcher.dispatch(ruleA, targetA, EVENT, "us-east-1", () -> List.of(targetA));
        dispatcher.dispatch(ruleB, targetB, EVENT, "eu-west-1", () -> List.of(targetB));
        tickAt(T0.plusSeconds(1));

        verify(sqsService, times(2)).sendMessage(urlA, EVENT, 0, null, null, "us-east-1");
        verify(sqsService, times(2)).sendMessage(urlB, EVENT, 0, null, null, "eu-west-1");
        verify(sqsService, never()).sendMessage(urlA, EVENT, 0, null, null, "eu-west-1");
        verify(sqsService, never()).sendMessage(urlB, EVENT, 0, null, null, "us-east-1");
    }

    @Test
    void acceptedLambdaInvocationIsNotRetried() {
        String functionArn = "arn:aws:lambda:us-east-1:000000000000:function:f";
        Target target = new Target("fn-target", functionArn, null, null);

        dispatcher.dispatch(RULE_ARN, target, EVENT, REGION, () -> List.of(target));
        tickAt(T0.plusSeconds(1));
        tickAt(T0.plusSeconds(3600));

        verify(lambdaService, times(1)).invokeArn(eq(functionArn), any(), eq(InvocationType.Event));
    }

    @Test
    void clearDropsPendingRetries() {
        when(sqsService.sendMessage(TARGET_URL, EVENT, 0, null, null, REGION)).thenThrow(unavailable());
        Target target = sqsTarget(null, DLQ_ARN);

        dispatcher.dispatch(RULE_ARN, target, EVENT, REGION, () -> List.of(target));
        dispatcher.clear();
        tickAt(T0.plusSeconds(3600));

        verify(sqsService, times(1)).sendMessage(TARGET_URL, EVENT, 0, null, null, REGION);
        verifyNoDeadLetter();
    }

    @Test
    void attemptFailingAfterAResetIsNeitherRetriedNorDeadLettered() {
        when(sqsService.sendMessage(TARGET_URL, EVENT, 0, null, null, REGION)).thenAnswer(invocation -> {
            dispatcher.clear();
            throw unavailable();
        });
        Target target = sqsTarget(null, DLQ_ARN);

        dispatcher.dispatch(RULE_ARN, target, EVENT, REGION, () -> List.of(target));
        tickAt(T0.plusSeconds(3600));

        verify(sqsService, times(1)).sendMessage(TARGET_URL, EVENT, 0, null, null, REGION);
        verifyNoDeadLetter();
    }

    private void tickAt(Instant now) {
        clockNow.set(now);
        dispatcher.tick();
    }

    private static Target sqsTarget(Target.RetryPolicy retryPolicy, String deadLetterArn) {
        Target target = new Target("orders-target", TARGET_ARN, null, null);
        target.setRetryPolicy(retryPolicy);
        if (deadLetterArn != null) {
            target.setDeadLetterConfig(new Target.DeadLetterConfig(deadLetterArn));
        }
        return target;
    }

    private static AwsException unavailable() {
        return new AwsException("ServiceUnavailable", "Service is unavailable.", 503);
    }

    @SuppressWarnings("unchecked")
    private Map<String, MessageAttributeValue> deadLetterAttributes() {
        ArgumentCaptor<Map<String, MessageAttributeValue>> attributes = ArgumentCaptor.forClass(Map.class);
        verify(sqsService, times(1)).sendMessage(
                eq(DLQ_URL), eq(EVENT), eq(0), isNull(), isNull(), attributes.capture(), eq(REGION));
        return attributes.getValue();
    }

    private void verifyNoDeadLetter() {
        verify(sqsService, never()).sendMessage(anyString(), anyString(), anyInt(), any(), any(), anyMap(), anyString());
    }

    private static void assertAttribute(Map<String, MessageAttributeValue> attributes, String name, String value) {
        MessageAttributeValue attribute = attributes.get(name);
        assertNotNull(attribute, name);
        assertEquals(value, attribute.getStringValue(), name);
        assertEquals("String", attribute.getDataType(), name);
    }
}
