package io.github.hectorvent.floci.services.appconfig.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeploymentSummary {
    @JsonProperty("CompletedAt")
    private String completedAt;
    @JsonProperty("ConfigurationName")
    private String configurationName;
    @JsonProperty("ConfigurationProfileId")
    private String configurationProfileId;
    @JsonProperty("ConfigurationVersion")
    private String configurationVersion;
    @JsonProperty("DeploymentDurationInMinutes")
    private Integer deploymentDurationInMinutes;
    @JsonProperty("DeploymentNumber")
    private int deploymentNumber;
    @JsonProperty("FinalBakeTimeInMinutes")
    private Integer finalBakeTimeInMinutes;
    @JsonProperty("GrowthFactor")
    private Float growthFactor;
    @JsonProperty("GrowthType")
    private String growthType;
    @JsonProperty("PercentageComplete")
    private Float percentageComplete;
    @JsonProperty("StartedAt")
    private String startedAt;
    @JsonProperty("State")
    private String state;
    @JsonProperty("Type")
    private String type;
    @JsonProperty("VersionLabel")
    private String versionLabel;

    public DeploymentSummary() {
    }

    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }
    public String getConfigurationName() { return configurationName; }
    public void setConfigurationName(String configurationName) { this.configurationName = configurationName; }
    public String getConfigurationProfileId() { return configurationProfileId; }
    public void setConfigurationProfileId(String configurationProfileId) { this.configurationProfileId = configurationProfileId; }
    public String getConfigurationVersion() { return configurationVersion; }
    public void setConfigurationVersion(String configurationVersion) { this.configurationVersion = configurationVersion; }
    public Integer getDeploymentDurationInMinutes() { return deploymentDurationInMinutes; }
    public void setDeploymentDurationInMinutes(Integer deploymentDurationInMinutes) { this.deploymentDurationInMinutes = deploymentDurationInMinutes; }
    public int getDeploymentNumber() { return deploymentNumber; }
    public void setDeploymentNumber(int deploymentNumber) { this.deploymentNumber = deploymentNumber; }
    public Integer getFinalBakeTimeInMinutes() { return finalBakeTimeInMinutes; }
    public void setFinalBakeTimeInMinutes(Integer finalBakeTimeInMinutes) { this.finalBakeTimeInMinutes = finalBakeTimeInMinutes; }
    public Float getGrowthFactor() { return growthFactor; }
    public void setGrowthFactor(Float growthFactor) { this.growthFactor = growthFactor; }
    public String getGrowthType() { return growthType; }
    public void setGrowthType(String growthType) { this.growthType = growthType; }
    public Float getPercentageComplete() { return percentageComplete; }
    public void setPercentageComplete(Float percentageComplete) { this.percentageComplete = percentageComplete; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getVersionLabel() { return versionLabel; }
    public void setVersionLabel(String versionLabel) { this.versionLabel = versionLabel; }
}
