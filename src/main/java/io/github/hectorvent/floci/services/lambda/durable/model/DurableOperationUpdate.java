package io.github.hectorvent.floci.services.lambda.durable.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** One entry of a CheckpointDurableExecution {@code Updates} list, after parsing. */
@RegisterForReflection
public record DurableOperationUpdate(String id,
                                     String parentId,
                                     String name,
                                     DurableOperationType type,
                                     String subType,
                                     DurableOperationAction action,
                                     String payload,
                                     DurableErrorObject error,
                                     Integer nextAttemptDelaySeconds,
                                     Integer waitSeconds,
                                     Boolean replayChildren,
                                     Integer callbackTimeoutSeconds,
                                     Integer callbackHeartbeatTimeoutSeconds) {
}
