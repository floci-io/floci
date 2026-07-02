package io.github.hectorvent.floci.services.pipes.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum DesiredState {
    RUNNING,
    STOPPED
}
