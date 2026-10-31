package io.github.hectorvent.floci.services.paymentcryptography.model;

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
    private Map<String, Boolean> keyModesOfUse = new HashMap<>();
    private String keyOrigin = "AWS_PAYMENT_CRYPTOGRAPHY";
    private String keyCheckValue;
    private String keyCheckValueAlgorithm = "CMAC";
    private String keyState = "CREATE_COMPLETE";
    private boolean exportable;
    private boolean enabled = true;
    private String keyMaterialHex;
    private long createTimestamp;
    private long usageStartTimestamp;
    private Map<String, String> tags = new HashMap<>();

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

    public String getKeyMaterialHex() { return keyMaterialHex; }
    public void setKeyMaterialHex(String keyMaterialHex) { this.keyMaterialHex = keyMaterialHex; }

    public long getCreateTimestamp() { return createTimestamp; }
    public void setCreateTimestamp(long createTimestamp) { this.createTimestamp = createTimestamp; }

    public long getUsageStartTimestamp() { return usageStartTimestamp; }
    public void setUsageStartTimestamp(long usageStartTimestamp) {
        this.usageStartTimestamp = usageStartTimestamp;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
