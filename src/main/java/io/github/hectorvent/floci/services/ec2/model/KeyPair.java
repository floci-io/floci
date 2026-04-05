package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KeyPair {

    private String keyName;
    private String keyPairId;
    private String keyFingerprint;
    private String keyMaterial;
    private String region;
    private List<Tag> tags = new ArrayList<>();

    public KeyPair() {}

    public String getKeyName() { return keyName; }
    public void setKeyName(String keyName) { this.keyName = keyName; }

    public String getKeyPairId() { return keyPairId; }
    public void setKeyPairId(String keyPairId) { this.keyPairId = keyPairId; }

    public String getKeyFingerprint() { return keyFingerprint; }
    public void setKeyFingerprint(String keyFingerprint) { this.keyFingerprint = keyFingerprint; }

    public String getKeyMaterial() { return keyMaterial; }
    public void setKeyMaterial(String keyMaterial) { this.keyMaterial = keyMaterial; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
