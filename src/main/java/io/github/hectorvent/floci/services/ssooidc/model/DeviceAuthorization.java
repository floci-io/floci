package io.github.hectorvent.floci.services.ssooidc.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record DeviceAuthorization(
        String deviceCode,
        String userCode,
        String clientId,
        String startUrl,
        long expiresAtEpochSeconds,
        int intervalSeconds,
        boolean authorized,
        long lastPollAtEpochMillis,
        String principalId
) {}
