package io.github.hectorvent.floci.services.cognito.verification;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Stored representation of a Cognito verification code (signup confirmation,
 * password reset, MFA SMS, attribute verification). The actual code is hashed
 * with a per-code salt so dumps and storage snapshots cannot reveal valid codes.
 */
public final class VerificationCode {

    public enum Purpose {
        SIGNUP_CONFIRMATION,
        PASSWORD_RESET,
        SMS_MFA,
        ATTRIBUTE_VERIFICATION
    }

    private final String userPoolId;
    private final String username;
    private final Purpose purpose;
    private final String codeHash;
    private final String salt;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private int attemptsRemaining;
    private boolean consumed;

    @JsonCreator
    public VerificationCode(
            @JsonProperty("userPoolId") String userPoolId,
            @JsonProperty("username") String username,
            @JsonProperty("purpose") Purpose purpose,
            @JsonProperty("codeHash") String codeHash,
            @JsonProperty("salt") String salt,
            @JsonProperty("issuedAt") Instant issuedAt,
            @JsonProperty("expiresAt") Instant expiresAt,
            @JsonProperty("attemptsRemaining") int attemptsRemaining,
            @JsonProperty("consumed") boolean consumed) {
        this.userPoolId = userPoolId;
        this.username = username;
        this.purpose = purpose;
        this.codeHash = codeHash;
        this.salt = salt;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.attemptsRemaining = attemptsRemaining;
        this.consumed = consumed;
    }

    public VerificationCode(String userPoolId, String username, Purpose purpose,
                            String codeHash, String salt, Instant issuedAt,
                            Instant expiresAt, int attemptsRemaining) {
        this(userPoolId, username, purpose, codeHash, salt, issuedAt, expiresAt, attemptsRemaining, false);
    }

    public String getUserPoolId() { return userPoolId; }
    public String getUsername() { return username; }
    public Purpose getPurpose() { return purpose; }
    public String getCodeHash() { return codeHash; }
    public String getSalt() { return salt; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getAttemptsRemaining() { return attemptsRemaining; }
    public boolean isConsumed() { return consumed; }

    public void decrementAttempts() { this.attemptsRemaining--; }
    public void markConsumed() { this.consumed = true; }

    public boolean isExpired(Instant now) { return now.isAfter(expiresAt); }

    public static String storageKey(String userPoolId, String username, Purpose purpose) {
        // Assumes username does not contain ':'. Cognito usernames are emails,
        // phone numbers, or UUID-like sub strings in typical usage — none of
        // which contain ':'. External-IdP federated usernames could in theory,
        // but Cognito sanitizes them at sign-up.
        return userPoolId + ":" + username + ":" + purpose.name();
    }
}
