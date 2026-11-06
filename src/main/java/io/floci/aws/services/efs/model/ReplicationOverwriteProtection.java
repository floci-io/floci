package io.floci.aws.services.efs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum ReplicationOverwriteProtection {
    ENABLED,
    DISABLED,
    REPLICATING
}