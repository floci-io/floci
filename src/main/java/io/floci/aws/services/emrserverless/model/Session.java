package io.floci.aws.services.emrserverless.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.floci.aws.services.emrserverless.SparkConnectSession;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

/** GetSession's session. Timestamps go out as epoch seconds, the restJson1 default. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Session implements SparkConnectSession {
    private String applicationId;
    private String sessionId;
    private String arn;
    private String name;
    private String state;
    private String stateDetails;
    private String releaseLabel;
    private String executionRoleArn;
    private String createdBy;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdAt;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant updatedAt;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant startedAt;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant endedAt;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant idleSince;
    private Map<String, Object> configurationOverrides;
    private NetworkConfiguration networkConfiguration;
    private Long idleTimeoutMinutes;
    private Map<String, String> tags;
    private Long totalExecutionDurationSeconds;

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getStateDetails() { return stateDetails; }
    public void setStateDetails(String stateDetails) { this.stateDetails = stateDetails; }
    public String getReleaseLabel() { return releaseLabel; }
    public void setReleaseLabel(String releaseLabel) { this.releaseLabel = releaseLabel; }
    public String getExecutionRoleArn() { return executionRoleArn; }
    public void setExecutionRoleArn(String executionRoleArn) { this.executionRoleArn = executionRoleArn; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public Instant getIdleSince() { return idleSince; }
    public void setIdleSince(Instant idleSince) { this.idleSince = idleSince; }
    public Map<String, Object> getConfigurationOverrides() { return configurationOverrides; }
    public void setConfigurationOverrides(Map<String, Object> configurationOverrides) {
        this.configurationOverrides = configurationOverrides;
    }
    public NetworkConfiguration getNetworkConfiguration() { return networkConfiguration; }
    public void setNetworkConfiguration(NetworkConfiguration networkConfiguration) {
        this.networkConfiguration = networkConfiguration;
    }
    public Long getIdleTimeoutMinutes() { return idleTimeoutMinutes; }
    public void setIdleTimeoutMinutes(Long idleTimeoutMinutes) { this.idleTimeoutMinutes = idleTimeoutMinutes; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    public Long getTotalExecutionDurationSeconds() { return totalExecutionDurationSeconds; }
    public void setTotalExecutionDurationSeconds(Long totalExecutionDurationSeconds) {
        this.totalExecutionDurationSeconds = totalExecutionDurationSeconds;
    }
}
