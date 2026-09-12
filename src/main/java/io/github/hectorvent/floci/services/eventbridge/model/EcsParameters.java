package io.github.hectorvent.floci.services.eventbridge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code EcsParameters} block of an EventBridge rule target whose {@code Arn} points at
 * an ECS cluster, mirroring the shape EventBridge's {@code PutTargets} accepts and
 * {@code ListTargetsByRule} returns.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class EcsParameters {

    private String taskDefinitionArn;
    private Integer taskCount;
    private String launchType;
    private String group;
    private NetworkConfiguration networkConfiguration;
    private String platformVersion;
    private String propagateTags;
    private String referenceId;
    private Boolean enableECSManagedTags;
    private Boolean enableExecuteCommand;
    private JsonNode capacityProviderStrategy;
    private JsonNode placementConstraints;
    private JsonNode placementStrategy;
    private JsonNode tags;

    public EcsParameters() {
    }

    @JsonProperty("TaskDefinitionArn")
    public String getTaskDefinitionArn() {
        return taskDefinitionArn;
    }

    @JsonProperty("TaskDefinitionArn")
    public void setTaskDefinitionArn(String taskDefinitionArn) {
        this.taskDefinitionArn = taskDefinitionArn;
    }

    @JsonProperty("TaskCount")
    public Integer getTaskCount() {
        return taskCount;
    }

    @JsonProperty("TaskCount")
    public void setTaskCount(Integer taskCount) {
        this.taskCount = taskCount;
    }

    @JsonProperty("LaunchType")
    public String getLaunchType() {
        return launchType;
    }

    @JsonProperty("LaunchType")
    public void setLaunchType(String launchType) {
        this.launchType = launchType;
    }

    @JsonProperty("Group")
    public String getGroup() {
        return group;
    }

    @JsonProperty("Group")
    public void setGroup(String group) {
        this.group = group;
    }

    @JsonProperty("NetworkConfiguration")
    public NetworkConfiguration getNetworkConfiguration() {
        return networkConfiguration;
    }

    @JsonProperty("NetworkConfiguration")
    public void setNetworkConfiguration(NetworkConfiguration networkConfiguration) {
        this.networkConfiguration = networkConfiguration;
    }

    @JsonProperty("PlatformVersion")
    public String getPlatformVersion() {
        return platformVersion;
    }

    @JsonProperty("PlatformVersion")
    public void setPlatformVersion(String platformVersion) {
        this.platformVersion = platformVersion;
    }

    @JsonProperty("PropagateTags")
    public String getPropagateTags() {
        return propagateTags;
    }

    @JsonProperty("PropagateTags")
    public void setPropagateTags(String propagateTags) {
        this.propagateTags = propagateTags;
    }

    @JsonProperty("ReferenceId")
    public String getReferenceId() {
        return referenceId;
    }

    @JsonProperty("ReferenceId")
    public void setReferenceId(String referenceId) {
        this.referenceId = referenceId;
    }

    @JsonProperty("EnableECSManagedTags")
    public Boolean getEnableECSManagedTags() {
        return enableECSManagedTags;
    }

    @JsonProperty("EnableECSManagedTags")
    public void setEnableECSManagedTags(Boolean enableECSManagedTags) {
        this.enableECSManagedTags = enableECSManagedTags;
    }

    @JsonProperty("EnableExecuteCommand")
    public Boolean getEnableExecuteCommand() {
        return enableExecuteCommand;
    }

    @JsonProperty("EnableExecuteCommand")
    public void setEnableExecuteCommand(Boolean enableExecuteCommand) {
        this.enableExecuteCommand = enableExecuteCommand;
    }

    @JsonProperty("CapacityProviderStrategy")
    public JsonNode getCapacityProviderStrategy() {
        return capacityProviderStrategy;
    }

    @JsonProperty("CapacityProviderStrategy")
    public void setCapacityProviderStrategy(JsonNode capacityProviderStrategy) {
        this.capacityProviderStrategy = capacityProviderStrategy;
    }

    @JsonProperty("PlacementConstraints")
    public JsonNode getPlacementConstraints() {
        return placementConstraints;
    }

    @JsonProperty("PlacementConstraints")
    public void setPlacementConstraints(JsonNode placementConstraints) {
        this.placementConstraints = placementConstraints;
    }

    @JsonProperty("PlacementStrategy")
    public JsonNode getPlacementStrategy() {
        return placementStrategy;
    }

    @JsonProperty("PlacementStrategy")
    public void setPlacementStrategy(JsonNode placementStrategy) {
        this.placementStrategy = placementStrategy;
    }

    @JsonProperty("Tags")
    public JsonNode getTags() {
        return tags;
    }

    @JsonProperty("Tags")
    public void setTags(JsonNode tags) {
        this.tags = tags;
    }
}
