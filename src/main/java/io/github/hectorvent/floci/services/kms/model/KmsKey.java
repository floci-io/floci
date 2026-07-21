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
    private KmsKeyUsage keyUsage = KmsKeyUsage.ENCRYPT_DECRYPT;
    private KmsKeySpec keySpec = KmsKeySpec.SYMMETRIC_DEFAULT;
    private long creationDate;
    private long deletionDate;
    private String policy;
    private boolean keyRotationEnabled = false;
    private Map<String, String> tags = new HashMap<>();
    private String privateKeyEncoded;
    private String publicKeyEncoded;
    private int onDemandRotationCount;

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

    public KmsKeyUsage getKeyUsage() { return keyUsage; }
    public void setKeyUsage(KmsKeyUsage keyUsage) { this.keyUsage = keyUsage; }

    public KmsKeySpec getKeySpec() { return keySpec; }
    public void setKeySpec(KmsKeySpec spec) { this.keySpec = spec; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public long getDeletionDate() { return deletionDate; }
    public void setDeletionDate(long deletionDate) { this.deletionDate = deletionDate; }

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }

    public boolean isKeyRotationEnabled() { return keyRotationEnabled; }
    public void setKeyRotationEnabled(boolean keyRotationEnabled) { this.keyRotationEnabled = keyRotationEnabled; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getPrivateKeyEncoded() { return privateKeyEncoded; }
    public void setPrivateKeyEncoded(String privateKeyEncoded) { this.privateKeyEncoded = privateKeyEncoded; }

    public String getPublicKeyEncoded() { return publicKeyEncoded; }
    public void setPublicKeyEncoded(String publicKeyEncoded) { this.publicKeyEncoded = publicKeyEncoded; }

    public int getOnDemandRotationCount() { return onDemandRotationCount; }
    public void setOnDemandRotationCount(int onDemandRotationCount) { this.onDemandRotationCount = onDemandRotationCount; }
}
