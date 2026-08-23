package io.github.hectorvent.floci.services.cognitoidentity.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mapping between principal tags and user attributes for one identity provider attached to
 * an identity pool ({@code SetPrincipalTagAttributeMap} / {@code GetPrincipalTagAttributeMap}).
 */
@RegisterForReflection
public class PrincipalTagAttributeMap {

    private String identityProviderName;
    private boolean useDefaults;
    private Map<String, String> principalTags = new LinkedHashMap<>();

    public String getIdentityProviderName() {
        return identityProviderName;
    }

    public void setIdentityProviderName(String identityProviderName) {
        this.identityProviderName = identityProviderName;
    }

    public boolean isUseDefaults() {
        return useDefaults;
    }

    public void setUseDefaults(boolean useDefaults) {
        this.useDefaults = useDefaults;
    }

    public Map<String, String> getPrincipalTags() {
        return principalTags;
    }

    public void setPrincipalTags(Map<String, String> principalTags) {
        this.principalTags = principalTags;
    }
}
