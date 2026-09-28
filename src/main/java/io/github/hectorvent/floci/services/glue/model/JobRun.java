package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobRun {
    @JsonProperty("Id")
    private String id;

    @JsonProperty("Attempt")
    private Integer attempt;

    @JsonProperty("PreviousRunId")
    private String previousRunId;

    @JsonProperty("TriggerName")
    private String triggerName;

    @JsonProperty("JobName")
    private String jobName;

    @JsonProperty("JobMode")
    private String jobMode;

    @JsonProperty("JobRunQueuingEnabled")
    private Boolean jobRunQueuingEnabled;

    @JsonProperty("StartedOn")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant startedOn;

    @JsonProperty("LastModifiedOn")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant lastModifiedOn;

    @JsonProperty("CompletedOn")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant completedOn;

    @JsonProperty("JobRunState")
    private String jobRunState;

    @JsonProperty("Arguments")
    private Map<String, String> arguments;

    @JsonProperty("ErrorMessage")
    private String errorMessage;

    @JsonProperty("AllocatedCapacity")
    private Integer allocatedCapacity;

    @JsonProperty("ExecutionTime")
    private Integer executionTime;

    @JsonProperty("Timeout")
    private Integer timeout;

    @JsonProperty("MaxCapacity")
    private Double maxCapacity;

    @JsonProperty("WorkerType")
    private String workerType;

    @JsonProperty("NumberOfWorkers")
    private Integer numberOfWorkers;

    @JsonProperty("SecurityConfiguration")
    private String securityConfiguration;

    @JsonProperty("LogGroupName")
    private String logGroupName;

    @JsonProperty("NotificationProperty")
    private NotificationProperty notificationProperty;

    @JsonProperty("GlueVersion")
    private String glueVersion;

    @JsonProperty("ExecutionClass")
    private String executionClass;

    public JobRun() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }

    public String getPreviousRunId() { return previousRunId; }
    public void setPreviousRunId(String previousRunId) { this.previousRunId = previousRunId; }

    public String getTriggerName() { return triggerName; }
    public void setTriggerName(String triggerName) { this.triggerName = triggerName; }

    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }

    public String getJobMode() { return jobMode; }
    public void setJobMode(String jobMode) { this.jobMode = jobMode; }

    public Boolean getJobRunQueuingEnabled() { return jobRunQueuingEnabled; }
    public void setJobRunQueuingEnabled(Boolean jobRunQueuingEnabled) { this.jobRunQueuingEnabled = jobRunQueuingEnabled; }

    public Instant getStartedOn() { return startedOn; }
    public void setStartedOn(Instant startedOn) { this.startedOn = startedOn; }

    public Instant getLastModifiedOn() { return lastModifiedOn; }
    public void setLastModifiedOn(Instant lastModifiedOn) { this.lastModifiedOn = lastModifiedOn; }

    public Instant getCompletedOn() { return completedOn; }
    public void setCompletedOn(Instant completedOn) { this.completedOn = completedOn; }

    public String getJobRunState() { return jobRunState; }
    public void setJobRunState(String jobRunState) { this.jobRunState = jobRunState; }

    public Map<String, String> getArguments() { return arguments; }
    public void setArguments(Map<String, String> arguments) { this.arguments = arguments; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Integer getAllocatedCapacity() { return allocatedCapacity; }
    public void setAllocatedCapacity(Integer allocatedCapacity) { this.allocatedCapacity = allocatedCapacity; }

    public Integer getExecutionTime() { return executionTime; }
    public void setExecutionTime(Integer executionTime) { this.executionTime = executionTime; }

    public Integer getTimeout() { return timeout; }
    public void setTimeout(Integer timeout) { this.timeout = timeout; }

    public Double getMaxCapacity() { return maxCapacity; }
    public void setMaxCapacity(Double maxCapacity) { this.maxCapacity = maxCapacity; }

    public String getWorkerType() { return workerType; }
    public void setWorkerType(String workerType) { this.workerType = workerType; }

    public Integer getNumberOfWorkers() { return numberOfWorkers; }
    public void setNumberOfWorkers(Integer numberOfWorkers) { this.numberOfWorkers = numberOfWorkers; }

    public String getSecurityConfiguration() { return securityConfiguration; }
    public void setSecurityConfiguration(String securityConfiguration) { this.securityConfiguration = securityConfiguration; }

    public String getLogGroupName() { return logGroupName; }
    public void setLogGroupName(String logGroupName) { this.logGroupName = logGroupName; }

    public NotificationProperty getNotificationProperty() { return notificationProperty; }
    public void setNotificationProperty(NotificationProperty notificationProperty) { this.notificationProperty = notificationProperty; }

    public String getGlueVersion() { return glueVersion; }
    public void setGlueVersion(String glueVersion) { this.glueVersion = glueVersion; }

    public String getExecutionClass() { return executionClass; }
    public void setExecutionClass(String executionClass) { this.executionClass = executionClass; }
}
