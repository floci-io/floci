package io.github.hectorvent.floci.services.ssoadmin.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record InstanceUpdateState(
        String name,
        boolean permissionSetsEnabled,
        String keyType,
        String kmsKeyArn,
        String encryptionStatus,
        String encryptionStatusReason) {}
