package io.github.hectorvent.floci.services.cognito.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserPoolClient {
    private String clientId;
    private String userPoolId;
    private String clientName;
    private String clientSecret;
    private List<UserPoolClientSecret> userPoolClientSecrets = new ArrayList<>();
    private boolean generateSecret;
    private boolean allowedOAuthFlowsUserPoolClient;
    private List<String> allowedOAuthFlows = new ArrayList<>();
    private List<String> allowedOAuthScopes = new ArrayList<>();
    private Map<String, Object> analyticsConfiguration;
    private List<String> callbackURLs = new ArrayList<>();
    private String defaultRedirectURI;
    private List<String> explicitAuthFlows = new ArrayList<>();
    private Integer accessTokenValidity;
    private Integer idTokenValidity;
    private List<String> logoutURLs = new ArrayList<>();
    private String preventUserExistenceErrors;
    private List<String> readAttributes = new ArrayList<>();
    private Integer refreshTokenValidity;
    private List<String> supportedIdentityProviders = new ArrayList<>();
    private Map<String, String> tokenValidityUnits;
    private List<String> writeAttributes = new ArrayList<>();
    private Map<String, Object> refreshTokenRotation;
    private Boolean enableTokenRevocation;
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

    public List<UserPoolClientSecret> getUserPoolClientSecrets() {
        return userPoolClientSecrets;
    }
    public void setUserPoolClientSecrets(List<UserPoolClientSecret> userPoolClientSecrets) {
        this.userPoolClientSecrets = userPoolClientSecrets;
    }

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

    public Map<String, Object> getAnalyticsConfiguration() { return analyticsConfiguration; }
    public void setAnalyticsConfiguration(Map<String, Object> analyticsConfiguration) {
        this.analyticsConfiguration = analyticsConfiguration;
    }

    public List<String> getCallbackURLs() { return callbackURLs; }
    public void setCallbackURLs(List<String> callbackURLs) { this.callbackURLs = callbackURLs; }

    public String getDefaultRedirectURI() { return defaultRedirectURI; }
    public void setDefaultRedirectURI(String defaultRedirectURI) { this.defaultRedirectURI = defaultRedirectURI; }

    public List<String> getExplicitAuthFlows() { return explicitAuthFlows; }
    public void setExplicitAuthFlows(List<String> explicitAuthFlows) { this.explicitAuthFlows = explicitAuthFlows; }

    public Integer getAccessTokenValidity() { return accessTokenValidity; }
    public void setAccessTokenValidity(Integer accessTokenValidity) { this.accessTokenValidity = accessTokenValidity; }

    public Integer getIdTokenValidity() { return idTokenValidity; }
    public void setIdTokenValidity(Integer idTokenValidity) { this.idTokenValidity = idTokenValidity; }

    public List<String> getLogoutURLs() { return logoutURLs; }
    public void setLogoutURLs(List<String> logoutURLs) { this.logoutURLs = logoutURLs; }

    public String getPreventUserExistenceErrors() { return preventUserExistenceErrors; }
    public void setPreventUserExistenceErrors(String preventUserExistenceErrors) {
        this.preventUserExistenceErrors = preventUserExistenceErrors;
    }

    public List<String> getReadAttributes() { return readAttributes; }
    public void setReadAttributes(List<String> readAttributes) { this.readAttributes = readAttributes; }

    public Integer getRefreshTokenValidity() { return refreshTokenValidity; }
    public void setRefreshTokenValidity(Integer refreshTokenValidity) { this.refreshTokenValidity = refreshTokenValidity; }

    public List<String> getSupportedIdentityProviders() { return supportedIdentityProviders; }
    public void setSupportedIdentityProviders(List<String> supportedIdentityProviders) {
        this.supportedIdentityProviders = supportedIdentityProviders;
    }

    public Map<String, String> getTokenValidityUnits() { return tokenValidityUnits; }
    public void setTokenValidityUnits(Map<String, String> tokenValidityUnits) { this.tokenValidityUnits = tokenValidityUnits; }

    public List<String> getWriteAttributes() { return writeAttributes; }
    public void setWriteAttributes(List<String> writeAttributes) { this.writeAttributes = writeAttributes; }

    public Map<String, Object> getRefreshTokenRotation() { return refreshTokenRotation; }
    public void setRefreshTokenRotation(Map<String, Object> refreshTokenRotation) {
        this.refreshTokenRotation = refreshTokenRotation;
    }

    public Boolean getEnableTokenRevocation() { return enableTokenRevocation; }
    public void setEnableTokenRevocation(Boolean enableTokenRevocation) {
        this.enableTokenRevocation = enableTokenRevocation;
    }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public long getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(long lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }
}
