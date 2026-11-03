package io.github.hectorvent.floci.services.paymentcryptography.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

/**
 * A Payment Cryptography key.
 *
 * <p>The material is kept as hexadecimal, the way the service's own import and
 * export formats carry it, so a key that arrives from an import is the same
 * key that leaves from an export.
 *
 * <p>The material is not written to the store: it is held by the key while the
 * emulator runs, and no operation of this service returns it or needs it, so
 * leaving it out of the persisted key keeps it off the disk. A key read back
 * from a store has no material, and the slice that exports a key decides how
 * one is kept.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentCryptographyKey {
    private String keyId;
    private String arn;
    private String region;
    private String keyAlgorithm;
    private String keyClass;
    private String keyUsage;
    /**
     * The usage a key derives other keys for, when the key is a derivation key.
     * Empty for a key that derives nothing.
     */
    private String deriveKeyUsage;
    private Map<String, Boolean> keyModesOfUse = new HashMap<>();
    private String keyOrigin = "AWS_PAYMENT_CRYPTOGRAPHY";
    private String keyCheckValue;
    private String keyCheckValueAlgorithm = "CMAC";
    private String keyState = "CREATE_COMPLETE";
    private boolean exportable;
    private boolean enabled = true;
    private String keyMaterialHex;
    /**
     * The public half of an asymmetric key, base64 of the DER of its
     * SubjectPublicKeyInfo. Empty for a symmetric key, which has one blob of
     * material instead.
     */
    private String keyPublicKeyBase64;
    /**
     * The private half of an asymmetric key, base64 of the DER of its PKCS#8
     * encoding. Empty for a public key, which has no private half.
     *
     * <p>It is not written to the store, for the same reason the symmetric
     * material is not: a private key in clear in the data directory is a
     * secret on disk.
     */
    private String keyPrivateKeyBase64;
    /**
     * The certificate an imported public key arrived with, in PEM. A key the
     * service made has none, and gets a self-signed one when one is asked for.
     */
    private String keyCertificatePem;
    private long createTimestamp;
    private long usageStartTimestamp;
    private long deletePendingTimestamp;
    private long deleteTimestamp;
    private Map<String, String> tags = new HashMap<>();
    /** The region of the primary of a multi-region key, and its own region otherwise. */

    /** The regions a multi-region key is in, besides the primary's. */

    /** Whether this record is the primary of a multi-region key or a replica of one. */

    /** Whether the key took the account's default replication regions. */

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getKeyAlgorithm() { return keyAlgorithm; }
    public void setKeyAlgorithm(String keyAlgorithm) { this.keyAlgorithm = keyAlgorithm; }

    public String getKeyClass() { return keyClass; }
    public void setKeyClass(String keyClass) { this.keyClass = keyClass; }

    public String getKeyUsage() { return keyUsage; }
    public void setKeyUsage(String keyUsage) { this.keyUsage = keyUsage; }

    public String getDeriveKeyUsage() { return deriveKeyUsage; }
    public void setDeriveKeyUsage(String deriveKeyUsage) { this.deriveKeyUsage = deriveKeyUsage; }

    public Map<String, Boolean> getKeyModesOfUse() { return keyModesOfUse; }
    public void setKeyModesOfUse(Map<String, Boolean> keyModesOfUse) { this.keyModesOfUse = keyModesOfUse; }

    public String getKeyOrigin() { return keyOrigin; }
    public void setKeyOrigin(String keyOrigin) { this.keyOrigin = keyOrigin; }

    public String getKeyCheckValue() { return keyCheckValue; }
    public void setKeyCheckValue(String keyCheckValue) { this.keyCheckValue = keyCheckValue; }

    public String getKeyCheckValueAlgorithm() { return keyCheckValueAlgorithm; }
    public void setKeyCheckValueAlgorithm(String keyCheckValueAlgorithm) {
        this.keyCheckValueAlgorithm = keyCheckValueAlgorithm;
    }

    public String getKeyState() { return keyState; }
    public void setKeyState(String keyState) { this.keyState = keyState; }

    public boolean isExportable() { return exportable; }
    public void setExportable(boolean exportable) { this.exportable = exportable; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** The material of the key, which is held by the key and never written to the store. */
    @JsonIgnore
    public String getKeyMaterialHex() { return keyMaterialHex; }
    public void setKeyMaterialHex(String keyMaterialHex) { this.keyMaterialHex = keyMaterialHex; }

    public String getKeyPublicKeyBase64() { return keyPublicKeyBase64; }
    public void setKeyPublicKeyBase64(String keyPublicKeyBase64) {
        this.keyPublicKeyBase64 = keyPublicKeyBase64;
    }

    /**
     * The private half of an asymmetric key.
     *
     * <p>It is held by the key and never written to the store, like the
     * symmetric material.
     */
    @JsonIgnore
    public String getKeyPrivateKeyBase64() { return keyPrivateKeyBase64; }
    public void setKeyPrivateKeyBase64(String keyPrivateKeyBase64) {
        this.keyPrivateKeyBase64 = keyPrivateKeyBase64;
    }

    public String getKeyCertificatePem() { return keyCertificatePem; }
    public void setKeyCertificatePem(String keyCertificatePem) {
        this.keyCertificatePem = keyCertificatePem;
    }

    public long getCreateTimestamp() { return createTimestamp; }
    public void setCreateTimestamp(long createTimestamp) { this.createTimestamp = createTimestamp; }

    public long getUsageStartTimestamp() { return usageStartTimestamp; }
    public void setUsageStartTimestamp(long usageStartTimestamp) {
        this.usageStartTimestamp = usageStartTimestamp;
    }

    /** When the key is deleted, zero while it is not scheduled for deletion. */
    public long getDeletePendingTimestamp() { return deletePendingTimestamp; }
    public void setDeletePendingTimestamp(long deletePendingTimestamp) {
        this.deletePendingTimestamp = deletePendingTimestamp;
    }

    /** When the key was deleted, zero while it is not. */
    public long getDeleteTimestamp() { return deleteTimestamp; }
    public void setDeleteTimestamp(long deleteTimestamp) { this.deleteTimestamp = deleteTimestamp; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
