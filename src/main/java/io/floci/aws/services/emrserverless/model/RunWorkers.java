package io.floci.aws.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The workers a Spark job run asks for, as EMR Serverless sizes them: its driver and each of its executors, in vCPUs
 * and in GB of memory and of disk, and how many executors it starts with. A run's resource utilization is reckoned
 * from them.
 */
@RegisterForReflection
public class RunWorkers {
    private int driverVcpu;
    private int driverMemoryGb;
    private int driverDiskGb;
    private int executorVcpu;
    private int executorMemoryGb;
    private int executorDiskGb;
    private int executors;

    public RunWorkers() {}

    public RunWorkers(int driverVcpu, int driverMemoryGb, int driverDiskGb, int executorVcpu, int executorMemoryGb,
                      int executorDiskGb, int executors) {
        this.driverVcpu = driverVcpu;
        this.driverMemoryGb = driverMemoryGb;
        this.driverDiskGb = driverDiskGb;
        this.executorVcpu = executorVcpu;
        this.executorMemoryGb = executorMemoryGb;
        this.executorDiskGb = executorDiskGb;
        this.executors = executors;
    }

    public int getDriverVcpu() { return driverVcpu; }
    public void setDriverVcpu(int driverVcpu) { this.driverVcpu = driverVcpu; }
    public int getDriverMemoryGb() { return driverMemoryGb; }
    public void setDriverMemoryGb(int driverMemoryGb) { this.driverMemoryGb = driverMemoryGb; }
    public int getDriverDiskGb() { return driverDiskGb; }
    public void setDriverDiskGb(int driverDiskGb) { this.driverDiskGb = driverDiskGb; }
    public int getExecutorVcpu() { return executorVcpu; }
    public void setExecutorVcpu(int executorVcpu) { this.executorVcpu = executorVcpu; }
    public int getExecutorMemoryGb() { return executorMemoryGb; }
    public void setExecutorMemoryGb(int executorMemoryGb) { this.executorMemoryGb = executorMemoryGb; }
    public int getExecutorDiskGb() { return executorDiskGb; }
    public void setExecutorDiskGb(int executorDiskGb) { this.executorDiskGb = executorDiskGb; }
    public int getExecutors() { return executors; }
    public void setExecutors(int executors) { this.executors = executors; }
}
