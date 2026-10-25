package io.github.hectorvent.floci.services.emrserverless.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

/** GetJobRun's jobRun. Timestamps go out as epoch seconds, the restJson1 default. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobRun {
    private String applicationId;
    private String jobRunId;
    private String name;
    private String arn;
    private String createdBy;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdAt;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant updatedAt;
    private String executionRole;
    private Map<String, Object> executionIamPolicy;
    private String state;
    private String stateDetails;
    private String releaseLabel;
    private Map<String, Object> configurationOverrides;
    private JobDriver jobDriver;
    private Map<String, String> tags;
    private NetworkConfiguration networkConfiguration;
    private Integer totalExecutionDurationSeconds;
    private Long executionTimeoutMinutes;
    private String mode;
    private Map<String, Object> retryPolicy;
    private Integer attempt;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant attemptCreatedAt;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant attemptUpdatedAt;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant startedAt;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant endedAt;

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getJobRunId() { return jobRunId; }
    public void setJobRunId(String jobRunId) { this.jobRunId = jobRunId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
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
    public Map<String, Object> getExecutionIamPolicy() { return executionIamPolicy; }
    public void setExecutionIamPolicy(Map<String, Object> executionIamPolicy) { this.executionIamPolicy = executionIamPolicy; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getStateDetails() { return stateDetails; }
    public void setStateDetails(String stateDetails) { this.stateDetails = stateDetails; }
    public String getReleaseLabel() { return releaseLabel; }
    public void setReleaseLabel(String releaseLabel) { this.releaseLabel = releaseLabel; }
    public Map<String, Object> getConfigurationOverrides() { return configurationOverrides; }
    public void setConfigurationOverrides(Map<String, Object> configurationOverrides) { this.configurationOverrides = configurationOverrides; }
    public JobDriver getJobDriver() { return jobDriver; }
    public void setJobDriver(JobDriver jobDriver) { this.jobDriver = jobDriver; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    public NetworkConfiguration getNetworkConfiguration() { return networkConfiguration; }
    public void setNetworkConfiguration(NetworkConfiguration networkConfiguration) { this.networkConfiguration = networkConfiguration; }
    public Integer getTotalExecutionDurationSeconds() { return totalExecutionDurationSeconds; }
    public void setTotalExecutionDurationSeconds(Integer totalExecutionDurationSeconds) { this.totalExecutionDurationSeconds = totalExecutionDurationSeconds; }
    public Long getExecutionTimeoutMinutes() { return executionTimeoutMinutes; }
    public void setExecutionTimeoutMinutes(Long executionTimeoutMinutes) { this.executionTimeoutMinutes = executionTimeoutMinutes; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public Map<String, Object> getRetryPolicy() { return retryPolicy; }
    public void setRetryPolicy(Map<String, Object> retryPolicy) { this.retryPolicy = retryPolicy; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }
    public Instant getAttemptCreatedAt() { return attemptCreatedAt; }
    public void setAttemptCreatedAt(Instant attemptCreatedAt) { this.attemptCreatedAt = attemptCreatedAt; }
    public Instant getAttemptUpdatedAt() { return attemptUpdatedAt; }
    public void setAttemptUpdatedAt(Instant attemptUpdatedAt) { this.attemptUpdatedAt = attemptUpdatedAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
}
