package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.Priority;
import jakarta.interceptor.Interceptor;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftServerlessScheduledActionSchedulerTest {
    private static final Instant NOW = Instant.parse("2030-01-07T02:00:00Z");

    private final RedshiftServerlessScheduledActionService service = mock(RedshiftServerlessScheduledActionService.class);

    private RedshiftServerlessScheduledActionScheduler scheduler(boolean serviceEnabled, boolean actionsEnabled) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().redshiftServerless().enabled()).thenReturn(serviceEnabled);
        when(config.services().redshiftServerless().scheduledActionsEnabled()).thenReturn(actionsEnabled);
        when(config.services().redshiftServerless().scheduledActionSweepIntervalSeconds()).thenReturn(60);
        return new RedshiftServerlessScheduledActionScheduler(service, config);
    }

    @Test
    void workerExistsOnlyWhenTheServiceAndTheSwitchAreBothOn() {
        assertTrue(scheduler(true, true).active());
        assertFalse(scheduler(true, false).active());
        assertFalse(scheduler(false, true).active());
    }

    @Test
    void shutdownObserverRunsBeforeStorageIsFlushedAndClosed() throws Exception {
        Method onStop = RedshiftServerlessScheduledActionScheduler.class
                .getDeclaredMethod("onStop", ShutdownEvent.class);
        Annotation priority = Arrays.stream(onStop.getParameterAnnotations()[0])
                .filter(Priority.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertTrue(((Priority) priority).value() < Interceptor.Priority.APPLICATION);
    }

    @Test
    void tickRunsTheSweepAtTheGivenInstant() {
        scheduler(true, false).tick(NOW);
        verify(service).runScheduledActions(NOW);
    }

    @Test
    void tickIsSkippedBetweenBeforeResetAndAfterReset() {
        RedshiftServerlessScheduledActionScheduler scheduler = scheduler(true, false);
        scheduler.beforeReset();
        scheduler.tick(NOW);
        verify(service, never()).runScheduledActions(NOW);

        scheduler.afterReset();
        scheduler.tick(NOW);
        verify(service).runScheduledActions(NOW);
    }
}
