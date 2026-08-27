package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Locale;

@RegisterForReflection
public enum ReplicationGroupStatus {
    CREATING, AVAILABLE, DELETING, CREATE_FAILED;

    /** The status string as AWS reports it, e.g. {@code create-failed}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
