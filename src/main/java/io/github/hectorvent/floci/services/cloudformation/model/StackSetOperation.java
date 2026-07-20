package io.github.hectorvent.floci.services.cloudformation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StackSetOperation {
    private String operationId;
    private String stackSetName;
    /** CREATE, UPDATE or DELETE. */
    private String action;
    private String status = "SUCCEEDED";
    private Instant creationTimestamp = Instant.now();
    private Instant endTimestamp;

    public StackSetOperation() {}

    public StackSetOperation(String operationId, String stackSetName, String action) {
        this.operationId = operationId;
        this.stackSetName = stackSetName;
        this.action = action;
    }

    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }
    public String getStackSetName() { return stackSetName; }
    public void setStackSetName(String stackSetName) { this.stackSetName = stackSetName; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreationTimestamp() { return creationTimestamp; }
    public void setCreationTimestamp(Instant creationTimestamp) { this.creationTimestamp = creationTimestamp; }
    public Instant getEndTimestamp() { return endTimestamp; }
    public void setEndTimestamp(Instant endTimestamp) { this.endTimestamp = endTimestamp; }
}
