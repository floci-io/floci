package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class UpdateWorkGroupRequest {

    @JsonProperty("WorkGroup")
    private String workGroup;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("State")
    private String state;

    @JsonProperty("ConfigurationUpdates")
    private WorkGroupConfigurationUpdates configurationUpdates;

    public UpdateWorkGroupRequest() {}

    public String getWorkGroup() { return workGroup; }
    public void setWorkGroup(String workGroup) { this.workGroup = workGroup; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public WorkGroupConfigurationUpdates getConfigurationUpdates() { return configurationUpdates; }
    public void setConfigurationUpdates(WorkGroupConfigurationUpdates configurationUpdates) {
        this.configurationUpdates = configurationUpdates;
    }
}
