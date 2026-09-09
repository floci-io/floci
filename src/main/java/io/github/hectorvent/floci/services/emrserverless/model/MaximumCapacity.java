package io.github.hectorvent.floci.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class MaximumCapacity {
    private String cpu;
    private String disk;
    private String memory;

    public String getCpu() { return cpu; }
    public void setCpu(String cpu) { this.cpu = cpu; }
    public String getDisk() { return disk; }
    public void setDisk(String disk) { this.disk = disk; }
    public String getMemory() { return memory; }
    public void setMemory(String memory) { this.memory = memory; }
}
