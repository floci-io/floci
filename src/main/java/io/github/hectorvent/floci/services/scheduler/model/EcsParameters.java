package io.github.hectorvent.floci.services.scheduler.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
public class EcsParameters {

    private List<Map<String, Object>> capacityProviderStrategy;
    private Boolean enableECSManagedTags;
    private Boolean enableExecuteCommand;
    private String group;
    private String taskDefinitionArn;
    private String launchType;
    private List<Map<String, Object>> placementConstraints;
    private List<Map<String, Object>> placementStrategy;
    private Integer taskCount;
    private String platformVersion;
    private String propagateTags;
    private String referenceId;
    private List<Map<String, Object>> tags;
    private NetworkConfiguration networkConfiguration;

    public List<Map<String, Object>> getCapacityProviderStrategy() { return capacityProviderStrategy; }
    public void setCapacityProviderStrategy(List<Map<String, Object>> capacityProviderStrategy) { this.capacityProviderStrategy = capacityProviderStrategy; }

    public Boolean getEnableECSManagedTags() { return enableECSManagedTags; }
    public void setEnableECSManagedTags(Boolean enableECSManagedTags) { this.enableECSManagedTags = enableECSManagedTags; }

    public Boolean getEnableExecuteCommand() { return enableExecuteCommand; }
    public void setEnableExecuteCommand(Boolean enableExecuteCommand) { this.enableExecuteCommand = enableExecuteCommand; }

    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }

    public String getTaskDefinitionArn() { return taskDefinitionArn; }
    public void setTaskDefinitionArn(String taskDefinitionArn) { this.taskDefinitionArn = taskDefinitionArn; }

    public String getLaunchType() { return launchType; }
    public void setLaunchType(String launchType) { this.launchType = launchType; }

    public List<Map<String, Object>> getPlacementConstraints() { return placementConstraints; }
    public void setPlacementConstraints(List<Map<String, Object>> placementConstraints) { this.placementConstraints = placementConstraints; }

    public List<Map<String, Object>> getPlacementStrategy() { return placementStrategy; }
    public void setPlacementStrategy(List<Map<String, Object>> placementStrategy) { this.placementStrategy = placementStrategy; }

    public Integer getTaskCount() { return taskCount; }
    public void setTaskCount(Integer taskCount) { this.taskCount = taskCount; }

    public String getPlatformVersion() { return platformVersion; }
    public void setPlatformVersion(String platformVersion) { this.platformVersion = platformVersion; }

    public String getPropagateTags() { return propagateTags; }
    public void setPropagateTags(String propagateTags) { this.propagateTags = propagateTags; }

    public String getReferenceId() { return referenceId; }
    public void setReferenceId(String referenceId) { this.referenceId = referenceId; }

    public List<Map<String, Object>> getTags() { return tags; }
    public void setTags(List<Map<String, Object>> tags) { this.tags = tags; }

    public NetworkConfiguration getNetworkConfiguration() { return networkConfiguration; }
    public void setNetworkConfiguration(NetworkConfiguration networkConfiguration) { this.networkConfiguration = networkConfiguration; }
}
