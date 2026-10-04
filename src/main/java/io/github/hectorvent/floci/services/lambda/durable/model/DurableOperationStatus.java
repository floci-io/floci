package io.github.hectorvent.floci.services.lambda.durable.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum DurableOperationStatus {
    STARTED, PENDING, READY, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, STOPPED;

    /** EndTimestamp is set for every status a durable operation cannot leave again. */
    public boolean isTerminal() {
        return this != STARTED && this != PENDING && this != READY;
    }
}
