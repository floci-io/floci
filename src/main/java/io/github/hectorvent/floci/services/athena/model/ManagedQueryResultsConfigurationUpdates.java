package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** UpdateWorkGroup's changes to managed query results; null members are left as they are. */
@RegisterForReflection
public class ManagedQueryResultsConfigurationUpdates {

    @JsonProperty("Enabled")
    private Boolean enabled;

    @JsonProperty("EncryptionConfiguration")
    private ManagedQueryResultsConfiguration.EncryptionConfiguration encryptionConfiguration;

    @JsonProperty("RemoveEncryptionConfiguration")
    private Boolean removeEncryptionConfiguration;

    public ManagedQueryResultsConfigurationUpdates() {}

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public ManagedQueryResultsConfiguration.EncryptionConfiguration getEncryptionConfiguration() {
        return encryptionConfiguration;
    }
    public void setEncryptionConfiguration(ManagedQueryResultsConfiguration.EncryptionConfiguration configuration) {
        this.encryptionConfiguration = configuration;
    }

    public Boolean getRemoveEncryptionConfiguration() { return removeEncryptionConfiguration; }
    public void setRemoveEncryptionConfiguration(Boolean removeEncryptionConfiguration) {
        this.removeEncryptionConfiguration = removeEncryptionConfiguration;
    }
}
