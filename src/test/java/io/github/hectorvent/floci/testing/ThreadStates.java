package io.github.hectorvent.floci.testing;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Waiting for a thread to reach a lock, for tests that force two requests to overlap. */
public final class ThreadStates {

    private ThreadStates() {}

    /**
     * Returns once {@code thread} is blocked entering a monitor, or {@code task} has finished,
     * whichever comes first. Code that serializes correctly leaves the second request blocked
     * behind the first; code that does not lets it run to the end, which the caller's assertions
     * then catch. Polls every 5 ms and gives up after five seconds rather than hang a broken test.
     */
    public static void awaitBlockedOrDone(AtomicReference<Thread> thread, Future<?> task)
            throws InterruptedException {
        awaitBlockedOrDone(thread::get, task::isDone, 5);
    }

    /**
     * Returns once {@code thread} is blocked entering a monitor or is no longer alive. Same wait as
     * the {@link Future} overload, for a test that owns the {@link Thread} directly; gives up after
     * ten seconds and fails, since a thread still running unblocked by then is not overlapping the
     * way the test needs.
     */
    public static void awaitBlockedOrDone(Thread thread) throws InterruptedException {
        assertTrue(awaitBlockedOrDone(() -> thread, () -> !thread.isAlive(), 10),
                "worker blocked or finished before timeout");
    }

    private static boolean awaitBlockedOrDone(Supplier<Thread> thread, BooleanSupplier done, long timeoutSeconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline && !done.getAsBoolean()) {
            if (isBlocked(thread.get())) {
                return true;
            }
            Thread.sleep(5);
        }
        return done.getAsBoolean() || isBlocked(thread.get());
    }

    private static boolean isBlocked(Thread thread) {
        return thread != null && thread.getState() == Thread.State.BLOCKED;
    }
}
