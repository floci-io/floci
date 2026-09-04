package io.github.hectorvent.floci.services.cognitoidentity.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A Cognito user pool attached to an identity pool
 * ({@code IdentityPool.CognitoIdentityProviders} member).
 */
@RegisterForReflection
public class CognitoIdentityProvider {

    private String providerName;
    private String clientId;
    private boolean serverSideTokenCheck;

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public boolean isServerSideTokenCheck() {
        return serverSideTokenCheck;
    }

    public void setServerSideTokenCheck(boolean serverSideTokenCheck) {
        this.serverSideTokenCheck = serverSideTokenCheck;
    }
}
