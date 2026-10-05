package io.github.hectorvent.floci.services.lambda.durable;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives durable execution timers from one background thread. Waits, step retries, execution
 * timeouts and retention expiry are persisted deadlines. A fixed-interval sweep fires them without
 * one timer per operation, so a restart has nothing to reconcile.
 */
@ApplicationScoped
public class DurableExecutionSweeper {

    private static final Logger LOG = Logger.getLogger(DurableExecutionSweeper.class);

    static final long DEFAULT_TICK_INTERVAL_SECONDS = 1L;

    private final DurableExecutionService service;
    private final boolean enabled;
    private final long tickIntervalSeconds;
    private final ScheduledExecutorService executor;

    @Inject
    public DurableExecutionSweeper(DurableExecutionService service, EmulatorConfig config) {
        this.service = service;
        this.enabled = config.services().lambda().enabled() && config.services().lambda().durableSweepEnabled();
        this.tickIntervalSeconds = resolveInterval(config.services().lambda().durableSweepIntervalSeconds());
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lambda-durable-sweeper");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** A non-positive interval would throw from the startup observer and keep the emulator from becoming ready. */
    private static long resolveInterval(long configured) {
        if (configured > 0) {
            return configured;
        }
        LOG.warnv("Ignoring Lambda durable-sweep interval {0}s: must be a positive number of seconds, "
                + "falling back to {1}s", configured, DEFAULT_TICK_INTERVAL_SECONDS);
        return DEFAULT_TICK_INTERVAL_SECONDS;
    }

    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            LOG.debug("Lambda durable execution sweeper disabled");
            return;
        }
        executor.scheduleWithFixedDelay(this::tick, tickIntervalSeconds, tickIntervalSeconds, TimeUnit.SECONDS);
        LOG.debugv("Lambda durable execution sweeper started, interval {0}s", tickIntervalSeconds);
    }

    void onStop(@Observes ShutdownEvent event) {
        executor.shutdownNow();
    }

    private void tick() {
        try {
            service.sweep();
        } catch (RuntimeException e) {
            // A sweep failure must not kill the scheduled task. The next tick retries.
            LOG.error("Lambda durable execution sweep failed", e);
        }
    }
}
