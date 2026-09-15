package io.github.hectorvent.floci.services.cognito.model;

import java.time.Instant;
import java.util.List;

public record CognitoAuthorizationTransaction(
        String userPoolId,
        String clientId,
        String redirectUri,
        List<String> scopes,
        String nonce,
        String providerName,
        Instant expiresAt) {

    public CognitoAuthorizationTransaction {
        scopes = List.copyOf(scopes);
    }
}
