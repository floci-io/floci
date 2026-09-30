package io.github.hectorvent.floci.services.swf;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.ServicesConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.SwfServiceConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * The sweep interval reaches {@code scheduleWithFixedDelay}, which rejects a non-positive
 * delay. Because the schedule happens in a {@link StartupEvent} observer, an
 * IllegalArgumentException there stops the whole emulator from becoming ready, so a
 * misconfigured interval must not be passed through unchecked.
 */
class SwfTimeoutSweeperTest {

    @Test
    void nonPositiveConfiguredInterval_doesNotPreventStartup() {
        for (long interval : new long[] {0L, -1L, Long.MIN_VALUE}) {
            SwfTimeoutSweeper sweeper = new SwfTimeoutSweeper(null, configWithInterval(interval));
            assertDoesNotThrow(() -> sweeper.onStart(new StartupEvent()),
                    "interval " + interval + " must not abort startup");
            sweeper.onStop(new ShutdownEvent());
        }
    }

    @Test
    void positiveConfiguredInterval_startsNormally() {
        SwfTimeoutSweeper sweeper = new SwfTimeoutSweeper(null, configWithInterval(30L));
        assertDoesNotThrow(() -> sweeper.onStart(new StartupEvent()));
        sweeper.onStop(new ShutdownEvent());
    }

    @Test
    void configFixtureRejectsOtherServicePaths() {
        EmulatorConfig config = configWithInterval(30L);

        AssertionError otherService = assertThrows(AssertionError.class, () -> config.services().ecs());
        assertTrue(otherService.getMessage().contains("ecs"));
        assertThrows(AssertionError.class, () -> config.services().rds());
    }

    private static EmulatorConfig configWithInterval(long intervalSeconds) {
        EmulatorConfig config = configView(EmulatorConfig.class);
        ServicesConfig services = configView(ServicesConfig.class);
        SwfServiceConfig swf = configView(SwfServiceConfig.class);
        doReturn(services).when(config).services();
        doReturn(swf).when(services).swf();
        doReturn(true).when(swf).enabled();
        doReturn(true).when(swf).timeoutSweepEnabled();
        doReturn(intervalSeconds).when(swf).timeoutSweepIntervalSeconds();
        return config;
    }

    private static <T> T configView(Class<T> configType) {
        return mock(configType, invocation -> {
            throw new AssertionError("Unexpected configuration access: " + invocation.getMethod());
        });
    }
}
