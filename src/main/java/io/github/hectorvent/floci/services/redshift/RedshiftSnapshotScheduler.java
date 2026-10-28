package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Resettable;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Drives automated snapshots outside AWS request threads. */
@ApplicationScoped
public class RedshiftSnapshotScheduler implements Resettable {
    private static final Logger LOG = Logger.getLogger(RedshiftSnapshotScheduler.class);
    private final RedshiftService service;
    private final ScheduledExecutorService executor;
    private final long intervalSeconds;
    private final ReentrantLock sweepLock = new ReentrantLock();
    private volatile boolean paused;

    @Inject
    public RedshiftSnapshotScheduler(RedshiftService service, EmulatorConfig config) {
        this.service = service;
        this.intervalSeconds = Math.max(1, config.services().redshift().snapshotSweepIntervalSeconds());
        this.executor = config.services().redshift().enabled() && config.services().redshift().automatedSnapshotsEnabled()
                ? Executors.newSingleThreadScheduledExecutor(task -> {
                    Thread thread = new Thread(task, "redshift-snapshot-scheduler");
                    thread.setDaemon(true);
                    return thread;
                }) : null;
    }

    void onStart(@Observes StartupEvent ignored) {
        if (executor != null) {
            executor.scheduleWithFixedDelay(this::tickSafely, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        }
    }

    void onStop(@Observes ShutdownEvent ignored) {
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.warn("Redshift snapshot scheduler is still finishing a dump during shutdown");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.debugv(e, "Interrupted while stopping the Redshift snapshot scheduler");
            }
        }
    }

    public void tick(Instant now) {
        sweepLock.lock();
        try {
            if (!paused) {
                service.runSnapshotSchedules(now);
            }
        } finally {
            sweepLock.unlock();
        }
    }

    /** Drains a running sweep so it cannot write old snapshots back into storage that reset is about to clear. */
    @Override
    public void beforeReset() {
        paused = true;
        sweepLock.lock();
        sweepLock.unlock();
    }

    @Override
    public void clear() {
        // Snapshot state lives in RedshiftService storage, which reset wipes.
    }

    @Override
    public void afterReset() {
        paused = false;
    }

    private void tickSafely() {
        try {
            tick(Instant.now());
        } catch (RuntimeException e) {
            LOG.warnv(e, "Redshift automated snapshot sweep failed");
        }
    }
}
