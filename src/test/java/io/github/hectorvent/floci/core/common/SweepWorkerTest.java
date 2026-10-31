package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SweepWorkerTest {
    private static final Instant NOW = Instant.parse("2030-01-07T02:00:00Z");

    private final List<Instant> swept = new CopyOnWriteArrayList<>();

    private SweepWorker worker(boolean enabled, Consumer<Instant> sweep) {
        return new SweepWorker("test-sweep", enabled, 60, false, sweep);
    }

    private static void awaitQueuedSweeps(SweepWorker worker, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (worker.queuedSweeps() < expected) {
            assertTrue(System.nanoTime() < deadline, "Expected " + expected + " thread(s) waiting for the sweep");
            Thread.onSpinWait();
        }
    }

    @Test
    void backgroundThreadExistsOnlyWhenEnabled() {
        assertTrue(worker(true, swept::add).active());
        assertFalse(worker(false, swept::add).active());
    }

    @Test
    void tickRunsTheSweepAtTheGivenInstantEvenWithoutABackgroundThread() {
        worker(false, swept::add).tick(NOW);
        assertEquals(List.of(NOW), swept);
    }

    @Test
    void tickIsSkippedBetweenPauseAndResume() {
        SweepWorker worker = worker(false, swept::add);
        worker.pause();
        worker.tick(NOW);
        assertTrue(swept.isEmpty());

        worker.resume();
        worker.tick(NOW);
        assertEquals(List.of(NOW), swept);
    }

    @Test
    void pauseDrainsARunningSweepAndHoldsLaterOnesUntilResume() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SweepWorker worker = worker(false, now -> {
            swept.add(now);
            running.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> sweep = executor.submit(() -> worker.tick(NOW));
            assertTrue(running.await(5, TimeUnit.SECONDS));
            Future<?> pause = executor.submit(worker::pause);
            awaitQueuedSweeps(worker, 1);
            assertFalse(pause.isDone());

            release.countDown();
            pause.get(5, TimeUnit.SECONDS);
            sweep.get(5, TimeUnit.SECONDS);
            worker.tick(NOW.plusSeconds(60));
            assertEquals(List.of(NOW), swept);

            worker.resume();
            worker.tick(NOW.plusSeconds(120));
            assertEquals(List.of(NOW, NOW.plusSeconds(120)), swept);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void overlappingTicksNeverRunTheSweepConcurrently() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SweepWorker worker = worker(false, now -> {
            peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            running.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrent.decrementAndGet();
        });
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> first = executor.submit(() -> worker.tick(NOW));
            assertTrue(running.await(5, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> worker.tick(NOW));
            Future<?> third = executor.submit(() -> worker.tick(NOW));
            awaitQueuedSweeps(worker, 2);
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

    @Test
    void aFailedSweepReleasesTheLock() {
        List<Instant> attempts = new ArrayList<>();
        SweepWorker worker = worker(false, now -> {
            attempts.add(now);
            if (attempts.size() == 1) {
                throw new IllegalStateException("boom");
            }
        });
        assertThrows(IllegalStateException.class, () -> worker.tick(NOW));
        worker.tick(NOW.plusSeconds(60));
        assertEquals(List.of(NOW, NOW.plusSeconds(60)), attempts);
    }

    @Test
    void startRunsTheFirstSweepAtOnceWhenAskedTo() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        SweepWorker worker = new SweepWorker("test-sweep", true, 3600, true, now -> first.countDown());
        try {
            worker.start();
            assertTrue(first.await(5, TimeUnit.SECONDS));
        } finally {
            worker.stop();
        }
    }

    @Test
    void startWaitsOneIntervalBeforeTheFirstSweepByDefault() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        SweepWorker worker = new SweepWorker("test-sweep", true, 3600, false, now -> first.countDown());
        try {
            worker.start();
            assertEquals(1, first.getCount());
        } finally {
            worker.stop();
        }
    }

    @Test
    void stopWithoutABackgroundThreadDoesNothing() {
        SweepWorker worker = worker(false, swept::add);
        worker.start();
        worker.stop();
        assertTrue(swept.isEmpty());
    }
}
