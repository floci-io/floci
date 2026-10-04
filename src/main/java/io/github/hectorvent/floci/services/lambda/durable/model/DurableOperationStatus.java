package io.github.hectorvent.floci.services.lambda.durable.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum DurableOperationStatus {
    STARTED, PENDING, READY, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, STOPPED
}
