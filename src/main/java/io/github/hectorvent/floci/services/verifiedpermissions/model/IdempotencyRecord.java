package io.github.hectorvent.floci.services.verifiedpermissions.model;

import java.time.Instant;

public record IdempotencyRecord(
        String operation,
        String token,
        String requestFingerprint,
        String resourceId,
        String policyStoreId,
        Instant createdAt) {}
