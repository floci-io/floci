package io.github.hectorvent.floci.services.sqs;

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

@ApplicationScoped
public class SqsRetentionSweeper {

    private static final Logger LOG = Logger.getLogger(SqsRetentionSweeper.class);
    private static final long SWEEP_INTERVAL_SECONDS = 60;

    private final SqsService sqsService;
    private final boolean enabled;
    private final ScheduledExecutorService scheduler;

    @Inject
    public SqsRetentionSweeper(SqsService sqsService, EmulatorConfig config) {
        this.sqsService = sqsService;
        this.enabled = config.services().sqs().enabled();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sqs-retention-sweeper");
            t.setDaemon(true);
            return t;
        });
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!enabled) {
            return;
        }
        scheduler.scheduleAtFixedRate(this::sweep, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
        LOG.infov("SQS retention sweeper scheduled ({0}s interval)", SWEEP_INTERVAL_SECONDS);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        scheduler.shutdownNow();
    }

    void sweep() {
        try {
            sqsService.deleteExpiredMessages();
        } catch (RuntimeException e) {
            LOG.warnv(e, "SQS retention sweep failed; retrying on the next run");
        }
    }
}
