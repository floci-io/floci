package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

/**
 * One run of a Glue workflow. Statistics and Graph are computed from the job runs and crawls of
 * the run when it is read; the stored copy leaves them unset.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowRun {
    @JsonProperty("Name")
    private String name;

    @JsonProperty("WorkflowRunId")
    private String workflowRunId;

    @JsonProperty("PreviousRunId")
    private String previousRunId;

    @JsonProperty("WorkflowRunProperties")
    private Map<String, String> workflowRunProperties;

    @JsonProperty("StartedOn")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant startedOn;

    @JsonProperty("CompletedOn")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant completedOn;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("ErrorMessage")
    private String errorMessage;

    @JsonProperty("Statistics")
    private Map<String, Integer> statistics;

    @JsonProperty("Graph")
    private Map<String, Object> graph;

    public WorkflowRun() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(String workflowRunId) { this.workflowRunId = workflowRunId; }

    public String getPreviousRunId() { return previousRunId; }
    public void setPreviousRunId(String previousRunId) { this.previousRunId = previousRunId; }

    public Map<String, String> getWorkflowRunProperties() { return workflowRunProperties; }
    public void setWorkflowRunProperties(Map<String, String> workflowRunProperties) { this.workflowRunProperties = workflowRunProperties; }

    public Instant getStartedOn() { return startedOn; }
    public void setStartedOn(Instant startedOn) { this.startedOn = startedOn; }

    public Instant getCompletedOn() { return completedOn; }
    public void setCompletedOn(Instant completedOn) { this.completedOn = completedOn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Map<String, Integer> getStatistics() { return statistics; }
    public void setStatistics(Map<String, Integer> statistics) { this.statistics = statistics; }

    public Map<String, Object> getGraph() { return graph; }
    public void setGraph(Map<String, Object> graph) { this.graph = graph; }
}
