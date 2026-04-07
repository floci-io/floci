package io.github.hectorvent.floci.services.ecr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * Short-lived credential bundle returned by GetAuthorizationToken.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonECR/latest/APIReference/API_AuthorizationData.html">AWS ECR AuthorizationData</a>
 */
@RegisterForReflection
public class AuthorizationData {
    private String authorizationToken;
    private Instant expiresAt;
    private String proxyEndpoint;

    public AuthorizationData() {}

    public AuthorizationData(String authorizationToken, Instant expiresAt, String proxyEndpoint) {
        this.authorizationToken = authorizationToken;
        this.expiresAt = expiresAt;
        this.proxyEndpoint = proxyEndpoint;
    }

    public String getAuthorizationToken() { return authorizationToken; }
    public void setAuthorizationToken(String authorizationToken) { this.authorizationToken = authorizationToken; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public String getProxyEndpoint() { return proxyEndpoint; }
    public void setProxyEndpoint(String proxyEndpoint) { this.proxyEndpoint = proxyEndpoint; }
}
