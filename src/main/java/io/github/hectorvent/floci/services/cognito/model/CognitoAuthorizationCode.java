package io.github.hectorvent.floci.services.cognito.model;

import java.time.Instant;
import java.util.List;

/**
 * A one-time authorization code. {@code nonce} and {@code codeChallenge} are the authorization
 * request's values, or null when it sent none; the token endpoint puts the nonce in the ID token
 * and requires a matching {@code code_verifier} when there is a challenge.
 */
public record CognitoAuthorizationCode(
        String userPoolId,
        String clientId,
        String userId,
        String redirectUri,
        List<String> scopes,
        String nonce,
        String codeChallenge,
        Instant expiresAt) {

    public CognitoAuthorizationCode {
        scopes = List.copyOf(scopes);
    }
}
