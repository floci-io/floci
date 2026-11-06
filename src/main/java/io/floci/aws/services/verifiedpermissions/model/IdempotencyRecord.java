package io.floci.aws.services.verifiedpermissions.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public record IdempotencyRecord(
        String operation,
        String token,
        String requestFingerprint,
        String resourceId,
        String policyStoreId,
        Instant createdAt) {}
