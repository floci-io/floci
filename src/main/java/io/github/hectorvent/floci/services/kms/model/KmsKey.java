package io.github.hectorvent.floci.services.kms.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KmsKey {
    private String keyId;
    private String arn;
    private String description;
    private boolean enabled = true;
    private String keyState = "Enabled"; // Enabled, Disabled, PendingDeletion
    private String keyUsage = "ENCRYPT_DECRYPT";
    private String customerMasterKeySpec = "SYMMETRIC_DEFAULT";
    private long creationDate;
    private long deletionDate;
    private String policy;
    private Map<String, String> tags = new HashMap<>();

    public KmsKey() {
        this.creationDate = Instant.now().getEpochSecond();
    }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getKeyState() { return keyState; }
    public void setKeyState(String keyState) { this.keyState = keyState; }

    public String getKeyUsage() { return keyUsage; }
    public void setKeyUsage(String keyUsage) { this.keyUsage = keyUsage; }

    public String getCustomerMasterKeySpec() { return customerMasterKeySpec; }
    public void setCustomerMasterKeySpec(String spec) { this.customerMasterKeySpec = spec; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public long getDeletionDate() { return deletionDate; }
    public void setDeletionDate(long deletionDate) { this.deletionDate = deletionDate; }

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
