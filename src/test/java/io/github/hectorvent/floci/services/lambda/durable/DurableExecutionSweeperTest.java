package io.github.hectorvent.floci.services.lambda.durable;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.LambdaServiceConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.ServicesConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/** A non-positive interval must not reach scheduleWithFixedDelay, which would abort startup. */
class DurableExecutionSweeperTest {

    @Test
    void nonPositiveConfiguredIntervalDoesNotPreventStartup() {
        for (long interval : new long[] {0L, -1L, Long.MIN_VALUE}) {
            DurableExecutionSweeper sweeper = new DurableExecutionSweeper(null, configWithInterval(interval));
            assertDoesNotThrow(() -> sweeper.onStart(new StartupEvent()), "interval " + interval + " must not abort startup");
            sweeper.onStop(new ShutdownEvent());
        }
    }

    @Test
    void positiveConfiguredIntervalStartsNormally() {
        DurableExecutionSweeper sweeper = new DurableExecutionSweeper(null, configWithInterval(30L));
        assertDoesNotThrow(() -> sweeper.onStart(new StartupEvent()));
        sweeper.onStop(new ShutdownEvent());
    }

    private static EmulatorConfig configWithInterval(long intervalSeconds) {
        EmulatorConfig config = configView(EmulatorConfig.class);
        ServicesConfig services = configView(ServicesConfig.class);
        LambdaServiceConfig lambda = configView(LambdaServiceConfig.class);
        doReturn(services).when(config).services();
        doReturn(lambda).when(services).lambda();
        doReturn(true).when(lambda).enabled();
        doReturn(true).when(lambda).durableSweepEnabled();
        doReturn(intervalSeconds).when(lambda).durableSweepIntervalSeconds();
        return config;
    }

    private static <T> T configView(Class<T> configType) {
        return mock(configType, invocation -> {
            throw new AssertionError("Unexpected configuration access: " + invocation.getMethod());
        });
    }
}
