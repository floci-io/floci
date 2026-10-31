package io.github.hectorvent.floci.services.appconfig.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Environment {
    @JsonProperty("Id")
    private String id;
    @JsonProperty("ApplicationId")
    private String applicationId;
    @JsonProperty("Name")
    private String name;
    @JsonProperty("Description")
    private String description;
    @JsonProperty("State")
    private String state; // READY, DEPLOYING, ROLLING_BACK, ROLLED_BACK
    // Null when the environment was created without monitors: AWS then returns no Monitors member.
    @JsonProperty("Monitors")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<Monitor> monitors;

    public Environment() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public List<Monitor> getMonitors() { return monitors; }
    public void setMonitors(List<Monitor> monitors) { this.monitors = monitors != null ? List.copyOf(monitors) : null; }
}
