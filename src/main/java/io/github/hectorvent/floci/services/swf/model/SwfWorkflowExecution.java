package io.github.hectorvent.floci.services.swf.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A workflow execution: its effective configuration, history, and the in-flight
 * activities, timers, and child executions the decider can still act on.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SwfWorkflowExecution {

    /**
     * The region this execution lives in. SWF names are unique per region, so the region is
     * part of the execution's identity — it is what lets the same domain and workflowId exist
     * independently in two regions, and it is how Lambda invocations reach the right region.
     */
    private String region;
    private String domain;
    private String workflowId;
    private String runId;
    private String workflowTypeName;
    private String workflowTypeVersion;

    private String taskList;
    private String taskPriority;
    private String executionStartToCloseTimeout;
    private String taskStartToCloseTimeout;
    private String childPolicy;
    private String lambdaRole;
    private String input;
    private List<String> tagList = new ArrayList<>();

    private String executionStatus = SwfConstants.EXECUTION_STATUS_OPEN;
    private String closeStatus;
    private double startTimestamp;
    private Double closeTimestamp;
    private boolean cancelRequested;

    private String parentWorkflowId;
    private String parentRunId;
    private Long parentInitiatedEventId;
    /**
     * The parent's ChildWorkflowExecutionStarted event id. Distinct from
     * {@link #parentInitiatedEventId}: the live service reports the initiated id on
     * {@code initiatedEventId} and this one on {@code startedEventId} of every
     * ChildWorkflowExecution* event, and deciders correlate on both.
     */
    private Long parentStartedEventId;
    private String continuedExecutionRunId;

    private long nextEventId = 1;
    private List<SwfHistoryEvent> events = new ArrayList<>();

    /** Latest decision task, if one is scheduled or started. */
    private SwfDecisionTask decisionTask;
    /** True while a decision task is scheduled or started; SWF allows only one at a time. */
    private boolean decisionTaskOutstanding;
    /** Set when an event arrives while a decision task is already outstanding. */
    private boolean decisionNeeded;

    private Map<String, SwfActivityTask> activities = new LinkedHashMap<>();
    private Map<String, SwfTimer> timers = new LinkedHashMap<>();
    /** Child workflow runIds keyed by the initiating decision's workflowId. */
    private Map<String, String> childExecutions = new LinkedHashMap<>();

    private Double latestActivityTaskTimestamp;
    private String latestExecutionContext;

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
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

    public String getWorkflowTypeName() {
        return workflowTypeName;
    }

    public void setWorkflowTypeName(String workflowTypeName) {
        this.workflowTypeName = workflowTypeName;
    }

    public String getWorkflowTypeVersion() {
        return workflowTypeVersion;
    }

    public void setWorkflowTypeVersion(String workflowTypeVersion) {
        this.workflowTypeVersion = workflowTypeVersion;
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

    public String getExecutionStartToCloseTimeout() {
        return executionStartToCloseTimeout;
    }

    public void setExecutionStartToCloseTimeout(String executionStartToCloseTimeout) {
        this.executionStartToCloseTimeout = executionStartToCloseTimeout;
    }

    public String getTaskStartToCloseTimeout() {
        return taskStartToCloseTimeout;
    }

    public void setTaskStartToCloseTimeout(String taskStartToCloseTimeout) {
        this.taskStartToCloseTimeout = taskStartToCloseTimeout;
    }

    public String getChildPolicy() {
        return childPolicy;
    }

    public void setChildPolicy(String childPolicy) {
        this.childPolicy = childPolicy;
    }

    public String getLambdaRole() {
        return lambdaRole;
    }

    public void setLambdaRole(String lambdaRole) {
        this.lambdaRole = lambdaRole;
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public List<String> getTagList() {
        return tagList;
    }

    public void setTagList(List<String> tagList) {
        this.tagList = tagList != null ? tagList : new ArrayList<>();
    }

    public String getExecutionStatus() {
        return executionStatus;
    }

    public void setExecutionStatus(String executionStatus) {
        this.executionStatus = executionStatus;
    }

    public String getCloseStatus() {
        return closeStatus;
    }

    public void setCloseStatus(String closeStatus) {
        this.closeStatus = closeStatus;
    }

    public double getStartTimestamp() {
        return startTimestamp;
    }

    public void setStartTimestamp(double startTimestamp) {
        this.startTimestamp = startTimestamp;
    }

    public Double getCloseTimestamp() {
        return closeTimestamp;
    }

    public void setCloseTimestamp(Double closeTimestamp) {
        this.closeTimestamp = closeTimestamp;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public void setCancelRequested(boolean cancelRequested) {
        this.cancelRequested = cancelRequested;
    }

    public String getParentWorkflowId() {
        return parentWorkflowId;
    }

    public void setParentWorkflowId(String parentWorkflowId) {
        this.parentWorkflowId = parentWorkflowId;
    }

    public String getParentRunId() {
        return parentRunId;
    }

    public void setParentRunId(String parentRunId) {
        this.parentRunId = parentRunId;
    }

    public Long getParentInitiatedEventId() {
        return parentInitiatedEventId;
    }

    public void setParentInitiatedEventId(Long parentInitiatedEventId) {
        this.parentInitiatedEventId = parentInitiatedEventId;
    }

    public Long getParentStartedEventId() {
        return parentStartedEventId;
    }

    public void setParentStartedEventId(Long parentStartedEventId) {
        this.parentStartedEventId = parentStartedEventId;
    }

    public String getContinuedExecutionRunId() {
        return continuedExecutionRunId;
    }

    public void setContinuedExecutionRunId(String continuedExecutionRunId) {
        this.continuedExecutionRunId = continuedExecutionRunId;
    }

    public long getNextEventId() {
        return nextEventId;
    }

    public void setNextEventId(long nextEventId) {
        this.nextEventId = nextEventId;
    }

    public List<SwfHistoryEvent> getEvents() {
        return events;
    }

    public void setEvents(List<SwfHistoryEvent> events) {
        this.events = events != null ? events : new ArrayList<>();
    }

    public SwfDecisionTask getDecisionTask() {
        return decisionTask;
    }

    public void setDecisionTask(SwfDecisionTask decisionTask) {
        this.decisionTask = decisionTask;
    }

    public boolean isDecisionTaskOutstanding() {
        return decisionTaskOutstanding;
    }

    public void setDecisionTaskOutstanding(boolean decisionTaskOutstanding) {
        this.decisionTaskOutstanding = decisionTaskOutstanding;
    }

    public boolean isDecisionNeeded() {
        return decisionNeeded;
    }

    public void setDecisionNeeded(boolean decisionNeeded) {
        this.decisionNeeded = decisionNeeded;
    }

    public Map<String, SwfActivityTask> getActivities() {
        return activities;
    }

    public void setActivities(Map<String, SwfActivityTask> activities) {
        this.activities = activities != null ? activities : new LinkedHashMap<>();
    }

    public Map<String, SwfTimer> getTimers() {
        return timers;
    }

    public void setTimers(Map<String, SwfTimer> timers) {
        this.timers = timers != null ? timers : new LinkedHashMap<>();
    }

    public Map<String, String> getChildExecutions() {
        return childExecutions;
    }

    public void setChildExecutions(Map<String, String> childExecutions) {
        this.childExecutions = childExecutions != null ? childExecutions : new LinkedHashMap<>();
    }

    public Double getLatestActivityTaskTimestamp() {
        return latestActivityTaskTimestamp;
    }

    public void setLatestActivityTaskTimestamp(Double latestActivityTaskTimestamp) {
        this.latestActivityTaskTimestamp = latestActivityTaskTimestamp;
    }

    public String getLatestExecutionContext() {
        return latestExecutionContext;
    }

    public void setLatestExecutionContext(String latestExecutionContext) {
        this.latestExecutionContext = latestExecutionContext;
    }

    public boolean isOpen() {
        return SwfConstants.EXECUTION_STATUS_OPEN.equals(executionStatus);
    }

    public long allocateEventId() {
        return nextEventId++;
    }
}
