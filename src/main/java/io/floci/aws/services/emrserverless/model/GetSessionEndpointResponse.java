package io.floci.aws.services.emrserverless.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class GetSessionEndpointResponse {
    private String applicationId;
    private String sessionId;
    private String endpoint;
    private String authToken;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant authTokenExpiresAt;

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }
    public Instant getAuthTokenExpiresAt() { return authTokenExpiresAt; }
    public void setAuthTokenExpiresAt(Instant authTokenExpiresAt) { this.authTokenExpiresAt = authTokenExpiresAt; }
}
