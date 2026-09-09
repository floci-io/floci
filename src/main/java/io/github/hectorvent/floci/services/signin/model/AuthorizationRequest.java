package io.github.hectorvent.floci.services.signin.model;

import java.time.Instant;

public record AuthorizationRequest(String clientId, String codeChallenge, String redirectUri,
                                   String resource, String state, String accountId, Instant expiresAt) {
}
