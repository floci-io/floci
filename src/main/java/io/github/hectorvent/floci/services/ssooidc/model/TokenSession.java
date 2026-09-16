package io.github.hectorvent.floci.services.ssooidc.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record TokenSession(
        String accessToken,
        String refreshToken,
        String clientId,
        List<String> scopes,
        long accessTokenExpiresAtEpochSeconds,
        long refreshTokenExpiresAtEpochSeconds,
        String principalId
) {
    public TokenSession {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }
}
