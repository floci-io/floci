package io.github.hectorvent.floci.services.cloudformation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChangeSet {
    private String changeSetId;
    private String changeSetName;
    private String stackName;
    private String stackId;
    private String status = "CREATE_COMPLETE";
    private String executionStatus = "AVAILABLE";
    private String statusReason;
    private String templateBody;
    private Map<String, String> parameters;
    private List<String> capabilities;
    private String changeSetType; // CREATE or UPDATE
    private Instant creationTime = Instant.now();

    public String getChangeSetId() { return changeSetId; }
    public void setChangeSetId(String changeSetId) { this.changeSetId = changeSetId; }
    public String getChangeSetName() { return changeSetName; }
    public void setChangeSetName(String changeSetName) { this.changeSetName = changeSetName; }
    public String getStackName() { return stackName; }
    public void setStackName(String stackName) { this.stackName = stackName; }
    public String getStackId() { return stackId; }
    public void setStackId(String stackId) { this.stackId = stackId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getExecutionStatus() { return executionStatus; }
    public void setExecutionStatus(String executionStatus) { this.executionStatus = executionStatus; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }
    public String getTemplateBody() { return templateBody; }
    public void setTemplateBody(String templateBody) { this.templateBody = templateBody; }
    public Map<String, String> getParameters() { return parameters; }
    public void setParameters(Map<String, String> parameters) { this.parameters = parameters; }
    public List<String> getCapabilities() { return capabilities; }
    public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }
    public String getChangeSetType() { return changeSetType; }
    public void setChangeSetType(String changeSetType) { this.changeSetType = changeSetType; }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
}
