package io.github.hectorvent.floci.services.redshiftdata;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedshiftDataExecutorTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shutdownDoesNotWaitForPendingAdmissionAndStillCompletesItAsCancelled(boolean queued) throws Exception {
        try (RedshiftDataExecutor executor = new RedshiftDataExecutor(1, 1, 0);
             ExecutorService callers = Executors.newFixedThreadPool(2)) {
            CountDownLatch busy = new CountDownLatch(1);
            CountDownLatch releaseBusy = new CountDownLatch(1);
            CountDownLatch admitting = new CountDownLatch(1);
            CountDownLatch releaseAdmission = new CountDownLatch(1);
            AtomicBoolean ran = new AtomicBoolean();
            AtomicBoolean cancelled = new AtomicBoolean();
            if (queued) {
                executor.submit("111111111111", "us-east-1", "busy", execution -> {
                    busy.countDown();
                    try {
                        releaseBusy.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException expected) {
                        // Shutdown interrupts this occupied worker before it can dequeue the reservation.
                        Thread.currentThread().interrupt();
                    }
                });
                assertTrue(busy.await(5, TimeUnit.SECONDS));
            }
            Future<RedshiftDataExecution> submission = callers.submit(() -> executor.submit(
                    "111111111111", "us-east-1", "pending", () -> {
                        admitting.countDown();
                        await(releaseAdmission);
                    }, execution -> {
                        ran.set(true);
                        cancelled.set(execution.isCancellationRequested());
                    }, (execution, failure) -> { }));
            assertTrue(admitting.await(5, TimeUnit.SECONDS));
            Future<?> shutdown = callers.submit(executor::close);
            try {
                shutdown.get(1, TimeUnit.SECONDS);
            } finally {
                releaseAdmission.countDown();
                releaseBusy.countDown();
            }
            RedshiftDataExecution pending = submission.get(5, TimeUnit.SECONDS);
            pending.await(Duration.ofSeconds(2));
            assertTrue(pending.isDone(), "late admission must not strand a SUBMITTED execution");
            assertTrue(ran.get());
            assertTrue(cancelled.get());
        }
    }

    @Test
    void rejectedQueueDoesNotRunAdmissionWrites() throws Exception {
        try (RedshiftDataExecutor executor = new RedshiftDataExecutor(1, 1, 1)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try {
                executor.submit("111111111111", "us-east-1", "running", execution -> {
                    started.countDown();
                    await(release);
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                executor.submit("111111111111", "us-east-1", "queued", execution -> { });
                AtomicBoolean written = new AtomicBoolean();
                assertThrows(AwsException.class, () -> executor.submit("111111111111", "us-east-1", "rejected",
                        () -> written.set(true), execution -> { }, (execution, failure) -> { }));
                assertFalse(written.get(), "a rejected queue entry must not leave durable admission state");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void failingStorageResetStillSignalsJdbcCancellationAndCompletesQueuedWork() throws Exception {
        try (RedshiftDataExecutor executor = new RedshiftDataExecutor(1, 1, 1)) {
            Statement jdbc = mock(Statement.class);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch cancelled = new CountDownLatch(1);
            doAnswer(invocation -> {
                cancelled.countDown();
                return null;
            }).when(jdbc).cancel();
            try {
                executor.submit("111111111111", "us-east-1", "running", execution -> {
                    try {
                        execution.track(jdbc);
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                    started.countDown();
                    await(release);
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                AtomicBoolean queuedRan = new AtomicBoolean();
                RedshiftDataExecution queued = executor.submit("111111111111", "us-east-1", "queued",
                        execution -> queuedRan.set(true));
                IllegalStateException storageFailure = new IllegalStateException("receipt clear failed");
                assertSame(storageFailure, assertThrows(IllegalStateException.class, () -> executor.clear(() -> {
                    throw storageFailure;
                })));
                assertTrue(cancelled.await(2, TimeUnit.SECONDS), "reset failure must not skip JDBC cancellation");
                assertTrue(queued.isDone());
                assertFalse(queuedRan.get());
            } finally {
                release.countDown();
            }
        }
    }

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

    @Test
    void unexpectedWorkerFailureReachesTheFailureHandler() throws Exception {
        try (RedshiftDataExecutor executor = new RedshiftDataExecutor(1, 1, 1)) {
            AtomicBoolean recovered = new AtomicBoolean();
            RedshiftDataExecution execution = executor.submit("111111111111", "us-east-1", "broken", () -> { },
                    ignored -> {
                        throw new IllegalStateException("storage unavailable");
                    },
                    (failed, failure) -> recovered.set("storage unavailable".equals(failure.getMessage())));
            execution.await(Duration.ofSeconds(5));
            assertTrue(execution.isDone());
            assertTrue(recovered.get());
        }
    }
}
