package io.github.hectorvent.floci.services.cognito.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ManagedLoginBranding {
    private String managedLoginBrandingId;
    private String userPoolId;
    private boolean useCognitoProvidedValues;
    /** Null when the caller supplied none; AWS omits Settings from the response in that case. */
    private Map<String, Object> settings;
    private List<Map<String, Object>> assets = new ArrayList<>();
    private long creationDate;
    private long lastModifiedDate;

    public ManagedLoginBranding() {
        long now = System.currentTimeMillis() / 1000L;
        this.creationDate = now;
        this.lastModifiedDate = now;
    }

    public String getManagedLoginBrandingId() { return managedLoginBrandingId; }
    public void setManagedLoginBrandingId(String managedLoginBrandingId) {
        this.managedLoginBrandingId = managedLoginBrandingId;
    }

    public String getUserPoolId() { return userPoolId; }
    public void setUserPoolId(String userPoolId) { this.userPoolId = userPoolId; }

    public boolean isUseCognitoProvidedValues() { return useCognitoProvidedValues; }
    public void setUseCognitoProvidedValues(boolean useCognitoProvidedValues) {
        this.useCognitoProvidedValues = useCognitoProvidedValues;
    }

    public Map<String, Object> getSettings() { return settings; }
    public void setSettings(Map<String, Object> settings) {
        this.settings = settings == null ? null : new LinkedHashMap<>(settings);
    }

    public List<Map<String, Object>> getAssets() { return assets; }
    public void setAssets(List<Map<String, Object>> assets) {
        this.assets = assets == null ? new ArrayList<>() : new ArrayList<>(assets);
    }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public long getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(long lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }
}
