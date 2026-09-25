package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.eventbridge.model.Rule;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the race between an in-flight cron tick and a concurrent DeleteRule/DisableRule:
 * the tick's self-rescheduling must not resurrect a timer for a rule that stopped
 * scheduling (deleted or disabled) while target delivery was still in progress.
 * The timer is driven by hand through a mocked Vertx, so no real time passes.
 */
class RuleSchedulerTest {

    private static final String ACCOUNT = "000000000000";
    private static final String RULE_ARN = "arn:aws:events:us-east-1:000000000000:rule/default/timer-stop-rule";
    private static final String EVERY_MINUTE_CRON = "cron(0/1 * * * ? *)";
    private static final Instant JUST_BEFORE_MINUTE_BOUNDARY = Instant.parse("2026-06-15T12:00:59.000Z");

    @Test
    void deletedRuleDoesNotResurrectCronTimerDuringInFlightTick() {
        Vertx vertx = mock(Vertx.class);
        ArgumentCaptor<Handler<Long>> fire = timerHandlerCaptor();
        when(vertx.setTimer(anyLong(), fire.capture())).thenReturn(1L, 2L);

        AtomicReference<Rule> currentRule = new AtomicReference<>(enabledRule());
        AtomicReference<RuleScheduler> schedulerRef = new AtomicReference<>();
        // AWS DeleteRule lands mid-delivery. EventBridgeService.deleteRule always calls
        // RuleScheduler.stopScheduler before removing the rule; mirror that ordering here.
        StoppingInvoker invoker = new StoppingInvoker(() -> {
            schedulerRef.get().stopScheduler(RULE_ARN);
            currentRule.set(null);
        });
        RuleScheduler scheduler = newScheduler(vertx, invoker);
        schedulerRef.set(scheduler);
        scheduler.startScheduler(RULE_ARN, EVERY_MINUTE_CRON, () -> toScheduleData(currentRule.get()));

        fire.getValue().handle(1L);

        assertEquals(1, invoker.invocationCount());
        verify(vertx, times(1)).setTimer(anyLong(), any());
        assertFalse(scheduler.isRunning(RULE_ARN), "deleted rule must not have its cron timer re-armed");
    }

    @Test
    void disabledRuleDoesNotResurrectCronTimerDuringInFlightTick() {
        Vertx vertx = mock(Vertx.class);
        ArgumentCaptor<Handler<Long>> fire = timerHandlerCaptor();
        when(vertx.setTimer(anyLong(), fire.capture())).thenReturn(1L, 2L);

        Rule rule = enabledRule();
        AtomicReference<RuleScheduler> schedulerRef = new AtomicReference<>();
        // AWS DisableRule lands mid-delivery: the rule stays but flips to DISABLED.
        // EventBridgeService.disableRule always calls RuleScheduler.stopScheduler.
        StoppingInvoker invoker = new StoppingInvoker(() -> {
            rule.setState(RuleState.DISABLED);
            schedulerRef.get().stopScheduler(RULE_ARN);
        });
        RuleScheduler scheduler = newScheduler(vertx, invoker);
        schedulerRef.set(scheduler);
        scheduler.startScheduler(RULE_ARN, EVERY_MINUTE_CRON, () -> toScheduleData(rule));

        fire.getValue().handle(1L);

        assertEquals(1, invoker.invocationCount());
        verify(vertx, times(1)).setTimer(anyLong(), any());
        assertFalse(scheduler.isRunning(RULE_ARN), "disabled rule must not have its cron timer re-armed");
    }

    @Test
    void stopBetweenReArmCheckAndReplaceCancelsTheNewTimer() {
        Vertx vertx = mock(Vertx.class);
        ArgumentCaptor<Handler<Long>> fire = timerHandlerCaptor();
        AtomicReference<RuleScheduler> schedulerRef = new AtomicReference<>();
        // The re-arm's own setTimer call is the last step before the context swap, so a stop
        // issued from inside it lands after the "still my timer" check and before the replace.
        when(vertx.setTimer(anyLong(), fire.capture())).thenReturn(1L).thenAnswer(invocation -> {
            schedulerRef.get().stopScheduler(RULE_ARN);
            return 2L;
        });

        Rule rule = enabledRule();
        StoppingInvoker invoker = new StoppingInvoker(() -> { });
        RuleScheduler scheduler = newScheduler(vertx, invoker);
        schedulerRef.set(scheduler);
        scheduler.startScheduler(RULE_ARN, EVERY_MINUTE_CRON, () -> toScheduleData(rule));

        fire.getValue().handle(1L);

        verify(vertx).cancelTimer(2L);
        assertFalse(scheduler.isRunning(RULE_ARN), "a stop racing the re-arm must not leave the new timer recorded");
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Handler<Long>> timerHandlerCaptor() {
        return ArgumentCaptor.forClass(Handler.class);
    }

    private static RuleScheduler.ScheduleData toScheduleData(Rule rule) {
        if (rule == null) {
            return null;
        }
        return new RuleScheduler.ScheduleData(rule, List.of(target()));
    }

    private static Rule enabledRule() {
        Rule rule = new Rule();
        rule.setName("timer-stop-rule");
        rule.setArn(RULE_ARN);
        rule.setAccountId(ACCOUNT);
        rule.setEventBusName("default");
        rule.setScheduleExpression(EVERY_MINUTE_CRON);
        rule.setState(RuleState.ENABLED);
        return rule;
    }

    private static Target target() {
        return new Target("target-1", "arn:aws:lambda:us-east-1:000000000000:function:test-fn", null, null);
    }

    private static RuleScheduler newScheduler(Vertx vertx, EventBridgeInvoker invoker) {
        return new RuleScheduler(vertx, testConfig(), new ObjectMapper(), invoker,
                Clock.fixed(JUST_BEFORE_MINUTE_BOUNDARY, ZoneOffset.UTC));
    }

    /**
     * Runs a stop action from inside target delivery, putting a DeleteRule/DisableRule
     * exactly while the tick is still delivering.
     */
    private static final class StoppingInvoker extends EventBridgeInvoker {

        private final Runnable onDelivery;
        private final AtomicInteger invocationCount = new AtomicInteger();

        StoppingInvoker(Runnable onDelivery) {
            super(null, null, null, new ObjectMapper(), testConfig());
            this.onDelivery = onDelivery;
        }

        @Override
        public void invokeTarget(Target target, String eventJson, String region) {
            invocationCount.incrementAndGet();
            onDelivery.run();
        }

        int invocationCount() {
            return invocationCount.get();
        }
    }

    private static EmulatorConfig testConfig() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        return config;
    }
}
