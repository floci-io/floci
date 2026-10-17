package io.github.hectorvent.floci.services.cognito.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record CognitoAuthorizationTransaction(
        String userPoolId,
        String clientId,
        String redirectUri,
        List<String> scopes,
        String nonce,
        String providerName,
        String relyingPartyState,
        String codeChallenge,
        Map<String, String> providerDetails,
        Instant expiresAt) {

    public CognitoAuthorizationTransaction {
        scopes = List.copyOf(scopes);
        providerDetails = providerDetails != null ? Map.copyOf(providerDetails) : Map.of();
    }

    public CognitoAuthorizationTransaction(
            String userPoolId,
            String clientId,
            String redirectUri,
            List<String> scopes,
            String nonce,
            String providerName,
            String relyingPartyState,
            String codeChallenge,
            Instant expiresAt) {
        this(userPoolId, clientId, redirectUri, scopes, nonce, providerName, relyingPartyState, codeChallenge,
                Map.of(), expiresAt);
    }
}
