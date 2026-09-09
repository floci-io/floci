package io.github.hectorvent.floci.services.swf.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An activity task from the moment it is scheduled by a decision until it closes.
 *
 * <p>Timeouts are stored as the resolved effective values (activity type defaults
 * already applied) so the timeout sweep does not have to re-resolve them.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SwfActivityTask {

    public static final String STATE_SCHEDULED = "SCHEDULED";
    public static final String STATE_STARTED = "STARTED";
    public static final String STATE_CLOSED = "CLOSED";

    private String taskToken;
    private String domain;
    private String workflowId;
    private String runId;
    private String activityId;
    private String activityTypeName;
    private String activityTypeVersion;
    private String taskList;
    private String taskPriority;
    private String input;
    private String control;

    private String scheduleToStartTimeout;
    private String scheduleToCloseTimeout;
    private String startToCloseTimeout;
    private String heartbeatTimeout;

    private long scheduledEventId;
    private Long startedEventId;
    private long decisionTaskCompletedEventId;

    private double scheduledTimestamp;
    private Double startedTimestamp;
    private Double lastHeartbeatTimestamp;

    private String state = STATE_SCHEDULED;
    private boolean cancelRequested;
    private Long latestCancelRequestedEventId;
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

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getActivityTypeName() {
        return activityTypeName;
    }

    public void setActivityTypeName(String activityTypeName) {
        this.activityTypeName = activityTypeName;
    }

    public String getActivityTypeVersion() {
        return activityTypeVersion;
    }

    public void setActivityTypeVersion(String activityTypeVersion) {
        this.activityTypeVersion = activityTypeVersion;
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

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public String getControl() {
        return control;
    }

    public void setControl(String control) {
        this.control = control;
    }

    public String getScheduleToStartTimeout() {
        return scheduleToStartTimeout;
    }

    public void setScheduleToStartTimeout(String scheduleToStartTimeout) {
        this.scheduleToStartTimeout = scheduleToStartTimeout;
    }

    public String getScheduleToCloseTimeout() {
        return scheduleToCloseTimeout;
    }

    public void setScheduleToCloseTimeout(String scheduleToCloseTimeout) {
        this.scheduleToCloseTimeout = scheduleToCloseTimeout;
    }

    public String getStartToCloseTimeout() {
        return startToCloseTimeout;
    }

    public void setStartToCloseTimeout(String startToCloseTimeout) {
        this.startToCloseTimeout = startToCloseTimeout;
    }

    public String getHeartbeatTimeout() {
        return heartbeatTimeout;
    }

    public void setHeartbeatTimeout(String heartbeatTimeout) {
        this.heartbeatTimeout = heartbeatTimeout;
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

    public long getDecisionTaskCompletedEventId() {
        return decisionTaskCompletedEventId;
    }

    public void setDecisionTaskCompletedEventId(long decisionTaskCompletedEventId) {
        this.decisionTaskCompletedEventId = decisionTaskCompletedEventId;
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

    public Double getLastHeartbeatTimestamp() {
        return lastHeartbeatTimestamp;
    }

    public void setLastHeartbeatTimestamp(Double lastHeartbeatTimestamp) {
        this.lastHeartbeatTimestamp = lastHeartbeatTimestamp;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public void setCancelRequested(boolean cancelRequested) {
        this.cancelRequested = cancelRequested;
    }

    public Long getLatestCancelRequestedEventId() {
        return latestCancelRequestedEventId;
    }

    public void setLatestCancelRequestedEventId(Long latestCancelRequestedEventId) {
        this.latestCancelRequestedEventId = latestCancelRequestedEventId;
    }

    public String getIdentity() {
        return identity;
    }

    public void setIdentity(String identity) {
        this.identity = identity;
    }

    public boolean isOpen() {
        return !STATE_CLOSED.equals(state);
    }
}
