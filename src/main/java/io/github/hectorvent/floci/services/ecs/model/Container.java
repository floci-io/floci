package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public class Container {

    private String containerArn;
    private String taskArn;
    private String name;
    private String image;
    private String lastStatus;
    private Integer exitCode;
    private String reason;
    private List<NetworkBinding> networkBindings;
    private List<TaskNetworkInterface> networkInterfaces;
    private List<ManagedAgent> managedAgents;
    private String healthStatus;
    private String runtimeId;
    private String imageDigest;
    private String cpu;
    private String memory;
    private String memoryReservation;

    // transient — not persisted
    private transient String dockerId;

    public String getContainerArn() { return containerArn; }
    public void setContainerArn(String containerArn) { this.containerArn = containerArn; }

    public String getTaskArn() { return taskArn; }
    public void setTaskArn(String taskArn) { this.taskArn = taskArn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }

    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public List<NetworkBinding> getNetworkBindings() { return networkBindings; }
    public void setNetworkBindings(List<NetworkBinding> networkBindings) { this.networkBindings = networkBindings; }

    public List<TaskNetworkInterface> getNetworkInterfaces() { return networkInterfaces; }
    public void setNetworkInterfaces(List<TaskNetworkInterface> networkInterfaces) {
        this.networkInterfaces = networkInterfaces;
    }

    public List<ManagedAgent> getManagedAgents() { return managedAgents; }
    public void setManagedAgents(List<ManagedAgent> managedAgents) { this.managedAgents = managedAgents; }

    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String healthStatus) { this.healthStatus = healthStatus; }

    public String getRuntimeId() { return runtimeId; }
    public void setRuntimeId(String runtimeId) { this.runtimeId = runtimeId; }

    public String getImageDigest() { return imageDigest; }
    public void setImageDigest(String imageDigest) { this.imageDigest = imageDigest; }

    public String getCpu() { return cpu; }
    public void setCpu(String cpu) { this.cpu = cpu; }

    public String getMemory() { return memory; }
    public void setMemory(String memory) { this.memory = memory; }

    public String getMemoryReservation() { return memoryReservation; }
    public void setMemoryReservation(String memoryReservation) { this.memoryReservation = memoryReservation; }

    public String getDockerId() { return dockerId; }
    public void setDockerId(String dockerId) { this.dockerId = dockerId; }
}
