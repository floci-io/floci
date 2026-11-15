package io.floci.aws.services.kms.model;

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
    private String keyState = "Enabled"; // Enabled, Disabled, PendingDeletion, PendingImport
    private String origin = "AWS_KMS";
    // CUSTOMER for keys a customer created, AWS for the per-service keys AWS ships under the
    // reserved alias/aws/* aliases. DescribeKey reports it, and it is what tells a caller the
    // key cannot be deleted, retagged or repolicied.
    private String keyManager = "CUSTOMER";
    private String expirationModel;
    private long validTo;
    private KmsImportParameters importParameters;
    private String keyMaterialId;
    private KmsKeyUsage keyUsage = KmsKeyUsage.ENCRYPT_DECRYPT;
    private KmsKeySpec keySpec = KmsKeySpec.SYMMETRIC_DEFAULT;
    private boolean multiRegion;
    private String multiRegionKeyType;
    private String multiRegionPrimaryRegion;
    private long creationDate;
    private long deletionDate;
    private String policy;
    private boolean keyRotationEnabled = false;
    private Map<String, String> tags = new HashMap<>();
    private String privateKeyEncoded;
    private String publicKeyEncoded;
    private int onDemandRotationCount;
    // Backing keys for the AES-GCM ciphertext envelope (symmetric CMKs only): backing key id
    // -> Base64(32 random bytes). Rotation adds a new entry and switches currentBackingKeyId
    // rather than replacing the map, so blobs encrypted under a prior backing key keep
    // decrypting, matching AWS KMS's own behavior of retaining prior backing keys. Keys
    // persisted before this field existed load with an empty map (Jackson leaves the default),
    // and KmsService lazily generates material for them on first use.
    private Map<String, String> backingKeys = new HashMap<>();
    private String currentBackingKeyId;
    // Only completed rotations have a timestamp. The first material and material loaded
    // from older snapshots have no recorded rotation date.
    private Map<String, Long> backingKeyRotationDates = new HashMap<>();

    public KmsKey() {
        this.creationDate = Instant.now().getEpochSecond();
    }

    public KmsKey(KmsKey source) {
        this.keyId = source.keyId;
        this.arn = source.arn;
        this.description = source.description;
        this.enabled = source.enabled;
        this.keyState = source.keyState;
        this.origin = source.origin;
        this.keyManager = source.keyManager;
        this.expirationModel = source.expirationModel;
        this.validTo = source.validTo;
        this.importParameters = source.importParameters;
        this.keyMaterialId = source.keyMaterialId;
        this.keyUsage = source.keyUsage;
        this.keySpec = source.keySpec;
        this.multiRegion = source.multiRegion;
        this.multiRegionKeyType = source.multiRegionKeyType;
        this.multiRegionPrimaryRegion = source.multiRegionPrimaryRegion;
        this.creationDate = source.creationDate;
        this.deletionDate = source.deletionDate;
        this.policy = source.policy;
        this.keyRotationEnabled = source.keyRotationEnabled;
        this.tags = source.tags == null ? null : new HashMap<>(source.tags);
        this.privateKeyEncoded = source.privateKeyEncoded;
        this.publicKeyEncoded = source.publicKeyEncoded;
        this.onDemandRotationCount = source.onDemandRotationCount;
        this.backingKeys = source.backingKeys == null ? null : new HashMap<>(source.backingKeys);
        this.currentBackingKeyId = source.currentBackingKeyId;
        this.backingKeyRotationDates = source.backingKeyRotationDates == null ? null : new HashMap<>(source.backingKeyRotationDates);
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

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public String getKeyManager() { return keyManager; }
    public void setKeyManager(String keyManager) { this.keyManager = keyManager; }

    public String getExpirationModel() { return expirationModel; }
    public void setExpirationModel(String expirationModel) { this.expirationModel = expirationModel; }

    public long getValidTo() { return validTo; }
    public void setValidTo(long validTo) { this.validTo = validTo; }

    public KmsImportParameters getImportParameters() { return importParameters; }
    public void setImportParameters(KmsImportParameters importParameters) {
        this.importParameters = importParameters;
    }

    public String getKeyMaterialId() { return keyMaterialId; }
    public void setKeyMaterialId(String keyMaterialId) { this.keyMaterialId = keyMaterialId; }

    public KmsKeyUsage getKeyUsage() { return keyUsage; }
    public void setKeyUsage(KmsKeyUsage keyUsage) { this.keyUsage = keyUsage; }

    public KmsKeySpec getKeySpec() { return keySpec; }
    public void setKeySpec(KmsKeySpec spec) { this.keySpec = spec; }

    public boolean isMultiRegion() { return multiRegion; }
    public void setMultiRegion(boolean multiRegion) { this.multiRegion = multiRegion; }

    public String getMultiRegionKeyType() { return multiRegionKeyType; }
    public void setMultiRegionKeyType(String multiRegionKeyType) { this.multiRegionKeyType = multiRegionKeyType; }

    public String getMultiRegionPrimaryRegion() { return multiRegionPrimaryRegion; }
    public void setMultiRegionPrimaryRegion(String multiRegionPrimaryRegion) {
        this.multiRegionPrimaryRegion = multiRegionPrimaryRegion;
    }

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

    public Map<String, String> getBackingKeys() { return backingKeys; }
    public void setBackingKeys(Map<String, String> backingKeys) { this.backingKeys = backingKeys; }

    public String getCurrentBackingKeyId() { return currentBackingKeyId; }
    public void setCurrentBackingKeyId(String currentBackingKeyId) { this.currentBackingKeyId = currentBackingKeyId; }

    public Map<String, Long> getBackingKeyRotationDates() { return backingKeyRotationDates; }
    public void setBackingKeyRotationDates(Map<String, Long> dates) {
        this.backingKeyRotationDates = dates == null ? new HashMap<>() : dates;
    }
}
