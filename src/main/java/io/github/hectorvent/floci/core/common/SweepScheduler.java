package io.github.hectorvent.floci.core.common;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;

import java.time.Instant;

/**
 * Forwards a bean's startup, shutdown and reset events to a {@link SweepWorker}. A subclass supplies the
 * worker; the state it sweeps lives in the owning service's storage, which reset wipes, so there is nothing
 * to clear here.
 */
public abstract class SweepScheduler implements Resettable {
    private final SweepWorker worker;

    protected SweepScheduler(SweepWorker worker) {
        this.worker = worker;
    }

    // Quarkus builds the client proxy of an @ApplicationScoped subclass through this; the proxy never uses it.
    protected SweepScheduler() {
        this.worker = null;
    }

    public void onStart(@Observes StartupEvent ignored) {
        worker.start();
    }

    // Priority below APPLICATION (2000) so the worker drains before EmulatorLifecycle.onStop flushes storage.
    public void onStop(@Observes @Priority(1000) ShutdownEvent ignored) {
        worker.stop();
    }

    /** Whether a background worker exists, which the owner's configuration switches decide. */
    public boolean active() {
        return worker.active();
    }

    public void tick(Instant now) {
        worker.tick(now);
    }

    /** Drains a running sweep so it cannot write into storage that reset is about to clear. */
    @Override
    public void beforeReset() {
        worker.pause();
    }

    @Override
    public void clear() {
        // The swept state lives in the owning service's storage, which reset wipes.
    }

    @Override
    public void afterReset() {
        worker.resume();
    }
}
