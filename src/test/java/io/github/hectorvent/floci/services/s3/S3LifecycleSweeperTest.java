package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.S3ServiceConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.ServicesConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class S3LifecycleSweeperTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void aFailingSweepDoesNotEscapeTheScheduledTask() {
        S3Service s3Service = mock(S3Service.class);
        doThrow(new IllegalStateException("sweep failed")).when(s3Service).applyLifecycleExpiration(CLOCK.instant());
        S3LifecycleSweeper sweeper = new S3LifecycleSweeper(s3Service, configWithInterval(60L), CLOCK);

        assertDoesNotThrow(sweeper::sweep);
        verify(s3Service).applyLifecycleExpiration(CLOCK.instant());
    }

    @Test
    void nonPositiveConfiguredInterval_doesNotPreventStartup() {
        for (long interval : new long[] {0L, -1L, Long.MIN_VALUE}) {
            S3LifecycleSweeper sweeper = new S3LifecycleSweeper(null, configWithInterval(interval), CLOCK);
            assertDoesNotThrow(() -> sweeper.onStart(new StartupEvent()),
                    "interval " + interval + " must not abort startup");
            sweeper.onStop(new ShutdownEvent());
        }
    }

    private static EmulatorConfig configWithInterval(long intervalSeconds) {
        EmulatorConfig config = configView(EmulatorConfig.class);
        ServicesConfig services = configView(ServicesConfig.class);
        S3ServiceConfig s3 = configView(S3ServiceConfig.class);
        doReturn(services).when(config).services();
        doReturn(s3).when(services).s3();
        doReturn(true).when(s3).enabled();
        doReturn(true).when(s3).lifecycleSweepEnabled();
        doReturn(intervalSeconds).when(s3).lifecycleSweepIntervalSeconds();
        return config;
    }

    private static <T> T configView(Class<T> configType) {
        return mock(configType, invocation -> {
            throw new AssertionError("Unexpected configuration access: " + invocation.getMethod());
        });
    }
}
