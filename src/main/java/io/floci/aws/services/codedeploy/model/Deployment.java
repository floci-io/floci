package io.floci.aws.services.codedeploy.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Deployment {
    public Deployment() {}

    private String deploymentId;
    private String applicationName;
    private String deploymentGroupName;
    private String deploymentConfigName;
    private String status;
    private Map<String, Object> revision;
    private Double createTime;
    private Double startTime;
    private Double completeTime;
    private Map<String, String> errorInformation;
    private String description;
    private String creator;
    private String computePlatform;

    public String getDeploymentId() { return deploymentId; }
    public void setDeploymentId(String deploymentId) { this.deploymentId = deploymentId; }

    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }

    public String getDeploymentGroupName() { return deploymentGroupName; }
    public void setDeploymentGroupName(String deploymentGroupName) { this.deploymentGroupName = deploymentGroupName; }

    public String getDeploymentConfigName() { return deploymentConfigName; }
    public void setDeploymentConfigName(String deploymentConfigName) { this.deploymentConfigName = deploymentConfigName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, Object> getRevision() { return revision; }
    public void setRevision(Map<String, Object> revision) { this.revision = revision; }

    public Double getCreateTime() { return createTime; }
    public void setCreateTime(Double createTime) { this.createTime = createTime; }

    public Double getStartTime() { return startTime; }
    public void setStartTime(Double startTime) { this.startTime = startTime; }

    public Double getCompleteTime() { return completeTime; }
    public void setCompleteTime(Double completeTime) { this.completeTime = completeTime; }

    public Map<String, String> getErrorInformation() { return errorInformation; }
    public void setErrorInformation(Map<String, String> errorInformation) { this.errorInformation = errorInformation; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCreator() { return creator; }
    public void setCreator(String creator) { this.creator = creator; }

    public String getComputePlatform() { return computePlatform; }
    public void setComputePlatform(String computePlatform) { this.computePlatform = computePlatform; }
}
