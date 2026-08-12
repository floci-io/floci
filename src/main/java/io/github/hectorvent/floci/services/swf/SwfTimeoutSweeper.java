package io.github.hectorvent.floci.services.swf;

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
 * Drives SWF timeout expiry from one background thread.
 *
 * <p>SWF timeouts are observable state changes — an activity that misses its
 * start-to-close timeout must produce an ActivityTaskTimedOut event and a fresh
 * decision task whether or not anyone is polling. A single sweep over open
 * executions on a fixed interval delivers that without one timer per task, and
 * leaves nothing to reconcile after a restart.
 */
@ApplicationScoped
public class SwfTimeoutSweeper {

    private static final Logger LOG = Logger.getLogger(SwfTimeoutSweeper.class);

    private final SwfService swfService;
    private final boolean enabled;
    private final long tickIntervalSeconds;
    private final ScheduledExecutorService executor;

    @Inject
    public SwfTimeoutSweeper(SwfService swfService, EmulatorConfig config) {
        this.swfService = swfService;
        this.enabled = config.services().swf().enabled()
                && config.services().swf().timeoutSweepEnabled();
        this.tickIntervalSeconds = config.services().swf().timeoutSweepIntervalSeconds();
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "swf-timeout-sweeper");
            thread.setDaemon(true);
            return thread;
        });
    }

    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            LOG.debug("SWF timeout sweeper disabled");
            return;
        }
        executor.scheduleWithFixedDelay(this::tick, tickIntervalSeconds, tickIntervalSeconds, TimeUnit.SECONDS);
        LOG.debugv("SWF timeout sweeper started, interval {0}s", tickIntervalSeconds);
    }

    void onStop(@Observes ShutdownEvent event) {
        executor.shutdownNow();
    }

    private void tick() {
        try {
            swfService.sweep();
        } catch (RuntimeException e) {
            // A sweep failure must not kill the scheduled task; the next tick retries.
            LOG.error("SWF timeout sweep failed", e);
        }
    }
}
