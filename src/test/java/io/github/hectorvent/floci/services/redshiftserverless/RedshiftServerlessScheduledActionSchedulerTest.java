package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
    void tickRunsTheSweepAtTheGivenInstant() {
        scheduler(true, false).tick(NOW);
        verify(service).runScheduledActions(NOW);
    }

    @Test
    void resetDrainsARunningSweepAndPausesUntilItEnds() throws Exception {
        RedshiftServerlessScheduledActionScheduler scheduler = scheduler(true, false);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            running.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(service).runScheduledActions(NOW);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> sweep = executor.submit(() -> scheduler.tick(NOW));
            assertTrue(running.await(5, TimeUnit.SECONDS));
            Future<?> reset = executor.submit(scheduler::beforeReset);
            assertThrows(TimeoutException.class, () -> reset.get(300, TimeUnit.MILLISECONDS));

            release.countDown();
            reset.get(5, TimeUnit.SECONDS);
            sweep.get(5, TimeUnit.SECONDS);
            scheduler.tick(NOW.plusSeconds(60));
            verify(service, never()).runScheduledActions(NOW.plusSeconds(60));

            scheduler.afterReset();
            scheduler.tick(NOW.plusSeconds(120));
            verify(service).runScheduledActions(NOW.plusSeconds(120));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void overlappingTicksNeverRunTheSweepConcurrently() throws Exception {
        RedshiftServerlessScheduledActionScheduler scheduler = scheduler(true, false);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        doAnswer(invocation -> {
            peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            Thread.sleep(100);
            concurrent.decrementAndGet();
            return null;
        }).when(service).runScheduledActions(NOW);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> first = executor.submit(() -> scheduler.tick(NOW));
            Future<?> second = executor.submit(() -> scheduler.tick(NOW));
            Future<?> third = executor.submit(() -> scheduler.tick(NOW));
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            third.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, peak.get());
    }

    @Test
    void aFailedSweepReleasesTheLock() {
        RedshiftServerlessScheduledActionScheduler scheduler = scheduler(true, false);
        doThrow(new IllegalStateException("boom")).when(service).runScheduledActions(NOW);
        assertThrows(IllegalStateException.class, () -> scheduler.tick(NOW));
        scheduler.tick(NOW.plusSeconds(60));
        verify(service).runScheduledActions(NOW.plusSeconds(60));
    }
}
