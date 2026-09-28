package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * A parsed {@code RunTask} (or {@code StartTask}) request.
 *
 * <p>ECS's launch surface is wide enough that threading it through positional parameters stopped
 * scaling, so the handler fills this and the service reads it. The narrower
 * {@code runTask(...)} overloads on the service remain for callers that only need the common
 * members, such as the EventBridge, Scheduler and Step Functions ECS targets.
 */
@RegisterForReflection
public class RunTaskRequest {

    private String cluster;
    private String taskDefinition;
    private int count = 1;
    private LaunchType launchType;
    private List<CapacityProviderStrategyItem> capacityProviderStrategy;
    private String group;
    private String startedBy;
    private TaskOverride overrides;
    private NetworkConfiguration networkConfiguration;
    private String platformVersion;
    private boolean enableExecuteCommand;
    private boolean enableECSManagedTags;
    private String propagateTags;
    private String referenceId;
    private Map<String, String> tags;
    private List<String> containerInstances;

    public String getCluster() { return cluster; }
    public void setCluster(String cluster) { this.cluster = cluster; }

    public String getTaskDefinition() { return taskDefinition; }
    public void setTaskDefinition(String taskDefinition) { this.taskDefinition = taskDefinition; }

    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }

    public LaunchType getLaunchType() { return launchType; }
    public void setLaunchType(LaunchType launchType) { this.launchType = launchType; }

    public List<CapacityProviderStrategyItem> getCapacityProviderStrategy() { return capacityProviderStrategy; }
    public void setCapacityProviderStrategy(List<CapacityProviderStrategyItem> capacityProviderStrategy) {
        this.capacityProviderStrategy = capacityProviderStrategy;
    }

    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }

    public String getStartedBy() { return startedBy; }
    public void setStartedBy(String startedBy) { this.startedBy = startedBy; }

    public TaskOverride getOverrides() { return overrides; }
    public void setOverrides(TaskOverride overrides) { this.overrides = overrides; }

    public List<ContainerOverride> getContainerOverrides() {
        return overrides == null ? null : overrides.getContainerOverrides();
    }

    public NetworkConfiguration getNetworkConfiguration() { return networkConfiguration; }
    public void setNetworkConfiguration(NetworkConfiguration networkConfiguration) {
        this.networkConfiguration = networkConfiguration;
    }

    public String getPlatformVersion() { return platformVersion; }
    public void setPlatformVersion(String platformVersion) { this.platformVersion = platformVersion; }

    public boolean isEnableExecuteCommand() { return enableExecuteCommand; }
    public void setEnableExecuteCommand(boolean enableExecuteCommand) {
        this.enableExecuteCommand = enableExecuteCommand;
    }

    public boolean isEnableECSManagedTags() { return enableECSManagedTags; }
    public void setEnableECSManagedTags(boolean enableECSManagedTags) {
        this.enableECSManagedTags = enableECSManagedTags;
    }

    public String getPropagateTags() { return propagateTags; }
    public void setPropagateTags(String propagateTags) { this.propagateTags = propagateTags; }

    public String getReferenceId() { return referenceId; }
    public void setReferenceId(String referenceId) { this.referenceId = referenceId; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public List<String> getContainerInstances() { return containerInstances; }
    public void setContainerInstances(List<String> containerInstances) {
        this.containerInstances = containerInstances;
    }
}
