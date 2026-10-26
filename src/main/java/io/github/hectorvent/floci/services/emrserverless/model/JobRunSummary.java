package io.github.hectorvent.floci.services.emrserverless.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/** One entry of ListJobRuns. Unlike JobRun, the run's id is {@code id}. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobRunSummary {
    private String applicationId;
    private String id;
    private String name;
    private String mode;
    private String arn;
    private String createdBy;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdAt;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant updatedAt;
    private String executionRole;
    private String state;
    private String stateDetails;
    private String releaseLabel;
    private String type;
    private Integer attempt;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant attemptCreatedAt;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant attemptUpdatedAt;

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getExecutionRole() { return executionRole; }
    public void setExecutionRole(String executionRole) { this.executionRole = executionRole; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getStateDetails() { return stateDetails; }
    public void setStateDetails(String stateDetails) { this.stateDetails = stateDetails; }
    public String getReleaseLabel() { return releaseLabel; }
    public void setReleaseLabel(String releaseLabel) { this.releaseLabel = releaseLabel; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }
    public Instant getAttemptCreatedAt() { return attemptCreatedAt; }
    public void setAttemptCreatedAt(Instant attemptCreatedAt) { this.attemptCreatedAt = attemptCreatedAt; }
    public Instant getAttemptUpdatedAt() { return attemptUpdatedAt; }
    public void setAttemptUpdatedAt(Instant attemptUpdatedAt) { this.attemptUpdatedAt = attemptUpdatedAt; }
}
