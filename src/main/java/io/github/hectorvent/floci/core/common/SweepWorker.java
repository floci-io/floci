package io.github.hectorvent.floci.core.common;

import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Runs a periodic sweep on its own daemon thread, one sweep at a time. A service owns one worker and
 * forwards its startup, shutdown and reset events to it; the worker has no CDI wiring of its own.
 */
public final class SweepWorker {
    private static final Logger LOG = Logger.getLogger(SweepWorker.class);

    private final String name;
    private final Consumer<Instant> sweep;
    private final long intervalSeconds;
    private final boolean sweepAtStart;
    private final ScheduledExecutorService executor;
    private final ReentrantLock sweepLock = new ReentrantLock();
    private volatile boolean paused;

    /**
     * @param name display name used in the thread name and log messages
     * @param enabled whether a background thread exists; a disabled worker still runs {@link #tick}
     * @param intervalSeconds delay between sweeps, at least one second
     * @param sweepAtStart whether the first sweep runs at startup, not after one interval
     * @param sweep the work, run under the sweep lock and skipped while paused
     */
    public SweepWorker(String name, boolean enabled, long intervalSeconds, boolean sweepAtStart,
                       Consumer<Instant> sweep) {
        this.name = name;
        this.sweep = sweep;
        this.intervalSeconds = Math.max(1, intervalSeconds);
        this.sweepAtStart = sweepAtStart;
        this.executor = enabled
                ? Executors.newSingleThreadScheduledExecutor(task -> {
                    Thread thread = new Thread(task, name);
                    thread.setDaemon(true);
                    return thread;
                }) : null;
    }

    public void start() {
        if (executor != null) {
            executor.scheduleWithFixedDelay(this::tickSafely, sweepAtStart ? 0 : intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        }
    }

    /** Interrupts a running sweep and waits briefly for it to end. */
    public void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warnv("The {0} worker is still finishing a sweep during shutdown", name);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debugv(e, "Interrupted while stopping the {0} worker", name);
        }
    }

    /** Whether a background thread exists, which the owner's configuration switches decide. */
    public boolean active() {
        return executor != null;
    }

    public void tick(Instant now) {
        sweepLock.lock();
        try {
            if (!paused) {
                sweep.accept(now);
            }
        } finally {
            sweepLock.unlock();
        }
    }

    /** Stops new sweeps and waits for a running one, so it cannot write into storage that reset is about to clear. */
    public void pause() {
        paused = true;
        sweepLock.lock();
        sweepLock.unlock();
    }

    public void resume() {
        paused = false;
    }

    /** Threads waiting for the running sweep, which lets tests observe blocking without timing. */
    public int queuedSweeps() {
        return sweepLock.getQueueLength();
    }

    private void tickSafely() {
        try {
            tick(Instant.now());
        } catch (RuntimeException e) {
            LOG.warnv(e, "The {0} sweep failed", name);
        }
    }
}
