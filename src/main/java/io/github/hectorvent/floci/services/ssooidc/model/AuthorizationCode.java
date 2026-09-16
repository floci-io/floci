package io.github.hectorvent.floci.services.ssooidc.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record AuthorizationCode(
        String code,
        String clientId,
        String redirectUri,
        String codeChallenge,
        long expiresAtEpochSeconds,
        String principalId
) {}
