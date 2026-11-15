package io.floci.aws.services.applicationautoscaling;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.testing.ThreadStates;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApplicationAutoScalingScheduledActionSchedulerTest {
    @Test
    void disabledServiceDoesNotStartOrRecover() {
        ApplicationAutoScalingService service = mock(ApplicationAutoScalingService.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        ApplicationAutoScalingScheduledActionScheduler scheduler = new ApplicationAutoScalingScheduledActionScheduler(service, config);
        scheduler.onStart(null);
        assertFalse(scheduler.active());
        verifyNoInteractions(service);
        scheduler.onStop(null);
    }

    @Test
    void shutdownDrainsActiveSweepAndPreventsFurtherTicks() throws Exception {
        ApplicationAutoScalingService service = mock(ApplicationAutoScalingService.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().applicationautoscaling().enabled()).thenReturn(true);
        when(config.services().applicationautoscaling().scheduledActionIntervalSeconds()).thenReturn(3600L);
        ApplicationAutoScalingScheduledActionScheduler scheduler = new ApplicationAutoScalingScheduledActionScheduler(service, config);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> stoppingThread = new AtomicReference<>();
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return null;
        }).when(service).runScheduledActions(any());
        scheduler.onStart(null);
        assertTrue(scheduler.active());
        verify(service).recoverScheduledActions(any());
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> tick = executor.submit(() -> scheduler.tick(Instant.now()));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            Future<?> stop = executor.submit(() -> {
                stoppingThread.set(Thread.currentThread());
                scheduler.onStop(null);
            });
            ThreadStates.awaitBlockedOrDone(stoppingThread, stop);
            assertFalse(stop.isDone());
            release.countDown();
            tick.get(10, TimeUnit.SECONDS);
            stop.get(10, TimeUnit.SECONDS);
            scheduler.tick(Instant.now());
            verify(service, times(1)).runScheduledActions(any());
        } finally {
            release.countDown();
            scheduler.onStop(null);
        }
    }

    @Test
    void resetPausesAndResumesTicks() {
        ApplicationAutoScalingService service = mock(ApplicationAutoScalingService.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        ApplicationAutoScalingScheduledActionScheduler scheduler = new ApplicationAutoScalingScheduledActionScheduler(service, config);
        scheduler.beforeReset();
        scheduler.tick(Instant.now());
        verifyNoInteractions(service);
        scheduler.afterReset();
        scheduler.tick(Instant.now());
        verify(service).runScheduledActions(any());
        scheduler.onStop(null);
    }
}
