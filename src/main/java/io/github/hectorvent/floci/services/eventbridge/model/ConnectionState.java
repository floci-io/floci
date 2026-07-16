package io.github.hectorvent.floci.services.eventbridge.model;

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
