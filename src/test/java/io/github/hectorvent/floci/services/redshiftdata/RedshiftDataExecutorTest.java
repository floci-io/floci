package io.github.hectorvent.floci.services.redshiftdata;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RedshiftDataExecutorTest {

    @Test
    void admissionReturnsWhileSqlIsRunningAndRejectsOverflow() throws Exception {
        try (RedshiftDataExecutor executor = new RedshiftDataExecutor(1, 1, 1)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            RedshiftDataExecution first = executor.submit("111111111111", "us-east-1", "first", execution -> {
                started.countDown();
                await(release);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(first.isDone());
            executor.submit("111111111111", "us-east-1", "second", execution -> {});
            AwsException failure = assertThrows(AwsException.class,
                    () -> executor.submit("111111111111", "us-east-1", "third", execution -> {}));
            assertEquals("ActiveStatementsExceededException", failure.getErrorCode());
            release.countDown();
            first.await(Duration.ofSeconds(5));
            assertTrue(first.isDone());
            assertFalse(executor.cancel("222222222222", "second"));
        }
    }

    @Test
    void queuedCancellationSkipsSqlAndCompletesImmediately() throws Exception {
        try (RedshiftDataExecutor executor = new RedshiftDataExecutor(1, 1, 1)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.submit("111111111111", "us-east-1", "first", execution -> {
                started.countDown();
                await(release);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            AtomicBoolean cancelled = new AtomicBoolean();
            RedshiftDataExecution second = executor.submit("111111111111", "us-east-1", "second",
                    execution -> cancelled.set(execution.isCancellationRequested()));
            assertTrue(executor.cancel("111111111111", "second"));
            second.await(Duration.ofSeconds(5));
            assertTrue(cancelled.get());
            assertFalse(executor.cancel("111111111111", "second"));
            release.countDown();
        }
    }

    @Test
    void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RedshiftDataExecutor(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RedshiftDataExecutor(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RedshiftDataExecutor(1, 1, -1));
    }

    @Test
    void resetSuppressesEventsQueuedByAnAlreadyCompletedExecution() throws Exception {
        try (RedshiftDataExecutor executor = new RedshiftDataExecutor(1, 4, 1)) {
            RedshiftDataExecution completed = executor.submit("111111111111", "us-east-1", "query", execution -> {});
            completed.await(Duration.ofSeconds(5));
            CountDownLatch delivering = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            Runnable blockedDelivery = () -> {
                delivering.countDown();
                await(release);
            };
            executor.deliver(completed, "111111111111", "us-east-1", "query", blockedDelivery);
            executor.deliver(completed, "111111111111", "us-east-1", "query", blockedDelivery);
            assertTrue(delivering.await(5, TimeUnit.SECONDS));
            AtomicBoolean publishedAfterClear = new AtomicBoolean();
            executor.deliver(completed, "111111111111", "us-east-1", "query", () -> publishedAfterClear.set(true));
            executor.clear(() -> {});
            release.countDown();
            executor.close();
            assertFalse(publishedAfterClear.get());
        }
    }

    @Test
    void shutdownReturnsWithinBudgetWhileCommitIsBlocked() throws Exception {
        RedshiftDataExecutor executor = new RedshiftDataExecutor(1, 1, 0);
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        executor.submit("111111111111", "us-east-1", "query", execution -> {
            try {
                execution.commit(() -> {
                    committing.countDown();
                    boolean waiting = true;
                    while (waiting) {
                        try {
                            release.await();
                            waiting = false;
                        } catch (InterruptedException ignored) {
                            // Model a JDBC commit that does not respond to thread interruption.
                        }
                    }
                });
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
        assertTrue(committing.await(5, TimeUnit.SECONDS));
        Thread closer = Thread.startVirtualThread(() -> {
            executor.close();
            closed.countDown();
        });
        try {
            assertTrue(closed.await(1, TimeUnit.SECONDS), "shutdown must not wait on the JDBC commit monitor");
        } finally {
            release.countDown();
            closer.join(5000);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException expected) {
            Thread.currentThread().interrupt();
            throw new AssertionError(expected);
        }
    }
}
