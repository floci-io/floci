package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public class CreateWorkGroupRequest {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("Tags")
    private List<WorkGroupTag> tags;

    @JsonProperty("Configuration")
    private CreateWorkGroupConfigurationRequest configuration;

    public CreateWorkGroupRequest() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<WorkGroupTag> getTags() { return tags; }
    public void setTags(List<WorkGroupTag> tags) { this.tags = tags; }
    public CreateWorkGroupConfigurationRequest getConfiguration() { return configuration; }
    public void setConfiguration(CreateWorkGroupConfigurationRequest configuration) { this.configuration = configuration; }
}
