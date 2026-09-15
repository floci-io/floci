package io.github.hectorvent.floci.services.cognito.model;

import java.time.Instant;
import java.util.List;

public record CognitoAuthorizationCode(
        String userPoolId,
        String clientId,
        String userId,
        String redirectUri,
        List<String> scopes,
        Instant expiresAt) {

    public CognitoAuthorizationCode {
        scopes = List.copyOf(scopes);
    }
}
