package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A workgroup's managed query results: Athena keeps the results, optionally under a KMS key. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ManagedQueryResultsConfiguration {

    @JsonProperty("Enabled")
    private Boolean enabled;

    @JsonProperty("EncryptionConfiguration")
    private EncryptionConfiguration encryptionConfiguration;

    public ManagedQueryResultsConfiguration() {}

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public EncryptionConfiguration getEncryptionConfiguration() { return encryptionConfiguration; }
    public void setEncryptionConfiguration(EncryptionConfiguration encryptionConfiguration) {
        this.encryptionConfiguration = encryptionConfiguration;
    }

    /** The KMS key managed query results are encrypted with. */
    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EncryptionConfiguration {

        @JsonProperty("KmsKey")
        private String kmsKey;

        public EncryptionConfiguration() {}

        public EncryptionConfiguration(String kmsKey) {
            this.kmsKey = kmsKey;
        }

        public String getKmsKey() { return kmsKey; }
        public void setKmsKey(String kmsKey) { this.kmsKey = kmsKey; }
    }
}
