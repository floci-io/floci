package io.floci.aws.services.pipes.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum DesiredState {
    RUNNING,
    STOPPED
}
