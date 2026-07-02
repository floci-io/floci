package io.github.hectorvent.floci.services.eks.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum ClusterStatus {
    CREATING, ACTIVE, DELETING, FAILED, UPDATING, DEGRADED
}
