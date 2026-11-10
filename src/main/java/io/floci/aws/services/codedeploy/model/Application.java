package io.floci.aws.services.codedeploy.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Application {
    public Application() {}

    private String applicationId;
    private String applicationName;
    private Double createTime;
    private Boolean linkedToGitHub;
    private String gitHubAccountName;
    private String computePlatform;

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }

    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }

    public Double getCreateTime() { return createTime; }
    public void setCreateTime(Double createTime) { this.createTime = createTime; }

    public Boolean getLinkedToGitHub() { return linkedToGitHub; }
    public void setLinkedToGitHub(Boolean linkedToGitHub) { this.linkedToGitHub = linkedToGitHub; }

    public String getGitHubAccountName() { return gitHubAccountName; }
    public void setGitHubAccountName(String gitHubAccountName) { this.gitHubAccountName = gitHubAccountName; }

    public String getComputePlatform() { return computePlatform; }
    public void setComputePlatform(String computePlatform) { this.computePlatform = computePlatform; }
}
