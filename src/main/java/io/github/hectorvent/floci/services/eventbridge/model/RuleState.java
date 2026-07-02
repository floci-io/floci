package io.github.hectorvent.floci.services.eventbridge.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum RuleState {
    ENABLED,
    DISABLED
}
