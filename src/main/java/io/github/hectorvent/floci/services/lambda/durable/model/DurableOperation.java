package io.github.hectorvent.floci.services.lambda.durable.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One checkpointed operation of a durable execution. A single flat class serves every operation
 * type. The wire mapping emits only the details block that belongs to {@link #getType()}.
 * Timestamps are epoch millis.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DurableOperation {

    private String id;
    private String parentId;
    private String name;
    private DurableOperationType type;
    private String subType;
    private DurableOperationStatus status;
    private long startTimestamp;
    private Long endTimestamp;
    private String inputPayload;
    private Boolean replayChildren;
    private String result;
    private DurableErrorObject error;
    private Integer attempt;
    private Long nextAttemptTimestamp;
    private Long scheduledEndTimestamp;
    private String callbackId;
    private Integer heartbeatTimeoutSeconds;
    private Long callbackDeadline;
    /** Moves forward on every SendDurableExecutionCallbackHeartbeat. */
    private Long heartbeatDeadline;
    /** Position in the execution's change log. The handler is only shown operations newer than it has seen. */
    private long changeSequence;

    public DurableOperation() {
    }

    public DurableOperation copy() {
        DurableOperation copy = new DurableOperation();
        copy.id = id;
        copy.parentId = parentId;
        copy.name = name;
        copy.type = type;
        copy.subType = subType;
        copy.status = status;
        copy.startTimestamp = startTimestamp;
        copy.endTimestamp = endTimestamp;
        copy.inputPayload = inputPayload;
        copy.replayChildren = replayChildren;
        copy.result = result;
        copy.error = error;
        copy.attempt = attempt;
        copy.nextAttemptTimestamp = nextAttemptTimestamp;
        copy.scheduledEndTimestamp = scheduledEndTimestamp;
        copy.callbackId = callbackId;
        copy.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
        copy.callbackDeadline = callbackDeadline;
        copy.heartbeatDeadline = heartbeatDeadline;
        copy.changeSequence = changeSequence;
        return copy;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public DurableOperationType getType() { return type; }
    public void setType(DurableOperationType type) { this.type = type; }

    public String getSubType() { return subType; }
    public void setSubType(String subType) { this.subType = subType; }

    public DurableOperationStatus getStatus() { return status; }
    public void setStatus(DurableOperationStatus status) { this.status = status; }

    public long getStartTimestamp() { return startTimestamp; }
    public void setStartTimestamp(long startTimestamp) { this.startTimestamp = startTimestamp; }

    public Long getEndTimestamp() { return endTimestamp; }
    public void setEndTimestamp(Long endTimestamp) { this.endTimestamp = endTimestamp; }

    public String getInputPayload() { return inputPayload; }
    public void setInputPayload(String inputPayload) { this.inputPayload = inputPayload; }

    public Boolean getReplayChildren() { return replayChildren; }
    public void setReplayChildren(Boolean replayChildren) { this.replayChildren = replayChildren; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public DurableErrorObject getError() { return error; }
    public void setError(DurableErrorObject error) { this.error = error; }

    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }

    public Long getNextAttemptTimestamp() { return nextAttemptTimestamp; }
    public void setNextAttemptTimestamp(Long nextAttemptTimestamp) { this.nextAttemptTimestamp = nextAttemptTimestamp; }

    public Long getScheduledEndTimestamp() { return scheduledEndTimestamp; }
    public void setScheduledEndTimestamp(Long scheduledEndTimestamp) { this.scheduledEndTimestamp = scheduledEndTimestamp; }

    public String getCallbackId() { return callbackId; }
    public void setCallbackId(String callbackId) { this.callbackId = callbackId; }

    public Integer getHeartbeatTimeoutSeconds() { return heartbeatTimeoutSeconds; }
    public void setHeartbeatTimeoutSeconds(Integer heartbeatTimeoutSeconds) {
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }

    public Long getCallbackDeadline() { return callbackDeadline; }
    public void setCallbackDeadline(Long callbackDeadline) { this.callbackDeadline = callbackDeadline; }

    public Long getHeartbeatDeadline() { return heartbeatDeadline; }
    public void setHeartbeatDeadline(Long heartbeatDeadline) { this.heartbeatDeadline = heartbeatDeadline; }

    public long getChangeSequence() { return changeSequence; }
    public void setChangeSequence(long changeSequence) { this.changeSequence = changeSequence; }
}
