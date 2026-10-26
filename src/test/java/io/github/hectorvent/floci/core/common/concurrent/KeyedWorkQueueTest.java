package io.github.hectorvent.floci.core.common.concurrent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class KeyedWorkQueueTest {
    @Test
    void concurrentProducersConvergeToEveryLatestValueWithoutOverlappingAKey() throws Exception {
        AtomicIntegerArray versions = new AtomicIntegerArray(32);
        AtomicIntegerArray observed = new AtomicIntegerArray(32);
        AtomicIntegerArray active = new AtomicIntegerArray(32);
        AtomicInteger overlaps = new AtomicInteger();
        try (KeyedWorkQueue<Integer> queue = new KeyedWorkQueue<>("stress-test", 4, 32, key -> {
            if (active.incrementAndGet(key) != 1) {
                overlaps.incrementAndGet();
            }
            observed.set(key, versions.get(key));
            active.decrementAndGet(key);
        })) {
            try (ExecutorService producers = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> submissions = new ArrayList<>();
                for (int producer = 0; producer < 8; producer++) {
                    submissions.add(producers.submit(() -> {
                        for (int i = 0; i < 2000; i++) {
                            int key = i % 32;
                            versions.incrementAndGet(key);
                            queue.submit(key);
                        }
                    }));
                }
                for (Future<?> submission : submissions) {
                    submission.get();
                }
            }
            await().atMost(Duration.ofSeconds(5)).until(() -> queue.snapshot().keys() == 0);
            assertEquals(0, overlaps.get());
            assertEquals(0, queue.snapshot().rejected());
            for (int key = 0; key < 32; key++) {
                assertEquals(versions.get(key), observed.get(key), "Lost update for key " + key);
            }
        }
    }

    @Test
    void notificationDuringWorkIsCoalescedWithoutLosingTheNextPass() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        try (KeyedWorkQueue<String> queue = new KeyedWorkQueue<>("wake-test", 2, 4, key -> {
            if (runs.incrementAndGet() == 1) {
                entered.countDown();
                awaitLatch(release);
            }
        })) {
            assertTrue(queue.submit("a"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 10_000; i++) {
                assertTrue(queue.submit("a"));
            }
            assertEquals(1, runs.get());
            assertEquals(1, queue.snapshot().keys());
            release.countDown();
            await().atMost(Duration.ofSeconds(2)).until(() -> queue.snapshot().keys() == 0);
            assertEquals(2, runs.get());
            assertEquals(10_000, queue.snapshot().coalesced());
        } finally {
            release.countDown();
        }
    }

    @Test
    void blockedKeyDoesNotBlockAnotherKeyAndCapacityIsBounded() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<String> runs = new CopyOnWriteArrayList<>();
        try (KeyedWorkQueue<String> queue = new KeyedWorkQueue<>("bounded-test", 2, 3, key -> {
            runs.add(key);
            entered.countDown();
            awaitLatch(release);
        })) {
            assertTrue(queue.submit("a"));
            assertTrue(queue.submit("b"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(queue.submit("c"));
            assertFalse(queue.submit("d"));
            assertEquals(2, queue.snapshot().active());
            assertEquals(3, queue.snapshot().keys());
            release.countDown();
            await().atMost(Duration.ofSeconds(2)).until(() -> queue.snapshot().keys() == 0);
            assertEquals(3, runs.size());
            // A later authoritative repair pass can retry the rejected key.
            assertTrue(queue.submit("d"));
            await().atMost(Duration.ofSeconds(2)).until(() -> runs.contains("d"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void hotKeyYieldsToQueuedKeys() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> runs = new CopyOnWriteArrayList<>();
        try (KeyedWorkQueue<String> queue = new KeyedWorkQueue<>("fair-test", 1, 3, key -> {
            runs.add(key);
            if (runs.size() == 1) {
                entered.countDown();
                awaitLatch(release);
            }
        })) {
            queue.submit("hot");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            queue.submit("hot");
            queue.submit("cold");
            release.countDown();
            await().atMost(Duration.ofSeconds(2)).until(() -> queue.snapshot().completed() == 3);
            assertEquals(List.of("hot", "cold", "hot"), runs);
        } finally {
            release.countDown();
        }
    }

    @Test
    void failureDoesNotStrandTheQueue() {
        AtomicInteger runs = new AtomicInteger();
        try (KeyedWorkQueue<String> queue = new KeyedWorkQueue<>("failure-test", 1, 2, key -> {
            if (runs.incrementAndGet() == 1) {
                throw new IllegalStateException("expected failure");
            }
        })) {
            queue.submit("first");
            queue.submit("second");
            await().atMost(Duration.ofSeconds(2)).until(() -> queue.snapshot().completed() == 2);
            assertEquals(1, queue.snapshot().failures());
            assertEquals(0, queue.snapshot().keys());
        }
    }

    @Test
    void closeInterruptsActiveWorkAndDiscardsQueuedWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        KeyedWorkQueue<String> queue = new KeyedWorkQueue<>("close-test", 1, 2, key -> {
            runs.incrementAndGet();
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        try {
            queue.submit("active");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            queue.submit("queued");
            queue.close();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertFalse(queue.submit("late"));
            await().atMost(Duration.ofSeconds(2)).until(() -> queue.snapshot().active() == 0);
            assertEquals(1, runs.get());
            assertEquals(0, queue.snapshot().keys());
        } finally {
            queue.close();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
