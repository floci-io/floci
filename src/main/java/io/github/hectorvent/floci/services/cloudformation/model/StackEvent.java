package io.github.hectorvent.floci.services.cloudformation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.UUID;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StackEvent {
    private String eventId = UUID.randomUUID().toString();
    private String stackId;
    private String stackName;
    private String logicalResourceId;
    private String physicalResourceId;
    private String resourceType;
    private String resourceStatus;
    private String resourceStatusReason;
    private Instant timestamp = Instant.now();

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getStackId() { return stackId; }
    public void setStackId(String stackId) { this.stackId = stackId; }
    public String getStackName() { return stackName; }
    public void setStackName(String stackName) { this.stackName = stackName; }
    public String getLogicalResourceId() { return logicalResourceId; }
    public void setLogicalResourceId(String logicalResourceId) { this.logicalResourceId = logicalResourceId; }
    public String getPhysicalResourceId() { return physicalResourceId; }
    public void setPhysicalResourceId(String physicalResourceId) { this.physicalResourceId = physicalResourceId; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceStatus() { return resourceStatus; }
    public void setResourceStatus(String resourceStatus) { this.resourceStatus = resourceStatus; }
    public String getResourceStatusReason() { return resourceStatusReason; }
    public void setResourceStatusReason(String reason) { this.resourceStatusReason = reason; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
