package io.github.hectorvent.floci.core.common.concurrent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class VersionedSignalTest {
    @Test
    void notificationBetweenReadingStateAndParkingIsRetained() throws Exception {
        VersionedSignal signal = new VersionedSignal();
        long observed = signal.version();
        signal.signal();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> wait = executor.submit(() -> signal.awaitChange(observed, TimeUnit.SECONDS.toNanos(20)));
            assertTrue(wait.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void aChangeReleasesEveryWaiterIncludingLateRegistrations() throws Exception {
        VersionedSignal signal = new VersionedSignal();
        long observed = signal.version();
        CountDownLatch entering = new CountDownLatch(16);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> waiters = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                waiters.add(executor.submit(() -> {
                    entering.countDown();
                    return signal.awaitChange(observed, TimeUnit.SECONDS.toNanos(20));
                }));
            }
            assertTrue(entering.await(2, TimeUnit.SECONDS));
            signal.signal();
            for (Future<Boolean> waiter : waiters) {
                assertTrue(waiter.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void anUnchangedVersionWaitsForItsDeadline() throws Exception {
        VersionedSignal signal = new VersionedSignal();
        long started = System.nanoTime();
        assertFalse(signal.awaitChange(signal.version(), Duration.ofMillis(30).toNanos()));
        assertTrue(System.nanoTime() - started >= Duration.ofMillis(30).toNanos());
    }

    @Test
    void interruptionReleasesTheWaiterWithoutPublishingAChange() throws Exception {
        VersionedSignal signal = new VersionedSignal();
        CountDownLatch entering = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        Thread waiter = Thread.ofVirtual().start(() -> {
            entering.countDown();
            try {
                signal.awaitChange(signal.version(), TimeUnit.SECONDS.toNanos(20));
            } catch (InterruptedException expected) {
                interrupted.countDown();
            }
        });
        try {
            assertTrue(entering.await(2, TimeUnit.SECONDS));
            waiter.interrupt();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(0, signal.version());
        } finally {
            waiter.interrupt();
            waiter.join(2000);
        }
    }
}
