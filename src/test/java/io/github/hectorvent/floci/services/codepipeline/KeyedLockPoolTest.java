package io.github.hectorvent.floci.services.codepipeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyedLockPoolTest {

    @Test
    void serializesSameKeyAndReleasesTheEntry() throws Exception {
        KeyedLockPool locks = new KeyedLockPool();
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(20);

        try {
            for (int i = 0; i < 20; i++) {
                tasks.add(CompletableFuture.runAsync(() -> {
                    ready.countDown();
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    locks.withLock("pipeline", () -> {
                        int current = active.incrementAndGet();
                        maxActive.accumulateAndGet(current, Math::max);
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        } finally {
                            active.decrementAndGet();
                        }
                    });
                }, executor));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);

            assertEquals(1, maxActive.get());
            assertEquals(0, locks.size());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void releasesEntriesWhenActionsFail() {
        KeyedLockPool locks = new KeyedLockPool();

        assertThrows(IllegalStateException.class, () -> locks.withLock("pipeline", () -> {
            throw new IllegalStateException("boom");
        }));

        assertEquals(0, locks.size());
    }

    @Test
    void releasesEntriesForDistinctKeys() {
        KeyedLockPool locks = new KeyedLockPool();

        for (int i = 0; i < 1_000; i++) {
            locks.withLock("pipeline-" + i, () -> {});
        }

        assertEquals(0, locks.size());
    }
}
