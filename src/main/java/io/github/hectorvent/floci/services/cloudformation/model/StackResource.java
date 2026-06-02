package io.github.hectorvent.floci.services.cloudformation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StackResource {
    private String logicalId;
    private String physicalId;
    private String resourceType;
    private String status = "CREATE_IN_PROGRESS";
    private String statusReason;
    private Instant timestamp = Instant.now();
    private Map<String, String> attributes = new HashMap<>();

    public String getLogicalId() { return logicalId; }
    public void setLogicalId(String logicalId) { this.logicalId = logicalId; }
    public String getPhysicalId() { return physicalId; }
    public void setPhysicalId(String physicalId) { this.physicalId = physicalId; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }
}
