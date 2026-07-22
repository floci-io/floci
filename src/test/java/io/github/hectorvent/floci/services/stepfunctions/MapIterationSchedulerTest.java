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
        });

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
                    }));

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
                    }));

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
                }));

        assertEquals(List.of(0, 1, 2), results);
    }
}
