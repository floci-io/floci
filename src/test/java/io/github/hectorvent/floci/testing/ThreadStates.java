package io.github.hectorvent.floci.testing;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && !task.isDone()) {
            Thread current = thread.get();
            if (current != null && current.getState() == Thread.State.BLOCKED) {
                return;
            }
            Thread.sleep(5);
        }
    }
}
