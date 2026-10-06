package io.github.hectorvent.floci.services.lambda.durable.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A durable execution with its checkpointed operations and history. One stored object per
 * execution, so a checkpoint persists operations, history and the invocation lane together.
 * Timestamps are epoch millis.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DurableExecution {

    private String executionArn;
    /** Last ARN segment and the id of the EXECUTION operation. The SDK finds that operation by it. */
    private String executionId;
    private String name;
    private String accountId;
    private String region;
    private String functionName;
    /** Version qualified, as reported in FunctionArn. */
    private String functionArn;
    private String version;
    private DurableExecutionStatus status = DurableExecutionStatus.RUNNING;
    private long startTimestamp;
    private Long endTimestamp;
    private String inputPayload;
    private String result;
    private DurableErrorObject error;
    private int executionTimeoutSeconds;
    private int retentionPeriodInDays;
    private Long executionDeadline;
    private Long retentionDeadline;
    private int maxResultBytes;
    /** Creation order. The EXECUTION operation is first. */
    private LinkedHashMap<String, DurableOperation> operations = new LinkedHashMap<>();
    private List<DurableHistoryEvent> history = new ArrayList<>();
    private long changeSequence;
    /** Change position the handler has been shown. NewExecutionState carries everything newer. */
    private long seenSequence;
    private long checkpointSequence;
    private String currentInvocationId;
    private boolean reinvokeRequested;
    private int consecutiveInvocationFailures;
    private Long nextInvocationAttemptAt;
    private DurableCheckpointReplay lastCheckpoint;

    public DurableExecution() {
    }

    @JsonIgnore
    public boolean isClosed() {
        return status.isClosed();
    }

    @JsonIgnore
    public boolean hasUnseenChanges() {
        return changeSequence > seenSequence;
    }

    /** True while a wait, a step retry or a callback is still pending. */
    @JsonIgnore
    public boolean hasPendingOperations() {
        for (DurableOperation operation : operations.values()) {
            DurableOperationStatus operationStatus = operation.getStatus();
            switch (operation.getType()) {
                case WAIT -> {
                    if (operationStatus == DurableOperationStatus.STARTED) {
                        return true;
                    }
                }
                case STEP -> {
                    if (operationStatus == DurableOperationStatus.PENDING
                            || operationStatus == DurableOperationStatus.READY) {
                        return true;
                    }
                }
                case CALLBACK -> {
                    if (operationStatus == DurableOperationStatus.STARTED) {
                        return true;
                    }
                }
                default -> {
                }
            }
        }
        return false;
    }

    public long nextChangeSequence() {
        changeSequence++;
        return changeSequence;
    }

    public DurableHistoryEvent appendEvent(String eventType, long timestamp) {
        DurableHistoryEvent event = new DurableHistoryEvent();
        event.setEventId(history.size() + 1L);
        event.setEventType(eventType);
        event.setEventTimestamp(timestamp);
        history.add(event);
        return event;
    }

    public String getExecutionArn() { return executionArn; }
    public void setExecutionArn(String executionArn) { this.executionArn = executionArn; }

    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getFunctionName() { return functionName; }
    public void setFunctionName(String functionName) { this.functionName = functionName; }

    public String getFunctionArn() { return functionArn; }
    public void setFunctionArn(String functionArn) { this.functionArn = functionArn; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public DurableExecutionStatus getStatus() { return status; }
    public void setStatus(DurableExecutionStatus status) { this.status = status; }

    public long getStartTimestamp() { return startTimestamp; }
    public void setStartTimestamp(long startTimestamp) { this.startTimestamp = startTimestamp; }

    public Long getEndTimestamp() { return endTimestamp; }
    public void setEndTimestamp(Long endTimestamp) { this.endTimestamp = endTimestamp; }

    public String getInputPayload() { return inputPayload; }
    public void setInputPayload(String inputPayload) { this.inputPayload = inputPayload; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public DurableErrorObject getError() { return error; }
    public void setError(DurableErrorObject error) { this.error = error; }

    public int getExecutionTimeoutSeconds() { return executionTimeoutSeconds; }
    public void setExecutionTimeoutSeconds(int executionTimeoutSeconds) {
        this.executionTimeoutSeconds = executionTimeoutSeconds;
    }

    public int getRetentionPeriodInDays() { return retentionPeriodInDays; }
    public void setRetentionPeriodInDays(int retentionPeriodInDays) { this.retentionPeriodInDays = retentionPeriodInDays; }

    public Long getExecutionDeadline() { return executionDeadline; }
    public void setExecutionDeadline(Long executionDeadline) { this.executionDeadline = executionDeadline; }

    public Long getRetentionDeadline() { return retentionDeadline; }
    public void setRetentionDeadline(Long retentionDeadline) { this.retentionDeadline = retentionDeadline; }

    public int getMaxResultBytes() { return maxResultBytes; }
    public void setMaxResultBytes(int maxResultBytes) { this.maxResultBytes = maxResultBytes; }

    public LinkedHashMap<String, DurableOperation> getOperations() { return operations; }
    public void setOperations(LinkedHashMap<String, DurableOperation> operations) { this.operations = operations; }

    public List<DurableHistoryEvent> getHistory() { return history; }
    public void setHistory(List<DurableHistoryEvent> history) { this.history = history; }

    public long getChangeSequence() { return changeSequence; }
    public void setChangeSequence(long changeSequence) { this.changeSequence = changeSequence; }

    public long getSeenSequence() { return seenSequence; }
    public void setSeenSequence(long seenSequence) { this.seenSequence = seenSequence; }

    public long getCheckpointSequence() { return checkpointSequence; }
    public void setCheckpointSequence(long checkpointSequence) { this.checkpointSequence = checkpointSequence; }

    public String getCurrentInvocationId() { return currentInvocationId; }
    public void setCurrentInvocationId(String currentInvocationId) { this.currentInvocationId = currentInvocationId; }

    public boolean isReinvokeRequested() { return reinvokeRequested; }
    public void setReinvokeRequested(boolean reinvokeRequested) { this.reinvokeRequested = reinvokeRequested; }

    public int getConsecutiveInvocationFailures() { return consecutiveInvocationFailures; }
    public void setConsecutiveInvocationFailures(int consecutiveInvocationFailures) {
        this.consecutiveInvocationFailures = consecutiveInvocationFailures;
    }

    public Long getNextInvocationAttemptAt() { return nextInvocationAttemptAt; }
    public void setNextInvocationAttemptAt(Long nextInvocationAttemptAt) {
        this.nextInvocationAttemptAt = nextInvocationAttemptAt;
    }

    public DurableCheckpointReplay getLastCheckpoint() { return lastCheckpoint; }
    public void setLastCheckpoint(DurableCheckpointReplay lastCheckpoint) { this.lastCheckpoint = lastCheckpoint; }
}
