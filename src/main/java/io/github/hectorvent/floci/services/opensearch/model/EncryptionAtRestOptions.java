package io.github.hectorvent.floci.services.opensearch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Encryption-at-rest options. Round-tripped only — the underlying Docker
 * volume is not actually encrypted. Real AWS would back the domain by an
 * encrypted EBS volume and refuse to disable encryption once turned on; we
 * accept whatever was last set so SDK clients can confirm their config.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class EncryptionAtRestOptions {

    @JsonProperty("Enabled")
    private boolean enabled = false;

    @JsonProperty("KmsKeyId")
    private String kmsKeyId;

    public EncryptionAtRestOptions() {}

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }
}
