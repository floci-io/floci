package io.floci.aws.services.mwaa.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum EnvironmentStatus {
    CREATING, AVAILABLE, UPDATING, DELETING, DELETED, CREATE_FAILED, UPDATE_FAILED, UNAVAILABLE
}
