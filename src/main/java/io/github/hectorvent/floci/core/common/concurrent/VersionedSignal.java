package io.github.hectorvent.floci.core.common.concurrent;

import java.util.concurrent.TimeUnit;

/**
 * A notification that cannot be lost between reading authoritative state and parking.
 * Observe the version before reading state, then await a change only if the predicate is false.
 * Never hold an authoritative-state lock while awaiting this signal.
 */
public final class VersionedSignal {
    private long version;

    public synchronized long version() {
        return version;
    }

    public synchronized void signal() {
        version++;
        notifyAll();
    }

    /** Returns true on a version change, false on timeout. Interruption belongs to the caller. */
    public synchronized boolean awaitChange(long observed, long timeoutNanos) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutNanos;
        long remaining = timeoutNanos;
        while (version == observed && remaining > 0) {
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
            remaining = deadline - System.nanoTime();
        }
        return version != observed;
    }
}
