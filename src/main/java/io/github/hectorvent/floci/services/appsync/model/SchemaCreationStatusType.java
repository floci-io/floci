package io.github.hectorvent.floci.services.appsync.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum SchemaCreationStatusType {
    PROCESSING,
    ACTIVE,
    DELETING,
    FAILED,
    SUCCESS,
    NOT_APPLICABLE
}
