package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum DbInstanceStatus {
    CREATING, AVAILABLE, DELETING, REBOOTING, MODIFYING
}
