package io.github.hectorvent.floci.services.scheduler.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * EventBridge Scheduler {@code Target.SqsParameters}. Carries the
 * {@code MessageGroupId} required when the target is a FIFO SQS queue.
 */
@RegisterForReflection
public class SqsParameters {

    private String messageGroupId;

    public SqsParameters() {}

    public SqsParameters(String messageGroupId) {
        this.messageGroupId = messageGroupId;
    }

    public String getMessageGroupId() { return messageGroupId; }
    public void setMessageGroupId(String messageGroupId) { this.messageGroupId = messageGroupId; }
}
