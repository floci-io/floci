package io.github.hectorvent.floci.services.cognito.model;

import java.time.Instant;

/**
 * A browser's managed login session, which lets {@code /oauth2/authorize} skip the sign-in form.
 * {@code username} is the user's canonical username, not the alias they typed.
 */
public record CognitoManagedLoginSession(String userPoolId, String username, Instant expiresAt) {
}
