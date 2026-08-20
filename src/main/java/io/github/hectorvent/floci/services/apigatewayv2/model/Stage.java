package io.github.hectorvent.floci.services.apigatewayv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Stage {
    private String stageName;
    private String deploymentId;
    private boolean autoDeploy;
    private long createdDate;
    private long lastUpdatedDate;
    private Map<String, String> stageVariables;
    private Map<String, String> tags = new HashMap<>();

    public Stage() {}

    public String getStageName() { return stageName; }
    public void setStageName(String stageName) { this.stageName = stageName; }

    public String getDeploymentId() { return deploymentId; }
    public void setDeploymentId(String deploymentId) { this.deploymentId = deploymentId; }

    public boolean isAutoDeploy() { return autoDeploy; }
    public void setAutoDeploy(boolean autoDeploy) { this.autoDeploy = autoDeploy; }

    public long getCreatedDate() { return createdDate; }
    public void setCreatedDate(long createdDate) { this.createdDate = createdDate; }

    public long getLastUpdatedDate() { return lastUpdatedDate; }
    public void setLastUpdatedDate(long lastUpdatedDate) { this.lastUpdatedDate = lastUpdatedDate; }

    public Map<String, String> getStageVariables() { return stageVariables; }
    public void setStageVariables(Map<String, String> stageVariables) { this.stageVariables = stageVariables; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
