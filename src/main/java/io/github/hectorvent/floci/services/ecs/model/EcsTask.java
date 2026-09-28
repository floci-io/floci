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
    /** Cloud Map service ids this task actually registered in, independent of later service updates. */
    private List<String> serviceDiscoveryServiceIds = List.of();
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
    private String networkInterfaceId;
    private String privateIpAddress;
    private String macAddress;
    private String privateDnsName;
    private String attachmentId;
    /** {@code ATTACHED} while the task holds its ENI, {@code DELETED} once it has been released. */
    private String attachmentStatus = "ATTACHED";
    private String platformVersion;
    private String platformFamily;
    /** Set instead of {@link #launchType} when the task was placed through a capacity provider. */
    private String capacityProviderName;
    private String connectivity;
    private Instant connectivityAt;
    private String healthStatus;
    private String stopCode;
    private String availabilityZone;
    private Instant pullStartedAt;
    private Instant pullStoppedAt;
    private Instant executionStoppedAt;
    private Instant stoppingAt;
    private EphemeralStorage ephemeralStorage;
    private boolean enableExecuteCommand;
    private TaskOverride overrides;
    private List<Attribute> attributes;
    /** Bumped on every state change, the way AWS advances a task's optimistic-locking version. */
    private long version = 1;

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
        this.serviceDiscoveryServiceIds = other.serviceDiscoveryServiceIds;
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
        this.networkInterfaceId = other.networkInterfaceId;
        this.privateIpAddress = other.privateIpAddress;
        this.macAddress = other.macAddress;
        this.privateDnsName = other.privateDnsName;
        this.attachmentId = other.attachmentId;
        this.attachmentStatus = other.attachmentStatus;
        this.platformVersion = other.platformVersion;
        this.platformFamily = other.platformFamily;
        this.capacityProviderName = other.capacityProviderName;
        this.connectivity = other.connectivity;
        this.connectivityAt = other.connectivityAt;
        this.healthStatus = other.healthStatus;
        this.stopCode = other.stopCode;
        this.availabilityZone = other.availabilityZone;
        this.pullStartedAt = other.pullStartedAt;
        this.pullStoppedAt = other.pullStoppedAt;
        this.executionStoppedAt = other.executionStoppedAt;
        this.stoppingAt = other.stoppingAt;
        this.ephemeralStorage = other.ephemeralStorage;
        this.enableExecuteCommand = other.enableExecuteCommand;
        this.overrides = other.overrides;
        this.attributes = other.attributes;
        this.version = other.version;
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

    public List<String> getServiceDiscoveryServiceIds() { return serviceDiscoveryServiceIds; }
    public void setServiceDiscoveryServiceIds(List<String> serviceDiscoveryServiceIds) {
        this.serviceDiscoveryServiceIds = serviceDiscoveryServiceIds;
    }

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

    public String getNetworkInterfaceId() { return networkInterfaceId; }
    public void setNetworkInterfaceId(String networkInterfaceId) { this.networkInterfaceId = networkInterfaceId; }
    public String getPrivateIpAddress() { return privateIpAddress; }
    public void setPrivateIpAddress(String privateIpAddress) { this.privateIpAddress = privateIpAddress; }

    public String getMacAddress() { return macAddress; }
    public void setMacAddress(String macAddress) { this.macAddress = macAddress; }

    public String getPrivateDnsName() { return privateDnsName; }
    public void setPrivateDnsName(String privateDnsName) { this.privateDnsName = privateDnsName; }

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

    public String getAttachmentId() { return attachmentId; }
    public void setAttachmentId(String attachmentId) { this.attachmentId = attachmentId; }

    public String getAttachmentStatus() { return attachmentStatus; }
    public void setAttachmentStatus(String attachmentStatus) { this.attachmentStatus = attachmentStatus; }

    public String getPlatformVersion() { return platformVersion; }
    public void setPlatformVersion(String platformVersion) { this.platformVersion = platformVersion; }

    public String getPlatformFamily() { return platformFamily; }
    public void setPlatformFamily(String platformFamily) { this.platformFamily = platformFamily; }

    public String getCapacityProviderName() { return capacityProviderName; }
    public void setCapacityProviderName(String capacityProviderName) {
        this.capacityProviderName = capacityProviderName;
    }

    public String getConnectivity() { return connectivity; }
    public void setConnectivity(String connectivity) { this.connectivity = connectivity; }

    public Instant getConnectivityAt() { return connectivityAt; }
    public void setConnectivityAt(Instant connectivityAt) { this.connectivityAt = connectivityAt; }

    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String healthStatus) { this.healthStatus = healthStatus; }

    public String getStopCode() { return stopCode; }
    public void setStopCode(String stopCode) { this.stopCode = stopCode; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

    public Instant getPullStartedAt() { return pullStartedAt; }
    public void setPullStartedAt(Instant pullStartedAt) { this.pullStartedAt = pullStartedAt; }

    public Instant getPullStoppedAt() { return pullStoppedAt; }
    public void setPullStoppedAt(Instant pullStoppedAt) { this.pullStoppedAt = pullStoppedAt; }

    public Instant getExecutionStoppedAt() { return executionStoppedAt; }
    public void setExecutionStoppedAt(Instant executionStoppedAt) { this.executionStoppedAt = executionStoppedAt; }

    /** When the task left RUNNING for STOPPING, which AWS reports alongside {@code stoppedAt}. */
    public Instant getStoppingAt() { return stoppingAt; }
    public void setStoppingAt(Instant stoppingAt) { this.stoppingAt = stoppingAt; }

    public EphemeralStorage getEphemeralStorage() { return ephemeralStorage; }
    public void setEphemeralStorage(EphemeralStorage ephemeralStorage) { this.ephemeralStorage = ephemeralStorage; }

    public boolean isEnableExecuteCommand() { return enableExecuteCommand; }
    public void setEnableExecuteCommand(boolean enableExecuteCommand) {
        this.enableExecuteCommand = enableExecuteCommand;
    }

    public TaskOverride getOverrides() { return overrides; }
    public void setOverrides(TaskOverride overrides) { this.overrides = overrides; }

    public List<Attribute> getAttributes() { return attributes; }
    public void setAttributes(List<Attribute> attributes) { this.attributes = attributes; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    /** Advances the version AWS bumps on every task state transition. */
    public void bumpVersion() { this.version++; }
}
