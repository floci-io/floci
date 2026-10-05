package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Applies bucket lifecycle expiration rules from one background thread. S3 evaluates lifecycle
 * asynchronously rather than on each request, so a sweep over every bucket with a lifecycle
 * configuration on a fixed interval matches that, and leaves nothing to reconcile after a restart.
 */
@ApplicationScoped
public class S3LifecycleSweeper {

    private static final Logger LOG = Logger.getLogger(S3LifecycleSweeper.class);

    /** Used when the configured interval is not a positive number of seconds. */
    static final long DEFAULT_INTERVAL_SECONDS = 60L;

    private final S3Service s3Service;
    private final Clock clock;
    private final boolean enabled;
    private final long intervalSeconds;
    private final ScheduledExecutorService executor;

    @Inject
    public S3LifecycleSweeper(S3Service s3Service, EmulatorConfig config, Clock clock) {
        this.s3Service = s3Service;
        this.clock = clock;
        this.enabled = config.services().s3().enabled() && config.services().s3().lifecycleSweepEnabled();
        this.intervalSeconds = resolveInterval(config.services().s3().lifecycleSweepIntervalSeconds());
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "s3-lifecycle-sweeper");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** A non-positive interval would throw from the startup observer and keep the emulator from becoming ready. */
    private static long resolveInterval(long configured) {
        if (configured > 0) {
            return configured;
        }
        LOG.warnv("Ignoring S3 lifecycle-sweep interval {0}s: must be a positive number of seconds, "
                + "falling back to {1}s", configured, DEFAULT_INTERVAL_SECONDS);
        return DEFAULT_INTERVAL_SECONDS;
    }

    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            LOG.debug("S3 lifecycle sweeper disabled");
            return;
        }
        executor.scheduleWithFixedDelay(this::sweep, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        LOG.debugv("S3 lifecycle sweeper started, interval {0}s", intervalSeconds);
    }

    void onStop(@Observes ShutdownEvent event) {
        executor.shutdownNow();
    }

    void sweep() {
        try {
            s3Service.applyLifecycleExpiration(clock.instant());
        } catch (RuntimeException e) {
            // A sweep failure must not kill the scheduled task. The next run retries.
            LOG.error("S3 lifecycle sweep failed", e);
        }
    }
}
