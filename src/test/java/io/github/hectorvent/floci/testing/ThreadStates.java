package io.github.hectorvent.floci.testing;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Waiting for a thread to reach a lock, for tests that force two requests to overlap. */
public final class ThreadStates {

    /** Every state a thread is in once it has stopped running: parked in any way, or ended. */
    public static final Set<Thread.State> PARKED_OR_DONE = Collections.unmodifiableSet(EnumSet.of(
            Thread.State.BLOCKED, Thread.State.WAITING, Thread.State.TIMED_WAITING, Thread.State.TERMINATED));

    private ThreadStates() {}

    /**
     * Returns once {@code thread} is blocked entering a monitor or parked on a lock, or {@code task} has
     * finished, whichever comes first. Code that serializes correctly leaves the second request blocked
     * behind the first; code that does not lets it run to the end, which the caller's assertions
     * then catch. Polls every 5 ms and gives up after five seconds rather than hang a broken test.
     */
    public static void awaitBlockedOrDone(AtomicReference<Thread> thread, Future<?> task)
            throws InterruptedException {
        awaitBlockedOrDone(thread::get, task::isDone, 5);
    }

    /**
     * Returns once {@code thread} is blocked entering a monitor, parked on a lock, or is no longer alive.
     * Same wait as the {@link Future} overload, for a test that owns the {@link Thread} directly; gives up
     * after ten seconds and fails, since a thread still running unblocked by then is not overlapping the
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
        if (thread == null) {
            return false;
        }
        Thread.State state = thread.getState();
        return state == Thread.State.BLOCKED || state == Thread.State.WAITING;
    }

    /**
     * Waits until {@code thread} is parked in any way (blocked on a monitor, waiting or timed
     * waiting) or has ended, and returns the state it reached. Fails after ten seconds.
     */
    public static Thread.State awaitParkedOrDone(Thread thread) {
        return awaitState(thread, PARKED_OR_DONE, Duration.ofSeconds(10), "the thread");
    }

    /**
     * Waits until {@code thread} is in one of {@code states} and returns the one it reached, checking
     * every millisecond. Fails naming {@code what} and the state it was left in once {@code timeout}
     * passes, so a thread that never gets there fails the test instead of hanging it.
     */
    public static Thread.State awaitState(Thread thread, Set<Thread.State> states, Duration timeout, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Thread.State state = thread.getState();
        while (!states.contains(state)) {
            if (System.nanoTime() - deadline > 0) {
                fail(what + " never reached " + states + ", it was " + state);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            state = thread.getState();
        }
        return state;
    }
}
