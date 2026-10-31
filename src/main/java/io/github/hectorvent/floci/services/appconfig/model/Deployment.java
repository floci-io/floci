package io.github.hectorvent.floci.services.appconfig.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Deployment {
    @JsonProperty("ApplicationId")
    private String applicationId;
    @JsonProperty("EnvironmentId")
    private String environmentId;
    @JsonProperty("ConfigurationProfileId")
    private String configurationProfileId;
    @JsonProperty("DeploymentNumber")
    private int deploymentNumber;
    @JsonProperty("ConfigurationName")
    private String configurationName;
    // Omitted rather than null on deployments stored before it was recorded, so they read back unchanged.
    @JsonProperty("ConfigurationLocationUri")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String configurationLocationUri;
    @JsonProperty("ConfigurationVersion")
    private String configurationVersion;
    @JsonProperty("DeploymentStrategyId")
    private String deploymentStrategyId;
    // The strategy's settings as the deployment started with them, so changing or deleting the strategy
    // leaves them as they were. Omitted on deployments stored before they were recorded.
    @JsonProperty("DeploymentDurationInMinutes")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer deploymentDurationInMinutes;
    @JsonProperty("FinalBakeTimeInMinutes")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer finalBakeTimeInMinutes;
    @JsonProperty("GrowthFactor")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Float growthFactor;
    @JsonProperty("GrowthType")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String growthType;
    @JsonProperty("State")
    private String state; // BAKING, VALIDATING, DEPLOYING, COMPLETE, ROLLING_BACK, ROLLED_BACK
    @JsonProperty("Description")
    private String description;

    public Deployment() {}

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }

    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }

    public String getConfigurationProfileId() { return configurationProfileId; }
    public void setConfigurationProfileId(String configurationProfileId) { this.configurationProfileId = configurationProfileId; }

    public int getDeploymentNumber() { return deploymentNumber; }
    public void setDeploymentNumber(int deploymentNumber) { this.deploymentNumber = deploymentNumber; }

    public String getConfigurationName() { return configurationName; }
    public void setConfigurationName(String configurationName) { this.configurationName = configurationName; }

    public String getConfigurationLocationUri() { return configurationLocationUri; }
    public void setConfigurationLocationUri(String configurationLocationUri) { this.configurationLocationUri = configurationLocationUri; }

    public String getConfigurationVersion() { return configurationVersion; }
    public void setConfigurationVersion(String configurationVersion) { this.configurationVersion = configurationVersion; }

    public String getDeploymentStrategyId() { return deploymentStrategyId; }
    public void setDeploymentStrategyId(String deploymentStrategyId) { this.deploymentStrategyId = deploymentStrategyId; }

    public Integer getDeploymentDurationInMinutes() { return deploymentDurationInMinutes; }
    public void setDeploymentDurationInMinutes(Integer deploymentDurationInMinutes) { this.deploymentDurationInMinutes = deploymentDurationInMinutes; }

    public Integer getFinalBakeTimeInMinutes() { return finalBakeTimeInMinutes; }
    public void setFinalBakeTimeInMinutes(Integer finalBakeTimeInMinutes) { this.finalBakeTimeInMinutes = finalBakeTimeInMinutes; }

    public Float getGrowthFactor() { return growthFactor; }
    public void setGrowthFactor(Float growthFactor) { this.growthFactor = growthFactor; }

    public String getGrowthType() { return growthType; }
    public void setGrowthType(String growthType) { this.growthType = growthType; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
