package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class EcsTask {

    private String taskArn;
    private String clusterArn;
    private String taskDefinitionArn;
    private String group;
    /**
     * ARN of the service that launched this task, assigned by the service reconciler at launch
     * time. Internal to the emulator: it is never read from a {@code RunTask}/{@code StartTask}
     * request and never serialized onto the wire, unlike {@link #group}, which is a free-form
     * caller-supplied label and therefore cannot be trusted to establish ownership.
     */
    private String owningServiceArn;
    private LaunchType launchType;
    private String lastStatus;
    private String desiredStatus;
    private String cpu;
    private String memory;
    private Instant createdAt;
    private Instant startedAt;
    private Instant stoppedAt;
    private String startedBy;
    /** The owning service's deploymentId when this task was launched; null for RunTask/StartTask and legacy tasks. */
    private String deploymentId;
    private String stoppedReason;
    private List<Container> containers;
    private String containerInstanceArn;
    private boolean protectionEnabled;
    private Instant protectedUntil;
    private Map<String, String> tags = new HashMap<>();
    private NetworkConfiguration networkConfiguration;

    public EcsTask() {
    }

    /**
     * Shallow copy, used to synthesize a phase ladder of lifecycle events without mutating the
     * shared task instance held in the live task map — that instance stays visible to concurrent
     * DescribeTasks/ListTasks calls for the whole synthesis, so events must be built from a
     * snapshot instead of toggling {@link #lastStatus} back and forth on the original.
     */
    public EcsTask(EcsTask other) {
        this.taskArn = other.taskArn;
        this.clusterArn = other.clusterArn;
        this.taskDefinitionArn = other.taskDefinitionArn;
        this.group = other.group;
        this.owningServiceArn = other.owningServiceArn;
        this.launchType = other.launchType;
        this.lastStatus = other.lastStatus;
        this.desiredStatus = other.desiredStatus;
        this.cpu = other.cpu;
        this.memory = other.memory;
        this.createdAt = other.createdAt;
        this.startedAt = other.startedAt;
        this.stoppedAt = other.stoppedAt;
        this.startedBy = other.startedBy;
        this.deploymentId = other.deploymentId;
        this.stoppedReason = other.stoppedReason;
        this.containers = other.containers;
        this.containerInstanceArn = other.containerInstanceArn;
        this.protectionEnabled = other.protectionEnabled;
        this.protectedUntil = other.protectedUntil;
        this.tags = other.tags;
        this.networkConfiguration = other.networkConfiguration;
    }

    public String getTaskArn() { return taskArn; }
    public void setTaskArn(String taskArn) { this.taskArn = taskArn; }

    public String getClusterArn() { return clusterArn; }
    public void setClusterArn(String clusterArn) { this.clusterArn = clusterArn; }

    public String getTaskDefinitionArn() { return taskDefinitionArn; }
    public void setTaskDefinitionArn(String taskDefinitionArn) { this.taskDefinitionArn = taskDefinitionArn; }

    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }

    public String getOwningServiceArn() { return owningServiceArn; }
    public void setOwningServiceArn(String owningServiceArn) { this.owningServiceArn = owningServiceArn; }

    public LaunchType getLaunchType() { return launchType; }
    public void setLaunchType(LaunchType launchType) { this.launchType = launchType; }

    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }

    public String getDesiredStatus() { return desiredStatus; }
    public void setDesiredStatus(String desiredStatus) { this.desiredStatus = desiredStatus; }

    public String getCpu() { return cpu; }
    public void setCpu(String cpu) { this.cpu = cpu; }

    public String getMemory() { return memory; }
    public void setMemory(String memory) { this.memory = memory; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getStoppedAt() { return stoppedAt; }
    public void setStoppedAt(Instant stoppedAt) { this.stoppedAt = stoppedAt; }

    public String getStartedBy() { return startedBy; }
    public void setStartedBy(String startedBy) { this.startedBy = startedBy; }

    public String getDeploymentId() { return deploymentId; }
    public void setDeploymentId(String deploymentId) { this.deploymentId = deploymentId; }

    /** The awsvpc network configuration the task was launched with, or null. Carried through from
     *  the RunTask request (including the ecs:runTask Step Functions integration) so it survives the
     *  launch; awsvpc ENI attachments are not emulated in the local mock profile. */
    public NetworkConfiguration getNetworkConfiguration() { return networkConfiguration; }
    public void setNetworkConfiguration(NetworkConfiguration networkConfiguration) {
        this.networkConfiguration = networkConfiguration;
    }

    public String getStoppedReason() { return stoppedReason; }
    public void setStoppedReason(String stoppedReason) { this.stoppedReason = stoppedReason; }

    public List<Container> getContainers() { return containers; }
    public void setContainers(List<Container> containers) { this.containers = containers; }

    public String getContainerInstanceArn() { return containerInstanceArn; }
    public void setContainerInstanceArn(String containerInstanceArn) { this.containerInstanceArn = containerInstanceArn; }

    public boolean isProtectionEnabled() { return protectionEnabled; }
    public void setProtectionEnabled(boolean protectionEnabled) { this.protectionEnabled = protectionEnabled; }

    public Instant getProtectedUntil() { return protectedUntil; }
    public void setProtectedUntil(Instant protectedUntil) { this.protectedUntil = protectedUntil; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
