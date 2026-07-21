package io.github.hectorvent.floci.services.cognito.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Information about a revoked token for tracking and validation purposes.
 * Used to implement AWS Cognito AdminUserGlobalSignOut token revocation behavior.
 */
@RegisterForReflection
public class RevokedTokenInfo {
    
    private final String jti;
    private final String tokenType;
    private final String username;
    private final String userPoolId;
    private final long revokedAt;
    private final long expiresAt;

    @JsonCreator
    public RevokedTokenInfo(
            @JsonProperty("jti") String jti,
            @JsonProperty("tokenType") String tokenType,
            @JsonProperty("username") String username,
            @JsonProperty("userPoolId") String userPoolId,
            @JsonProperty("revokedAt") long revokedAt,
            @JsonProperty("expiresAt") long expiresAt) {
        this.jti = jti;
        this.tokenType = tokenType;
        this.username = username;
        this.userPoolId = userPoolId;
        this.revokedAt = revokedAt;
        this.expiresAt = expiresAt;
    }

    public String getJti() {
        return jti;
    }

    public String getTokenType() {
        return tokenType;
    }

    public String getUsername() {
        return username;
    }

    public String getUserPoolId() {
        return userPoolId;
    }

    public long getRevokedAt() {
        return revokedAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    /**
     * Check if this revocation record has expired and can be cleaned up.
     */
    public boolean isExpired() {
        return System.currentTimeMillis() / 1000L > expiresAt;
    }

    @Override
    public String toString() {
        return "RevokedTokenInfo{" +
                "jti='" + jti + '\'' +
                ", tokenType='" + tokenType + '\'' +
                ", username='" + username + '\'' +
                ", userPoolId='" + userPoolId + '\'' +
                ", revokedAt=" + revokedAt +
                ", expiresAt=" + expiresAt +
                '}';
    }
}