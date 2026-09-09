package io.github.hectorvent.floci.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class InitialCapacityConfig {
    private WorkerConfiguration workerConfiguration;
    private Long workerCount;

    public WorkerConfiguration getWorkerConfiguration() { return workerConfiguration; }
    public void setWorkerConfiguration(WorkerConfiguration workerConfiguration) { this.workerConfiguration = workerConfiguration; }
    public Long getWorkerCount() { return workerCount; }
    public void setWorkerCount(Long workerCount) { this.workerCount = workerCount; }
}
