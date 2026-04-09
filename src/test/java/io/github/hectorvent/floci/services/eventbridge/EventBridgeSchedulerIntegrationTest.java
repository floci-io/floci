package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.eventbridge.model.EventBus;
import io.github.hectorvent.floci.services.eventbridge.model.Rule;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EventBridgeSchedulerIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private Vertx vertx;
    private EventBridgeService eventBridgeService;
    private RuleScheduler scheduler;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        StorageBackend<String, EventBus> busStore = new InMemoryStorage<>();
        StorageBackend<String, Rule> ruleStore = new InMemoryStorage<>();
        StorageBackend<String, List<Target>> targetStore = new InMemoryStorage<>();

        EventBridgeInvoker invoker = new EventBridgeInvoker(null, null, null, new ObjectMapper(), createConfig());
        scheduler = new RuleScheduler(vertx, createConfig(), new ObjectMapper(), invoker);

        eventBridgeService = new EventBridgeService(
                busStore, ruleStore, targetStore,
                new RegionResolver(REGION, ACCOUNT),
                new ObjectMapper(), scheduler, invoker);
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    @Nested
    @DisplayName("Rate expression lifecycle")
    class RateLifecycle {

        @Test
        void putRuleWithScheduleStartsScheduler() {
            eventBridgeService.getOrCreateDefaultBus(REGION);
            Rule rule = eventBridgeService.putRule(
                    "test-rule", "default", null, "rate(1 minute)",
                    RuleState.ENABLED, null, null, null, REGION);

            assertTrue(scheduler.isRunning(rule.getArn()));
        }

        @Test
        void deleteRuleStopsScheduler() {
            eventBridgeService.getOrCreateDefaultBus(REGION);
            Rule rule = eventBridgeService.putRule(
                    "test-rule", "default", null, "rate(1 minute)",
                    RuleState.ENABLED, null, null, null, REGION);
            String arn = rule.getArn();

            assertTrue(scheduler.isRunning(arn));

            eventBridgeService.deleteRule("test-rule", "default", REGION);

            assertFalse(scheduler.isRunning(arn));
        }

        @Test
        void disableRuleStopsScheduler() {
            eventBridgeService.getOrCreateDefaultBus(REGION);
            Rule rule = eventBridgeService.putRule(
                    "test-rule", "default", null, "rate(1 minute)",
                    RuleState.ENABLED, null, null, null, REGION);
            String arn = rule.getArn();

            assertTrue(scheduler.isRunning(arn));

            eventBridgeService.disableRule("test-rule", "default", REGION);

            assertFalse(scheduler.isRunning(arn));
        }

        @Test
        void enableRuleStartsScheduler() {
            eventBridgeService.getOrCreateDefaultBus(REGION);
            Rule rule = eventBridgeService.putRule(
                    "test-rule", "default", null, "rate(1 minute)",
                    RuleState.DISABLED, null, null, null, REGION);
            String arn = rule.getArn();

            assertFalse(scheduler.isRunning(arn));

            eventBridgeService.enableRule("test-rule", "default", REGION);

            assertTrue(scheduler.isRunning(arn));
        }

        @Test
        void putRuleDisablingScheduleStopsTimer() {
            eventBridgeService.getOrCreateDefaultBus(REGION);
            Rule rule = eventBridgeService.putRule(
                    "test-rule", "default", null, "rate(1 minute)",
                    RuleState.ENABLED, null, null, null, REGION);
            String arn = rule.getArn();

            assertTrue(scheduler.isRunning(arn));

            eventBridgeService.putRule(
                    "test-rule", "default", null, "rate(1 minute)",
                    RuleState.DISABLED, null, null, null, REGION);

            assertFalse(scheduler.isRunning(arn));
        }

        @Test
        void putRuleRemovingScheduleStopsTimer() {
            eventBridgeService.getOrCreateDefaultBus(REGION);
            Rule rule = eventBridgeService.putRule(
                    "test-rule", "default", null, "rate(1 minute)",
                    RuleState.ENABLED, null, null, null, REGION);
            String arn = rule.getArn();

            assertTrue(scheduler.isRunning(arn));

            eventBridgeService.putRule(
                    "test-rule", "default", null, null,
                    RuleState.ENABLED, null, null, null, REGION);

            assertFalse(scheduler.isRunning(arn));
        }

        @Test
        void changingCronToRateRestartsScheduler() {
            eventBridgeService.getOrCreateDefaultBus(REGION);
            Rule rule = eventBridgeService.putRule(
                    "test-rule", "default", null, "cron(0/1 * * * ? *)",
                    RuleState.ENABLED, null, null, null, REGION);
            String arn = rule.getArn();

            assertTrue(scheduler.isRunning(arn));

            eventBridgeService.putRule(
                    "test-rule", "default", null, "rate(1 minute)",
                    RuleState.ENABLED, null, null, null, REGION);

            assertTrue(scheduler.isRunning(arn));
        }

        @Test
        void changingRateToCronRestartsScheduler() {
            eventBridgeService.getOrCreateDefaultBus(REGION);
            Rule rule = eventBridgeService.putRule(
                    "test-rule", "default", null, "rate(1 minute)",
                    RuleState.ENABLED, null, null, null, REGION);
            String arn = rule.getArn();

            assertTrue(scheduler.isRunning(arn));

            eventBridgeService.putRule(
                    "test-rule", "default", null, "cron(0/1 * * * ? *)",
                    RuleState.ENABLED, null, null, null, REGION);

            assertTrue(scheduler.isRunning(arn));
        }
    }

    @Nested
    @DisplayName("Cron expression lifecycle")
    class CronLifecycle {

        @Test
        void putRuleWithScheduleStartsScheduler() {
            eventBridgeService.getOrCreateDefaultBus(REGION);
            Rule rule = eventBridgeService.putRule(
                    "test-cron-rule", "default", null, "cron(0/1 * * * ? *)",
                    RuleState.ENABLED, null, null, null, REGION);

            assertTrue(scheduler.isRunning(rule.getArn()));
        }

        @Test
        void deleteRuleStopsScheduler() {
            eventBridgeService.getOrCreateDefaultBus(REGION);
            Rule rule = eventBridgeService.putRule(
                    "test-cron-rule", "default", null, "cron(0/1 * * * ? *)",
                    RuleState.ENABLED, null, null, null, REGION);
            String arn = rule.getArn();

            assertTrue(scheduler.isRunning(arn));

            eventBridgeService.deleteRule("test-cron-rule", "default", REGION);

            assertFalse(scheduler.isRunning(arn));
        }

        @Test
        void disableRuleStopsScheduler() {
            eventBridgeService.getOrCreateDefaultBus(REGION);
            Rule rule = eventBridgeService.putRule(
                    "test-cron-rule", "default", null, "cron(0/1 * * * ? *)",
                    RuleState.ENABLED, null, null, null, REGION);
            String arn = rule.getArn();

            assertTrue(scheduler.isRunning(arn));

            eventBridgeService.disableRule("test-cron-rule", "default", REGION);

            assertFalse(scheduler.isRunning(arn));
        }

        @Test
        void enableRuleStartsScheduler() {
            eventBridgeService.getOrCreateDefaultBus(REGION);
            Rule rule = eventBridgeService.putRule(
                    "test-cron-rule", "default", null, "cron(0/1 * * * ? *)",
                    RuleState.DISABLED, null, null, null, REGION);
            String arn = rule.getArn();

            assertFalse(scheduler.isRunning(arn));

            eventBridgeService.enableRule("test-cron-rule", "default", REGION);

            assertTrue(scheduler.isRunning(arn));
        }

        @Test
        void putRuleDisablingScheduleStopsTimer() {
            eventBridgeService.getOrCreateDefaultBus(REGION);
            Rule rule = eventBridgeService.putRule(
                    "test-cron-rule", "default", null, "cron(0/1 * * * ? *)",
                    RuleState.ENABLED, null, null, null, REGION);
            String arn = rule.getArn();

            assertTrue(scheduler.isRunning(arn));

            eventBridgeService.putRule(
                    "test-cron-rule", "default", null, "cron(0/1 * * * ? *)",
                    RuleState.DISABLED, null, null, null, REGION);

            assertFalse(scheduler.isRunning(arn));
        }

        @Test
        void putRuleRemovingScheduleStopsTimer() {
            eventBridgeService.getOrCreateDefaultBus(REGION);
            Rule rule = eventBridgeService.putRule(
                    "test-cron-rule", "default", null, "cron(0/1 * * * ? *)",
                    RuleState.ENABLED, null, null, null, REGION);
            String arn = rule.getArn();

            assertTrue(scheduler.isRunning(arn));

            eventBridgeService.putRule(
                    "test-cron-rule", "default", null, null,
                    RuleState.ENABLED, null, null, null, REGION);

            assertFalse(scheduler.isRunning(arn));
        }
    }

    private EmulatorConfig createConfig() {
        return new EmulatorConfig() {
            @Override
            public String baseUrl() { return "http://localhost:4566"; }
            @Override
            public Optional<String> hostname() { return Optional.empty(); }
            @Override
            public String defaultRegion() { return REGION; }
            @Override
            public String defaultAccountId() { return ACCOUNT; }
            @Override
            public int maxRequestSize() { return 512; }
            @Override
            public String ecrBaseUri() { return ""; }
            @Override
            public StorageConfig storage() { return null; }
            @Override
            public AuthConfig auth() { return null; }
            @Override
            public ServicesConfig services() { return null; }
            @Override
            public EmulatorConfig.InitHooksConfig initHooks() { return null; }
        };
    }
}
