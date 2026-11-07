package io.floci.aws.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
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
    /**
     * The opaque id in this container's {@code ECS_CONTAINER_METADATA_URI_V4}. Minted before the
     * container is created, the way the ECS agent mints one, so the URI can be injected into the
     * container's own environment.
     */
    private String metadataId;
    private String imageDigest;
    private String cpu;
    private String memory;
    private String memoryReservation;
    /**
     * When Docker created, started and finished this container. The ECS API's own container shape
     * carries none of these, so they never reach a DescribeTasks response; the task metadata
     * endpoint reports them, and its containers start and stop one at a time.
     */
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;

    // transient — not persisted
    private transient String dockerId;

    /**
     * Explicit no-arg constructor: the copy constructor below would otherwise remove the
     * implicit default one that production call sites rely on.
     */
    public Container() {
    }

    /**
     * Copy, so a response snapshot can carry the containers as they stood at launch time
     * without sharing the live task's instances. The background launch replaces the live
     * task's containers once they are running, and a stop lands them STOPPED.
     */
    public Container(Container other) {
        this.containerArn = other.containerArn;
        this.taskArn = other.taskArn;
        this.name = other.name;
        this.image = other.image;
        this.lastStatus = other.lastStatus;
        this.exitCode = other.exitCode;
        this.reason = other.reason;
        this.networkBindings = other.networkBindings;
        this.networkInterfaces = other.networkInterfaces;
        this.managedAgents = other.managedAgents;
        this.healthStatus = other.healthStatus;
        this.runtimeId = other.runtimeId;
        this.metadataId = other.metadataId;
        this.imageDigest = other.imageDigest;
        this.cpu = other.cpu;
        this.memory = other.memory;
        this.memoryReservation = other.memoryReservation;
        this.createdAt = other.createdAt;
        this.startedAt = other.startedAt;
        this.finishedAt = other.finishedAt;
        this.dockerId = other.dockerId;
    }

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

    public String getMetadataId() { return metadataId; }
    public void setMetadataId(String metadataId) { this.metadataId = metadataId; }

    public String getImageDigest() { return imageDigest; }
    public void setImageDigest(String imageDigest) { this.imageDigest = imageDigest; }

    public String getCpu() { return cpu; }
    public void setCpu(String cpu) { this.cpu = cpu; }

    public String getMemory() { return memory; }
    public void setMemory(String memory) { this.memory = memory; }

    public String getMemoryReservation() { return memoryReservation; }
    public void setMemoryReservation(String memoryReservation) { this.memoryReservation = memoryReservation; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public String getDockerId() { return dockerId; }
    public void setDockerId(String dockerId) { this.dockerId = dockerId; }
}
