package io.github.hectorvent.floci.services.cognito.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserPoolClient {
    private String clientId;
    private String userPoolId;
    private String clientName;
    private String clientSecret;
    private boolean generateSecret;
    private boolean allowedOAuthFlowsUserPoolClient;
    private List<String> allowedOAuthFlows = new ArrayList<>();
    private List<String> allowedOAuthScopes = new ArrayList<>();
    private long creationDate;
    private long lastModifiedDate;

    public UserPoolClient() {
        long now = System.currentTimeMillis() / 1000L;
        this.creationDate = now;
        this.lastModifiedDate = now;
    }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getUserPoolId() { return userPoolId; }
    public void setUserPoolId(String userPoolId) { this.userPoolId = userPoolId; }

    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

    public boolean isGenerateSecret() { return generateSecret; }
    public void setGenerateSecret(boolean generateSecret) { this.generateSecret = generateSecret; }

    public boolean isAllowedOAuthFlowsUserPoolClient() { return allowedOAuthFlowsUserPoolClient; }
    public void setAllowedOAuthFlowsUserPoolClient(boolean allowedOAuthFlowsUserPoolClient) {
        this.allowedOAuthFlowsUserPoolClient = allowedOAuthFlowsUserPoolClient;
    }

    public List<String> getAllowedOAuthFlows() { return allowedOAuthFlows; }
    public void setAllowedOAuthFlows(List<String> allowedOAuthFlows) { this.allowedOAuthFlows = allowedOAuthFlows; }

    public List<String> getAllowedOAuthScopes() { return allowedOAuthScopes; }
    public void setAllowedOAuthScopes(List<String> allowedOAuthScopes) { this.allowedOAuthScopes = allowedOAuthScopes; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public long getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(long lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }
}
