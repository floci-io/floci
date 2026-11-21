package io.floci.aws.core.common;

/** Runs a task after a delay; abstracted so tests can run scheduled tasks on demand instead of on timers. */
public interface DelayedTaskScheduler {
    void schedule(Runnable task, long delayMillis);

    /** Stops the scheduler's threads; one that owns none has nothing to stop. */
    default void shutdown() {
    }
}
