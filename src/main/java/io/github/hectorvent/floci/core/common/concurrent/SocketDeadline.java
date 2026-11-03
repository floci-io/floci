package io.github.hectorvent.floci.core.common.concurrent;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One deadline for socket operations, including peers that continually send incomplete responses. */
public final class SocketDeadline {

    private SocketDeadline() {}

    public static <T> T call(int timeoutSeconds, Callable<T> operation) throws IOException {
        return call(timeoutSeconds, operation, () -> {});
    }

    /**
     * Releases worker ownership before publishing a normal result. Cancelled work retains ownership
     * until it actually finishes, even when it ignores interruption.
     */
    public static <T> T call(int timeoutSeconds, Callable<T> operation, Runnable onWorkerExit) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        AtomicBoolean exited = new AtomicBoolean();
        Runnable finish = () -> {
            if (exited.compareAndSet(false, true)) {
                onWorkerExit.run();
            }
        };
        FutureTask<T> result = new FutureTask<>(() -> {
            try {
                return operation.call();
            } finally {
                // Publish an ordinary result only after resource admission is released.
                finish.run();
            }
        });
        // Interrupting a virtual thread closes its blocking java.net sockets.
        try {
            Thread.ofVirtual().name("socket-deadline").start(() -> {
                try {
                    result.run();
                } finally {
                    finish.run();
                }
            });
        } catch (RuntimeException | Error failure) {
            finish.run();
            throw failure;
        }
        try {
            return result.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            throw new SocketTimeoutException("Socket operation exceeded " + timeoutSeconds + " seconds");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure = new InterruptedIOException("Socket operation interrupted");
            failure.initCause(interrupted);
            throw failure;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Socket operation failed", cause);
        } finally {
            result.cancel(true);
        }
    }
}
