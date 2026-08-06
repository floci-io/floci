package io.github.hectorvent.floci.core.common;

/**
 * Implemented by services that launch Docker containers whose lifetime is bound to the
 * emulator process (Lambda warm pool, ECS tasks, EC2 instances, in-flight build/job
 * containers). {@code EmulatorLifecycle.onStop} invokes every implementation during the
 * ShutdownEvent phase — before {@code StorageFactory.shutdownAll()} — so teardown runs at
 * a deterministic point and state changes made while stopping are still captured by the
 * final flush. A {@code @PreDestroy} alone is not sufficient for that: bean destruction
 * runs after the ShutdownEvent observers, when the storage flush schedulers are already
 * stopped.
 *
 * <p>Implementations must be idempotent; they may also be invoked from {@code @PreDestroy}
 * as a fallback.
 */
public interface ContainerTeardown {

    void stopManagedContainers();
}
