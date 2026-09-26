package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

/**
 * A Glue workflow. LastRun and Graph are filled in when the workflow is read; the stored copy
 * leaves them unset.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Workflow {
    @JsonProperty("Name")
    private String name;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("DefaultRunProperties")
    private Map<String, String> defaultRunProperties;

    @JsonProperty("CreatedOn")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdOn;

    @JsonProperty("LastModifiedOn")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant lastModifiedOn;

    @JsonProperty("LastRun")
    private WorkflowRun lastRun;

    @JsonProperty("Graph")
    private Map<String, Object> graph;

    @JsonProperty("MaxConcurrentRuns")
    private Integer maxConcurrentRuns;

    public Workflow() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, String> getDefaultRunProperties() { return defaultRunProperties; }
    public void setDefaultRunProperties(Map<String, String> defaultRunProperties) { this.defaultRunProperties = defaultRunProperties; }

    public Instant getCreatedOn() { return createdOn; }
    public void setCreatedOn(Instant createdOn) { this.createdOn = createdOn; }

    public Instant getLastModifiedOn() { return lastModifiedOn; }
    public void setLastModifiedOn(Instant lastModifiedOn) { this.lastModifiedOn = lastModifiedOn; }

    public WorkflowRun getLastRun() { return lastRun; }
    public void setLastRun(WorkflowRun lastRun) { this.lastRun = lastRun; }

    public Map<String, Object> getGraph() { return graph; }
    public void setGraph(Map<String, Object> graph) { this.graph = graph; }

    public Integer getMaxConcurrentRuns() { return maxConcurrentRuns; }
    public void setMaxConcurrentRuns(Integer maxConcurrentRuns) { this.maxConcurrentRuns = maxConcurrentRuns; }
}
