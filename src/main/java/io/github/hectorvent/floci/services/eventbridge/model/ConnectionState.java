package io.github.hectorvent.floci.services.eventbridge.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum ConnectionState {
    CREATING,
    UPDATING,
    DELETING,
    AUTHORIZED,
    DEAUTHORIZED,
    AUTHORIZING,
    DEAUTHORIZING,
    ACTIVE,
    FAILED_CONNECTIVITY
}
