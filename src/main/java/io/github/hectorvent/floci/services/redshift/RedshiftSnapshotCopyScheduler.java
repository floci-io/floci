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

/** Drives cross-Region snapshot copies outside AWS request threads, one sweep at a time. */
@ApplicationScoped
public class RedshiftSnapshotCopyScheduler implements Resettable {
    private final SweepWorker worker;

    @Inject
    public RedshiftSnapshotCopyScheduler(RedshiftService service, EmulatorConfig config) {
        EmulatorConfig.RedshiftServiceConfig redshift = config.services().redshift();
        this.worker = new SweepWorker("redshift-snapshot-copy",
                redshift.enabled() && redshift.snapshotCopyEnabled(),
                redshift.snapshotCopySweepIntervalSeconds(), true, service::runSnapshotCopies);
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

    /** Drains a running sweep so it cannot write a copy back into storage that reset is about to clear. */
    @Override
    public void beforeReset() {
        worker.pause();
    }

    @Override
    public void clear() {
        // Copies live in RedshiftService storage, which reset wipes.
    }

    @Override
    public void afterReset() {
        worker.resume();
    }
}
