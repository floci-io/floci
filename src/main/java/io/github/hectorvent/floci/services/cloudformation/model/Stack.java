package io.github.hectorvent.floci.services.cloudformation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.*;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Stack {
    private String stackId;
    private String stackName;
    private String region;
    private String status = "CREATE_IN_PROGRESS";
    private String statusReason;
    private Instant creationTime = Instant.now();
    private Instant lastUpdatedTime;
    private String templateBody;
    private List<String> capabilities = new ArrayList<>();
    private Map<String, String> parameters = new LinkedHashMap<>();
    private Map<String, String> outputs = new LinkedHashMap<>();
    private Map<String, String> exports = new LinkedHashMap<>();
    // Maps output key to its export name (when Export.Name is defined on an output)
    private Map<String, String> outputExportNames = new LinkedHashMap<>();
    private Map<String, StackResource> resources = new LinkedHashMap<>();
    private List<StackEvent> events = new ArrayList<>();
    private Map<String, ChangeSet> changeSets = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    private boolean enableTerminationProtection = false;

    public String getStackId() { return stackId; }
    public void setStackId(String stackId) { this.stackId = stackId; }
    public String getStackName() { return stackName; }
    public void setStackName(String stackName) { this.stackName = stackName; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
    public Instant getLastUpdatedTime() { return lastUpdatedTime; }
    public void setLastUpdatedTime(Instant lastUpdatedTime) { this.lastUpdatedTime = lastUpdatedTime; }
    public String getTemplateBody() { return templateBody; }
    public void setTemplateBody(String templateBody) { this.templateBody = templateBody; }
    public List<String> getCapabilities() { return capabilities; }
    public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }
    public Map<String, String> getParameters() { return parameters; }
    public void setParameters(Map<String, String> parameters) { this.parameters = parameters; }
    public Map<String, String> getOutputs() { return outputs; }
    public void setOutputs(Map<String, String> outputs) { this.outputs = outputs; }
    public Map<String, String> getExports() { return exports; }
    public void setExports(Map<String, String> exports) { this.exports = exports; }
    public Map<String, String> getOutputExportNames() { return outputExportNames; }
    public void setOutputExportNames(Map<String, String> outputExportNames) { this.outputExportNames = outputExportNames; }
    public Map<String, StackResource> getResources() { return resources; }
    public void setResources(Map<String, StackResource> resources) { this.resources = resources; }
    public List<StackEvent> getEvents() { return events; }
    public void setEvents(List<StackEvent> events) { this.events = events; }
    public Map<String, ChangeSet> getChangeSets() { return changeSets; }
    public void setChangeSets(Map<String, ChangeSet> changeSets) { this.changeSets = changeSets; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    public boolean isEnableTerminationProtection() { return enableTerminationProtection; }
    public void setEnableTerminationProtection(boolean enableTerminationProtection) { this.enableTerminationProtection = enableTerminationProtection; }
}
