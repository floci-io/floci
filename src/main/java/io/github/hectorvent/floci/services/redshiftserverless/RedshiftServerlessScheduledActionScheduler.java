package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.SweepWorker;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Instant;

/** Drives Serverless scheduled actions outside AWS request threads, one sweep at a time. */
@ApplicationScoped
public class RedshiftServerlessScheduledActionScheduler implements Resettable {
    private final SweepWorker worker;

    @Inject
    public RedshiftServerlessScheduledActionScheduler(RedshiftServerlessScheduledActionService service,
                                                      EmulatorConfig config) {
        EmulatorConfig.RedshiftServerlessServiceConfig serverless = config.services().redshiftServerless();
        this.worker = new SweepWorker("redshift-serverless-scheduled-actions",
                serverless.enabled() && serverless.scheduledActionsEnabled(),
                serverless.scheduledActionSweepIntervalSeconds(), false, service::runScheduledActions);
    }

    void onStart(@Observes StartupEvent ignored) {
        worker.start();
    }

    // Priority below APPLICATION (2000) so the worker drains before EmulatorLifecycle.onStop flushes storage.
    void onStop(@Observes @Priority(1000) ShutdownEvent ignored) {
        worker.stop();
    }

    /** Whether a background worker exists, which the two configuration switches decide. */
    boolean active() {
        return worker.active();
    }

    public void tick(Instant now) {
        worker.tick(now);
    }

    /** Drains a running sweep so it cannot write a claim back into storage that reset is about to clear. */
    @Override
    public void beforeReset() {
        worker.pause();
    }

    @Override
    public void clear() {
        // Claims live with the actions in RedshiftServerlessScheduledActionService storage, which reset wipes.
    }

    @Override
    public void afterReset() {
        worker.resume();
    }
}
