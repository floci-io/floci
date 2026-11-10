package io.floci.aws.services.eventbridge.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum ReplayState {
    STARTING, RUNNING, CANCELLING, COMPLETED, CANCELLED, FAILED
}
