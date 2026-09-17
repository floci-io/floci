package io.github.hectorvent.floci.core.common;

/**
 * Implemented by services that launch Docker containers whose lifetime is bound to the
 * emulator process (Lambda warm pool, ECS tasks, EC2 instances, in-flight build/job
 * containers). {@link ContainerTeardowns#stopAll} runs every implementation, tolerating
 * individual failures, and is invoked from two call sites: {@code EmulatorLifecycle.onStop}
 * during the ShutdownEvent phase, before {@code StorageFactory.shutdownAll()}, so teardown
 * runs at a deterministic point and state changes made while stopping are still captured
 * by the final flush; and {@code EmulatorInfoController} on {@code /state/reset} and
 * {@code /state/nuke}, since those wipe the same tracking state these containers were
 * started against, and without this a reset would leave them running with nothing left
 * to reconcile them against. A {@code @PreDestroy} alone is not sufficient for the
 * shutdown case: bean destruction runs after the ShutdownEvent observers, when the
 * storage flush schedulers are already stopped.
 *
 * <p>Implementations must be idempotent; they may also be invoked from {@code @PreDestroy}
 * as a fallback.
 */
public interface ContainerTeardown {

    void stopManagedContainers();
}
