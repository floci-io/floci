package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.eventbridge.model.Rule;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the race between an in-flight cron tick and a concurrent DeleteRule/DisableRule:
 * the tick's self-rescheduling must not resurrect a timer for a rule that stopped
 * scheduling (deleted or disabled) while target delivery was still in progress.
 */
class RuleSchedulerTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String RULE_ARN = "arn:aws:events:us-east-1:000000000000:rule/default/timer-stop-rule";
    private static final String EVERY_MINUTE_CRON = "cron(0/1 * * * ? *)";

    // Parked one second before a minute boundary so ScheduleExpressionParser.millisUntilNextFire
    // computes its documented 1-second floor, keeping the cron fire fast and deterministic
    // instead of depending on where real wall-clock time happens to sit in the current minute.
    private static final Instant JUST_BEFORE_MINUTE_BOUNDARY = Instant.parse("2026-06-15T12:00:59.000Z");

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    @Test
    void deletedRuleDoesNotResurrectCronTimerDuringInFlightTick() throws InterruptedException {
        CountDownLatch invocationStarted = new CountDownLatch(1);
        CountDownLatch releaseInvocation = new CountDownLatch(1);
        BlockingInvoker invoker = new BlockingInvoker(invocationStarted, releaseInvocation);
        RuleScheduler scheduler = newScheduler(invoker);

        AtomicReference<Rule> currentRule = new AtomicReference<>(enabledRule());
        scheduler.startScheduler(RULE_ARN, EVERY_MINUTE_CRON, () -> toScheduleData(currentRule.get()));

        assertTrue(invocationStarted.await(10, TimeUnit.SECONDS),
                "expected the cron timer to invoke the blocked target");

        // AWS DeleteRule: the rule is gone. EventBridgeService.deleteRule always calls
        // RuleScheduler.stopScheduler before removing the rule; mirror that ordering here.
        currentRule.set(null);
        scheduler.stopScheduler(RULE_ARN);

        releaseInvocation.countDown();

        // The clock sits one second before a cron fire, so a re-armed timer would deliver again
        // within about a second. Hold the check past that window.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertFalse(scheduler.isRunning(RULE_ARN), "deleted rule must not have its cron timer re-armed");
            assertEquals(1, invoker.invocationCount(), "target must not be invoked again after deletion");
        });
    }

    @Test
    void disabledRuleDoesNotResurrectCronTimerDuringInFlightTick() throws InterruptedException {
        CountDownLatch invocationStarted = new CountDownLatch(1);
        CountDownLatch releaseInvocation = new CountDownLatch(1);
        BlockingInvoker invoker = new BlockingInvoker(invocationStarted, releaseInvocation);
        RuleScheduler scheduler = newScheduler(invoker);

        AtomicReference<Rule> currentRule = new AtomicReference<>(enabledRule());
        scheduler.startScheduler(RULE_ARN, EVERY_MINUTE_CRON, () -> toScheduleData(currentRule.get()));

        assertTrue(invocationStarted.await(10, TimeUnit.SECONDS),
                "expected the cron timer to invoke the blocked target");

        // AWS DisableRule: the rule stays but flips to DISABLED. EventBridgeService.disableRule
        // always calls RuleScheduler.stopScheduler; mirror that ordering here.
        currentRule.get().setState(RuleState.DISABLED);
        scheduler.stopScheduler(RULE_ARN);

        releaseInvocation.countDown();

        // The clock sits one second before a cron fire, so a re-armed timer would deliver again
        // within about a second. Hold the check past that window.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertFalse(scheduler.isRunning(RULE_ARN), "disabled rule must not have its cron timer re-armed");
            assertEquals(1, invoker.invocationCount(), "target must not be invoked again after disabling");
        });
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

    private RuleScheduler newScheduler(EventBridgeInvoker invoker) {
        return new RuleScheduler(vertx, testConfig(), new ObjectMapper(), invoker,
                Clock.fixed(JUST_BEFORE_MINUTE_BOUNDARY, ZoneOffset.UTC));
    }

    /**
     * Blocks inside invokeTarget until released, simulating a target delivery that is
     * still in progress when a concurrent DeleteRule/DisableRule runs.
     */
    private static final class BlockingInvoker extends EventBridgeInvoker {

        private final CountDownLatch invocationStarted;
        private final CountDownLatch releaseInvocation;
        private final AtomicInteger invocationCount = new AtomicInteger();

        BlockingInvoker(CountDownLatch invocationStarted, CountDownLatch releaseInvocation) {
            super(null, null, null, new ObjectMapper(), testConfig());
            this.invocationStarted = invocationStarted;
            this.releaseInvocation = releaseInvocation;
        }

        @Override
        public void invokeTarget(Target target, String eventJson, String region) {
            invocationCount.incrementAndGet();
            invocationStarted.countDown();
            try {
                if (!releaseInvocation.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test target delivery was not released in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
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
