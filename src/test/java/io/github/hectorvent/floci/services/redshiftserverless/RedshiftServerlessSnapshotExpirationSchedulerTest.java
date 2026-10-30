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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftServerlessSnapshotExpirationSchedulerTest {
    private static final Instant NOW = Instant.parse("2030-01-07T02:00:00Z");

    private final RedshiftServerlessService service = mock(RedshiftServerlessService.class);

    private RedshiftServerlessSnapshotExpirationScheduler scheduler(boolean serviceEnabled, boolean actionsEnabled) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().redshiftServerless().enabled()).thenReturn(serviceEnabled);
        when(config.services().redshiftServerless().snapshotExpirationEnabled()).thenReturn(actionsEnabled);
        when(config.services().redshiftServerless().snapshotExpirationSweepIntervalSeconds()).thenReturn(60);
        return new RedshiftServerlessSnapshotExpirationScheduler(service, mock(RedshiftServerlessResourcePolicyService.class), config);
    }

    @Test
    void workerExistsOnlyWhenTheServiceAndTheSwitchAreBothOn() {
        assertTrue(scheduler(true, true).active());
        assertFalse(scheduler(true, false).active());
        assertFalse(scheduler(false, true).active());
    }

    @Test
    void shutdownObserverRunsBeforeStorageIsFlushedAndClosed() throws Exception {
        Method onStop = RedshiftServerlessSnapshotExpirationScheduler.class
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
        verify(service).expireSnapshots(eq(NOW), any());
    }

    @Test
    void resetDrainsARunningSweepAndPausesUntilItEnds() throws Exception {
        RedshiftServerlessSnapshotExpirationScheduler scheduler = scheduler(true, false);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            running.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(service).expireSnapshots(eq(NOW), any());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> sweep = executor.submit(() -> scheduler.tick(NOW));
            assertTrue(running.await(5, TimeUnit.SECONDS));
            Future<?> reset = executor.submit(scheduler::beforeReset);
            awaitQueuedSweeps(scheduler, 1);
            assertFalse(reset.isDone());

            release.countDown();
            reset.get(5, TimeUnit.SECONDS);
            sweep.get(5, TimeUnit.SECONDS);
            scheduler.tick(NOW.plusSeconds(60));
            verify(service, never()).expireSnapshots(eq(NOW.plusSeconds(60)), any());

            scheduler.afterReset();
            scheduler.tick(NOW.plusSeconds(120));
            verify(service).expireSnapshots(eq(NOW.plusSeconds(120)), any());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void overlappingTicksNeverRunTheSweepConcurrently() throws Exception {
        RedshiftServerlessSnapshotExpirationScheduler scheduler = scheduler(true, false);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            running.countDown();
            release.await(10, TimeUnit.SECONDS);
            concurrent.decrementAndGet();
            return null;
        }).when(service).expireSnapshots(eq(NOW), any());
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> first = executor.submit(() -> scheduler.tick(NOW));
            assertTrue(running.await(5, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> scheduler.tick(NOW));
            Future<?> third = executor.submit(() -> scheduler.tick(NOW));
            awaitQueuedSweeps(scheduler, 2);
            assertEquals(1, concurrent.get());

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            third.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertEquals(1, peak.get());
    }

    private static void awaitQueuedSweeps(RedshiftServerlessSnapshotExpirationScheduler scheduler, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (scheduler.queuedSweeps() < expected) {
            assertTrue(System.nanoTime() < deadline, "Expected " + expected + " thread(s) waiting for the sweep");
            Thread.onSpinWait();
        }
    }

    @Test
    void aFailedSweepReleasesTheLock() {
        RedshiftServerlessSnapshotExpirationScheduler scheduler = scheduler(true, false);
        doThrow(new IllegalStateException("boom")).when(service).expireSnapshots(eq(NOW), any());
        assertThrows(IllegalStateException.class, () -> scheduler.tick(NOW));
        scheduler.tick(NOW.plusSeconds(60));
        verify(service).expireSnapshots(eq(NOW.plusSeconds(60)), any());
    }
}
