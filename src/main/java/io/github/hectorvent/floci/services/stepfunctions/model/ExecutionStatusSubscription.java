package io.github.hectorvent.floci.services.stepfunctions.model;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A scoped wakeup for a child execution ending or its parent being stopped.
 * Read the version before authoritative state so completion cannot be lost
 * between that read and parking. Closing removes both registrations.
 */
public final class ExecutionStatusSubscription implements AutoCloseable {
    private final Execution child;
    private final Execution parent;
    private long version;
    private boolean closed;
    private boolean cancelled;

    public ExecutionStatusSubscription(Execution child, Execution parent) {
        this.child = Objects.requireNonNull(child);
        this.parent = parent == child ? null : parent;
        child.registerStatusSubscription(this);
        if (this.parent != null) {
            this.parent.registerStatusSubscription(this);
        }
    }

    public synchronized long version() {
        return version;
    }

    synchronized void signal() {
        version++;
        notifyAll();
    }

    public synchronized void cancel() {
        cancelled = true;
        notifyAll();
    }

    public synchronized boolean cancelled() {
        return cancelled;
    }

    public synchronized boolean awaitChange(long observed, long timeoutNanos) throws InterruptedException {
        // The executor owns the deadline and its clock. Recheck there after every wake,
        // including a spurious wake, rather than creating a second real-clock deadline.
        if (!closed && !cancelled && version == observed && timeoutNanos > 0) {
            TimeUnit.NANOSECONDS.timedWait(this, timeoutNanos);
        }
        return closed || cancelled || version != observed;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            notifyAll();
        }
        // setStatus holds an execution monitor while signalling. Do not take
        // an execution monitor while holding this subscription's monitor.
        child.unregisterStatusSubscription(this);
        if (parent != null) {
            parent.unregisterStatusSubscription(this);
        }
    }
}
