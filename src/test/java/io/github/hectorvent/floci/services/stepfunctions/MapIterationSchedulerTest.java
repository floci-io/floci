package io.github.hectorvent.floci.services.stepfunctions;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MapIterationSchedulerTest {

    @Test
    void appliesAwsConcurrencyCeilingsForInlineAndDistributedMaps() {
        assertEquals(40, AslExecutor.effectiveMapConcurrency(50_000, 0, false));
        assertEquals(40, AslExecutor.effectiveMapConcurrency(50_000, 500, false));
        assertEquals(7, AslExecutor.effectiveMapConcurrency(7, 0, false));
        assertEquals(10_000, AslExecutor.effectiveMapConcurrency(50_000, 0, true));
        assertEquals(250, AslExecutor.effectiveMapConcurrency(50_000, 250, true));
    }

    @Test
    void maxConcurrencyOneStartsEachItemStrictlyInInputOrder() throws Exception {
        List<Integer> starts = new java.util.concurrent.CopyOnWriteArrayList<>();

        List<Integer> results = MapIterationScheduler.execute(5, 1, index -> () -> {
            starts.add(index);
            return index;
        }, Long.MAX_VALUE);

        assertEquals(List.of(0, 1, 2, 3, 4), starts);
        assertEquals(starts, results);
    }

    @Test
    void keepsOnlyMaxConcurrencyIterationsInFlight() throws Exception {
        int itemCount = 50;
        int maxConcurrency = 3;
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        AtomicInteger started = new AtomicInteger();
        CountDownLatch firstWaveStarted = new CountDownLatch(maxConcurrency);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService driver = Executors.newSingleThreadExecutor()) {
            Future<List<Integer>> execution = driver.submit(() -> MapIterationScheduler.execute(
                    itemCount, maxConcurrency, index -> () -> {
                        int nowActive = active.incrementAndGet();
                        maxObserved.accumulateAndGet(nowActive, Math::max);
                        started.incrementAndGet();
                        firstWaveStarted.countDown();
                        try {
                            release.await();
                            return index;
                        } finally {
                            active.decrementAndGet();
                        }
                    }, Long.MAX_VALUE));

            assertTrue(firstWaveStarted.await(2, TimeUnit.SECONDS), "initial worker window did not start");
            assertEquals(maxConcurrency, started.get(), "queued items must not be submitted early");
            release.countDown();

            assertEquals(java.util.stream.IntStream.range(0, itemCount).boxed().toList(),
                    execution.get(5, TimeUnit.SECONDS));
            assertEquals(maxConcurrency, maxObserved.get());
        }
    }

    @Test
    void reportsLaterFailureWithoutWaitingForAnEarlierBlockedItem() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch slowInterrupted = new CountDownLatch(1);
        CountDownLatch neverRelease = new CountDownLatch(1);

        try (ExecutorService driver = Executors.newSingleThreadExecutor()) {
            Future<List<Integer>> execution = driver.submit(() -> MapIterationScheduler.execute(
                    2, 2, index -> () -> {
                        if (index == 0) {
                            slowStarted.countDown();
                            try {
                                neverRelease.await();
                                return index;
                            } catch (InterruptedException e) {
                                slowInterrupted.countDown();
                                throw e;
                            }
                        }
                        if (!slowStarted.await(2, TimeUnit.SECONDS)) {
                            fail("slow iteration did not start");
                        }
                        throw new IllegalStateException("later iteration failed");
                    }, Long.MAX_VALUE));

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> execution.get(2, TimeUnit.SECONDS));
            assertEquals(IllegalStateException.class, failure.getCause().getClass());
            assertEquals("later iteration failed", failure.getCause().getMessage());
            assertTrue(slowInterrupted.await(2, TimeUnit.SECONDS),
                    "the blocked sibling should be interrupted after failure");
        }
    }

    @Test
    void preservesInputOrderWhenItemsCompleteOutOfOrder() throws Exception {
        CountDownLatch laterItemsCompleted = new CountDownLatch(2);

        List<Integer> results = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> MapIterationScheduler.execute(3, 3, index -> () -> {
                    if (index == 0) {
                        laterItemsCompleted.await();
                    } else {
                        laterItemsCompleted.countDown();
                    }
                    return index;
                }, Long.MAX_VALUE));

        assertEquals(List.of(0, 1, 2), results);
    }

    @Test
    void stopsStartingSerialIterationsOncePastTheDeadline() {
        AtomicInteger started = new AtomicInteger();

        assertThrows(TimeoutException.class, () -> MapIterationScheduler.execute(3, 1, index -> () -> {
            started.incrementAndGet();
            return index;
        }, System.nanoTime() - 1));

        assertEquals(0, started.get());
    }

    @Test
    void endsTheSerialWaitForASingleItemOncePastTheDeadline() throws Exception {
        CountDownLatch neverRelease = new CountDownLatch(1);
        CountDownLatch blockedInterrupted = new CountDownLatch(1);

        // Not try-with-resources: against the still-buggy serial path the iteration below never
        // returns and is never interrupted, so ExecutorService#close() would block on
        // awaitTermination forever. shutdownNow() in the finally block does not wait.
        ExecutorService driver = Executors.newSingleThreadExecutor();
        try {
            Future<List<Integer>> execution = driver.submit(() -> MapIterationScheduler.execute(
                    1, 3, index -> () -> {
                        try {
                            neverRelease.await();
                            return index;
                        } catch (InterruptedException e) {
                            blockedInterrupted.countDown();
                            throw e;
                        }
                    }, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)));

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> execution.get(2, TimeUnit.SECONDS));
            assertEquals(TimeoutException.class, failure.getCause().getClass());
            assertTrue(blockedInterrupted.await(2, TimeUnit.SECONDS),
                    "the single iteration should be interrupted once the deadline passes");
        } finally {
            driver.shutdownNow();
        }
    }

    @Test
    void endsTheSerialWaitForMaxConcurrencyOneOncePastTheDeadline() throws Exception {
        CountDownLatch neverRelease = new CountDownLatch(1);
        CountDownLatch blockedInterrupted = new CountDownLatch(1);

        // Not try-with-resources: see endsTheSerialWaitForASingleItemOncePastTheDeadline above.
        ExecutorService driver = Executors.newSingleThreadExecutor();
        try {
            Future<List<Integer>> execution = driver.submit(() -> MapIterationScheduler.execute(
                    2, 1, index -> () -> {
                        try {
                            neverRelease.await();
                            return index;
                        } catch (InterruptedException e) {
                            blockedInterrupted.countDown();
                            throw e;
                        }
                    }, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)));

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> execution.get(2, TimeUnit.SECONDS));
            assertEquals(TimeoutException.class, failure.getCause().getClass());
            assertTrue(blockedInterrupted.await(2, TimeUnit.SECONDS),
                    "the blocked iteration should be interrupted once the deadline passes");
        } finally {
            driver.shutdownNow();
        }
    }

    @Test
    void endsTheConcurrentWaitOncePastTheDeadline() throws Exception {
        CountDownLatch neverRelease = new CountDownLatch(1);
        CountDownLatch blockedInterrupted = new CountDownLatch(2);

        try (ExecutorService driver = Executors.newSingleThreadExecutor()) {
            Future<List<Integer>> execution = driver.submit(() -> MapIterationScheduler.execute(
                    2, 2, index -> () -> {
                        try {
                            neverRelease.await();
                            return index;
                        } catch (InterruptedException e) {
                            blockedInterrupted.countDown();
                            throw e;
                        }
                    }, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)));

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> execution.get(5, TimeUnit.SECONDS));
            assertEquals(TimeoutException.class, failure.getCause().getClass());
            assertTrue(blockedInterrupted.await(2, TimeUnit.SECONDS),
                    "the iterations still running should be interrupted once the deadline passes");
        }
    }
}
