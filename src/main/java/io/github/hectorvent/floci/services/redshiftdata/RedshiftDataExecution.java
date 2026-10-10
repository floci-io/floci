package io.github.hectorvent.floci.services.redshiftdata;

import org.jboss.logging.Logger;
import org.postgresql.PGConnection;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class RedshiftDataExecution {
    private static final Logger LOG = Logger.getLogger(RedshiftDataExecution.class);

    private final CountDownLatch done = new CountDownLatch(1);
    private final long generation;
    private Statement statement;
    private Connection connection;
    private boolean cancellationRequested;
    private boolean committed;
    private boolean committing;
    private boolean terminal;
    private volatile boolean valid = true;
    private Runnable task;

    RedshiftDataExecution(long generation) {
        this.generation = generation;
    }

    long generation() {
        return generation;
    }

    synchronized void track(Connection active) throws SQLException {
        checkCancellation();
        connection = active;
    }

    synchronized void track(Statement active) throws SQLException {
        checkCancellation();
        statement = active;
    }

    synchronized void untrack(Statement active) {
        if (statement == active) {
            statement = null;
        }
    }

    synchronized void checkCancellation() throws SQLException {
        if (cancellationRequested || !valid) {
            throw new SQLException("Statement cancelled", "57014");
        }
    }

    void commit(SqlAction action) throws SQLException {
        synchronized (this) {
            checkCancellation();
            committing = true;
        }
        try {
            action.run();
            synchronized (this) {
                committed = true;
            }
        } finally {
            synchronized (this) {
                committing = false;
            }
        }
    }

    boolean cancel() {
        if (!requestCancellation()) {
            return false;
        }
        signalCancellation();
        Thread.startVirtualThread(() -> {
            while (isCancellationRequested() && !isDone()) {
                await(Duration.ofMillis(100));
                if (!isDone()) {
                    signalCancellation();
                }
            }
        });
        return true;
    }

    synchronized boolean requestCancellation() {
        if (terminal || committed || committing || cancellationRequested) {
            return false;
        }
        cancellationRequested = true;
        return true;
    }

    void signalCancellation() {
        Statement activeStatement;
        Connection activeConnection;
        synchronized (this) {
            activeStatement = statement;
            activeConnection = connection;
        }
        try {
            if (activeStatement != null) {
                activeStatement.cancel();
            } else if (activeConnection != null && activeConnection.isWrapperFor(PGConnection.class)) {
                activeConnection.unwrap(PGConnection.class).cancelQuery();
            }
        } catch (SQLException failure) {
            // The worker still observes cancellation before commit and rolls back its transaction.
            LOG.warnv(failure, "Unable to signal JDBC cancellation; worker will abort before commit");
        }
    }

    synchronized boolean isCancellationRequested() {
        return cancellationRequested;
    }

    synchronized void terminal() {
        terminal = true;
        statement = null;
        connection = null;
    }

    void completed() {
        terminal();
        done.countDown();
    }

    void await(Duration timeout) {
        try {
            done.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException expected) {
            // End the bounded wait and preserve interruption for the caller.
            Thread.currentThread().interrupt();
        }
    }

    boolean isDone() {
        return done.getCount() == 0;
    }

    boolean isValid() {
        return valid;
    }

    void invalidate() {
        valid = false;
    }

    void task(Runnable runnable) {
        task = runnable;
    }

    Runnable task() {
        return task;
    }

    @FunctionalInterface
    interface SqlAction {
        void run() throws SQLException;
    }
}
