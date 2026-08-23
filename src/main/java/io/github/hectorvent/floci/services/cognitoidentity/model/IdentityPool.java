package io.github.hectorvent.floci.services.cognitoidentity.model;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Cognito federated identity pool.
 *
 * <p>The pool's own configuration is the {@code IdentityPool} shape of the AWS model. The
 * roles, role mappings and principal-tag attribute maps are separate AWS operations
 * ({@code SetIdentityPoolRoles}, {@code SetPrincipalTagAttributeMap}) but belong to the same
 * pool, so they are persisted alongside it and survive an {@code UpdateIdentityPool} replace.
 */
@RegisterForReflection
public class IdentityPool {

    private String identityPoolId;
    private String identityPoolName;
    private boolean allowUnauthenticatedIdentities;
    private boolean allowClassicFlow;
    private Map<String, String> supportedLoginProviders = new LinkedHashMap<>();
    private String developerProviderName;
    private List<String> openIdConnectProviderArns = new ArrayList<>();
    private List<CognitoIdentityProvider> cognitoIdentityProviders = new ArrayList<>();
    private List<String> samlProviderArns = new ArrayList<>();
    private Map<String, String> identityPoolTags = new LinkedHashMap<>();

    private String arn;
    private String accountId;
    private Instant createdAt;

    private Map<String, String> roles = new LinkedHashMap<>();
    private JsonNode roleMappings;
    private Map<String, PrincipalTagAttributeMap> principalTagAttributeMaps = new LinkedHashMap<>();

    public String getIdentityPoolId() {
        return identityPoolId;
    }

    public void setIdentityPoolId(String identityPoolId) {
        this.identityPoolId = identityPoolId;
    }

    public String getIdentityPoolName() {
        return identityPoolName;
    }

    public void setIdentityPoolName(String identityPoolName) {
        this.identityPoolName = identityPoolName;
    }

    public boolean isAllowUnauthenticatedIdentities() {
        return allowUnauthenticatedIdentities;
    }

    public void setAllowUnauthenticatedIdentities(boolean allowUnauthenticatedIdentities) {
        this.allowUnauthenticatedIdentities = allowUnauthenticatedIdentities;
    }

    public boolean isAllowClassicFlow() {
        return allowClassicFlow;
    }

    public void setAllowClassicFlow(boolean allowClassicFlow) {
        this.allowClassicFlow = allowClassicFlow;
    }

    public Map<String, String> getSupportedLoginProviders() {
        return supportedLoginProviders;
    }

    public void setSupportedLoginProviders(Map<String, String> supportedLoginProviders) {
        this.supportedLoginProviders = supportedLoginProviders;
    }

    public String getDeveloperProviderName() {
        return developerProviderName;
    }

    public void setDeveloperProviderName(String developerProviderName) {
        this.developerProviderName = developerProviderName;
    }

    public List<String> getOpenIdConnectProviderArns() {
        return openIdConnectProviderArns;
    }

    public void setOpenIdConnectProviderArns(List<String> openIdConnectProviderArns) {
        this.openIdConnectProviderArns = openIdConnectProviderArns;
    }

    public List<CognitoIdentityProvider> getCognitoIdentityProviders() {
        return cognitoIdentityProviders;
    }

    public void setCognitoIdentityProviders(List<CognitoIdentityProvider> cognitoIdentityProviders) {
        this.cognitoIdentityProviders = cognitoIdentityProviders;
    }

    public List<String> getSamlProviderArns() {
        return samlProviderArns;
    }

    public void setSamlProviderArns(List<String> samlProviderArns) {
        this.samlProviderArns = samlProviderArns;
    }

    public Map<String, String> getIdentityPoolTags() {
        return identityPoolTags;
    }

    public void setIdentityPoolTags(Map<String, String> identityPoolTags) {
        this.identityPoolTags = identityPoolTags;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Map<String, String> getRoles() {
        return roles;
    }

    public void setRoles(Map<String, String> roles) {
        this.roles = roles;
    }

    public JsonNode getRoleMappings() {
        return roleMappings;
    }

    public void setRoleMappings(JsonNode roleMappings) {
        this.roleMappings = roleMappings;
    }

    public Map<String, PrincipalTagAttributeMap> getPrincipalTagAttributeMaps() {
        return principalTagAttributeMaps;
    }

    public void setPrincipalTagAttributeMaps(Map<String, PrincipalTagAttributeMap> principalTagAttributeMaps) {
        this.principalTagAttributeMaps = principalTagAttributeMaps;
    }
}
