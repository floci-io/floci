package io.floci.aws.services.lambda.durable.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum DurableExecutionStatus {
    RUNNING, SUCCEEDED, FAILED, TIMED_OUT, STOPPED;

    public boolean isClosed() {
        return this != RUNNING;
    }
}
