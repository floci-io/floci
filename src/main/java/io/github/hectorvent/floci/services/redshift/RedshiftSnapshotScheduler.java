package io.github.hectorvent.floci.services.redshift;

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

/** Drives automated snapshots outside AWS request threads. */
@ApplicationScoped
public class RedshiftSnapshotScheduler implements Resettable {
    private final SweepWorker worker;

    @Inject
    public RedshiftSnapshotScheduler(RedshiftService service, EmulatorConfig config) {
        EmulatorConfig.RedshiftServiceConfig redshift = config.services().redshift();
        this.worker = new SweepWorker("redshift-snapshot-scheduler",
                redshift.enabled() && redshift.automatedSnapshotsEnabled(),
                redshift.snapshotSweepIntervalSeconds(), false, service::runSnapshotSchedules);
    }

    void onStart(@Observes StartupEvent ignored) {
        worker.start();
    }

    // Priority below APPLICATION (2000) so the worker drains before EmulatorLifecycle.onStop flushes storage.
    void onStop(@Observes @Priority(1000) ShutdownEvent ignored) {
        worker.stop();
    }

    public void tick(Instant now) {
        worker.tick(now);
    }

    /** Drains a running sweep so it cannot write old snapshots back into storage that reset is about to clear. */
    @Override
    public void beforeReset() {
        worker.pause();
    }

    @Override
    public void clear() {
        // Snapshot state lives in RedshiftService storage, which reset wipes.
    }

    @Override
    public void afterReset() {
        worker.resume();
    }
}
