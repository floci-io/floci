package io.github.hectorvent.floci.services.redshiftdata;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedshiftDataExecutionTest {

    @Test
    void runningCancellationCancelsJdbcAndPreventsCommit() throws Exception {
        try (RedshiftDataExecutor executor = new RedshiftDataExecutor(1, 1, 1)) {
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            Statement statement = mock(Statement.class);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            RedshiftDataExecution handle = executor.submit("111111111111", "us-east-1", "query", execution -> {
                try {
                    execution.track(statement);
                    started.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    assertThrows(SQLException.class, () -> execution.commit(() -> fail("must not commit")));
                } catch (Throwable failure) {
                    workerFailure.set(failure);
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(executor.cancel("111111111111", "query"));
            verify(statement).cancel();
            release.countDown();
            handle.await(Duration.ofSeconds(5));
            assertTrue(handle.isDone());
            assertNull(workerFailure.get());
            assertFalse(executor.cancel("111111111111", "query"));
        }
    }

    @Test
    void successfulCommitWinsOverCancellation() throws Exception {
        try (RedshiftDataExecutor executor = new RedshiftDataExecutor(1, 1, 1)) {
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            CountDownLatch committed = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            RedshiftDataExecution handle = executor.submit("111111111111", "us-east-1", "query", execution -> {
                try {
                    execution.commit(committed::countDown);
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (Throwable failure) {
                    workerFailure.set(failure);
                }
            });
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            assertFalse(executor.cancel("111111111111", "query"));
            release.countDown();
            handle.await(Duration.ofSeconds(5));
            assertTrue(handle.isDone());
            assertNull(workerFailure.get());
        }
    }
}
