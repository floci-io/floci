package io.github.hectorvent.floci.services.swf.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A decision task. Scheduled whenever the execution needs a decision and
 * consumed by PollForDecisionTask; only one may be outstanding per execution.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SwfDecisionTask {

    public static final String STATE_SCHEDULED = "SCHEDULED";
    public static final String STATE_STARTED = "STARTED";

    private String taskToken;
    private String domain;
    private String workflowId;
    private String runId;
    private String taskList;
    private String taskPriority;
    private String startToCloseTimeout;

    private long scheduledEventId;
    private Long startedEventId;
    private long previousStartedEventId;

    private double scheduledTimestamp;
    private Double startedTimestamp;

    private String state = STATE_SCHEDULED;
    private String identity;

    public String getTaskToken() {
        return taskToken;
    }

    public void setTaskToken(String taskToken) {
        this.taskToken = taskToken;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getTaskList() {
        return taskList;
    }

    public void setTaskList(String taskList) {
        this.taskList = taskList;
    }

    public String getTaskPriority() {
        return taskPriority;
    }

    public void setTaskPriority(String taskPriority) {
        this.taskPriority = taskPriority;
    }

    public String getStartToCloseTimeout() {
        return startToCloseTimeout;
    }

    public void setStartToCloseTimeout(String startToCloseTimeout) {
        this.startToCloseTimeout = startToCloseTimeout;
    }

    public long getScheduledEventId() {
        return scheduledEventId;
    }

    public void setScheduledEventId(long scheduledEventId) {
        this.scheduledEventId = scheduledEventId;
    }

    public Long getStartedEventId() {
        return startedEventId;
    }

    public void setStartedEventId(Long startedEventId) {
        this.startedEventId = startedEventId;
    }

    public long getPreviousStartedEventId() {
        return previousStartedEventId;
    }

    public void setPreviousStartedEventId(long previousStartedEventId) {
        this.previousStartedEventId = previousStartedEventId;
    }

    public double getScheduledTimestamp() {
        return scheduledTimestamp;
    }

    public void setScheduledTimestamp(double scheduledTimestamp) {
        this.scheduledTimestamp = scheduledTimestamp;
    }

    public Double getStartedTimestamp() {
        return startedTimestamp;
    }

    public void setStartedTimestamp(Double startedTimestamp) {
        this.startedTimestamp = startedTimestamp;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getIdentity() {
        return identity;
    }

    public void setIdentity(String identity) {
        this.identity = identity;
    }
}
