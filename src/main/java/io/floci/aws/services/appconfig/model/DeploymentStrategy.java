package io.floci.aws.services.appconfig.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeploymentStrategy {
    @JsonProperty("Id")
    private String id;
    @JsonProperty("Name")
    private String name;
    @JsonProperty("Description")
    private String description;
    @JsonProperty("DeploymentDurationInMinutes")
    private int deploymentDurationInMinutes;
    @JsonProperty("GrowthFactor")
    private float growthFactor;
    @JsonProperty("FinalBakeTimeInMinutes")
    private int finalBakeTimeInMinutes;
    @JsonProperty("GrowthType")
    private String growthType; // LINEAR, EXPONENTIAL
    @JsonProperty("ReplicateTo")
    private String replicateTo; // NONE, SSM_DOCUMENT

    public DeploymentStrategy() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getDeploymentDurationInMinutes() { return deploymentDurationInMinutes; }
    public void setDeploymentDurationInMinutes(int deploymentDurationInMinutes) { this.deploymentDurationInMinutes = deploymentDurationInMinutes; }

    public float getGrowthFactor() { return growthFactor; }
    public void setGrowthFactor(float growthFactor) { this.growthFactor = growthFactor; }

    public int getFinalBakeTimeInMinutes() { return finalBakeTimeInMinutes; }
    public void setFinalBakeTimeInMinutes(int finalBakeTimeInMinutes) { this.finalBakeTimeInMinutes = finalBakeTimeInMinutes; }

    public String getGrowthType() { return growthType; }
    public void setGrowthType(String growthType) { this.growthType = growthType; }

    public String getReplicateTo() { return replicateTo; }
    public void setReplicateTo(String replicateTo) { this.replicateTo = replicateTo; }
}
