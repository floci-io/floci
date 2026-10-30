package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Resettable;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Drives Serverless snapshot expirations outside AWS request threads, one sweep at a time. */
@ApplicationScoped
public class RedshiftServerlessSnapshotExpirationScheduler implements Resettable {
    private static final Logger LOG = Logger.getLogger(RedshiftServerlessSnapshotExpirationScheduler.class);
    private final RedshiftServerlessService service;
    private final RedshiftServerlessResourcePolicyService policies;
    private final ScheduledExecutorService executor;
    private final long intervalSeconds;
    private final ReentrantLock sweepLock = new ReentrantLock();
    private volatile boolean paused;

    @Inject
    public RedshiftServerlessSnapshotExpirationScheduler(RedshiftServerlessService service,
                                                      RedshiftServerlessResourcePolicyService policies,
                                                      EmulatorConfig config) {
        EmulatorConfig.RedshiftServerlessServiceConfig serverless = config.services().redshiftServerless();
        this.service = service;
        this.policies = policies;
        this.intervalSeconds = Math.max(1, serverless.snapshotExpirationSweepIntervalSeconds());
        this.executor = serverless.enabled() && serverless.snapshotExpirationEnabled()
                ? Executors.newSingleThreadScheduledExecutor(task -> {
                    Thread thread = new Thread(task, "redshift-serverless-snapshot-expiration");
                    thread.setDaemon(true);
                    return thread;
                }) : null;
    }

    void onStart(@Observes StartupEvent ignored) {
        if (executor != null) {
            executor.scheduleWithFixedDelay(this::tickSafely, 0, intervalSeconds, TimeUnit.SECONDS);
        }
    }

    // Priority below APPLICATION (2000) so the worker drains before EmulatorLifecycle.onStop flushes storage.
    void onStop(@Observes @Priority(1000) ShutdownEvent ignored) {
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.warn("The Redshift Serverless snapshot expiration worker is still finishing a snapshot during shutdown");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.debugv(e, "Interrupted while stopping the Redshift Serverless snapshot expiration worker");
            }
        }
    }

    /** Whether a background worker exists, which the two configuration switches decide. */
    boolean active() {
        return executor != null;
    }

    public void tick(Instant now) {
        sweepLock.lock();
        try {
            if (!paused) {
                service.expireSnapshots(now, policies::deleteExpiredSnapshotPolicy);
            }
        } finally {
            sweepLock.unlock();
        }
    }

    /** Drains a running sweep so it cannot write a snapshot back into storage that reset is about to clear. */
    @Override
    public void beforeReset() {
        paused = true;
        sweepLock.lock();
        sweepLock.unlock();
    }

    @Override
    public void clear() {
        // Snapshot state lives in RedshiftServerlessService storage, which reset wipes.
    }

    @Override
    public void afterReset() {
        paused = false;
    }

    private void tickSafely() {
        try {
            tick(Instant.now());
        } catch (RuntimeException e) {
            LOG.warnv(e, "The Redshift Serverless snapshot expiration sweep failed");
        }
    }
}
