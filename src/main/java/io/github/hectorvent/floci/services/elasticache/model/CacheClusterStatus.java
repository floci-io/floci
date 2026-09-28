package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Locale;

@RegisterForReflection
public enum CacheClusterStatus {
    AVAILABLE, CREATING, DELETING, RESTORE_FAILED;

    /** The status string as AWS reports it, e.g. {@code restore-failed}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
