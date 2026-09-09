package io.github.hectorvent.floci.services.cognito.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class IdentityProvider {
    private String userPoolId;
    private String providerName;
    private String providerType;
    private Map<String, String> providerDetails = new LinkedHashMap<>();
    private Map<String, String> attributeMapping = new LinkedHashMap<>();
    private List<String> idpIdentifiers = new ArrayList<>();
    private long creationDate;
    private long lastModifiedDate;

    public IdentityProvider() {
        long now = System.currentTimeMillis() / 1000L;
        this.creationDate = now;
        this.lastModifiedDate = now;
    }

    public String getUserPoolId() { return userPoolId; }
    public void setUserPoolId(String userPoolId) { this.userPoolId = userPoolId; }

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }

    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }

    public Map<String, String> getProviderDetails() { return providerDetails; }
    public void setProviderDetails(Map<String, String> providerDetails) { this.providerDetails = providerDetails; }

    public Map<String, String> getAttributeMapping() { return attributeMapping; }
    public void setAttributeMapping(Map<String, String> attributeMapping) { this.attributeMapping = attributeMapping; }

    public List<String> getIdpIdentifiers() { return idpIdentifiers; }
    public void setIdpIdentifiers(List<String> idpIdentifiers) { this.idpIdentifiers = idpIdentifiers; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public long getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(long lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }
}
