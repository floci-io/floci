package io.floci.aws.services.rolesanywhere.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A certificate revocation list imported against a trust anchor, as {@code CrlDetail} reports it. {@code crlData}
 * holds the bytes the request sent, base64 encoded, the form the REST JSON protocol carries a blob in.
 */
@RegisterForReflection
public class Crl {

    private String crlId;
    private String crlArn;
    private String name;
    private boolean enabled;
    private String crlData;
    private String trustAnchorArn;
    private Instant createdAt;
    private Instant updatedAt;
    private String aliasName;
    private String aliasArn;
    private String previousAliasName;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getCrlId() {
        return crlId;
    }

    public void setCrlId(String crlId) {
        this.crlId = crlId;
    }

    public String getCrlArn() {
        return crlArn;
    }

    public void setCrlArn(String crlArn) {
        this.crlArn = crlArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCrlData() {
        return crlData;
    }

    public void setCrlData(String crlData) {
        this.crlData = crlData;
    }

    public String getTrustAnchorArn() {
        return trustAnchorArn;
    }

    public void setTrustAnchorArn(String trustAnchorArn) {
        this.trustAnchorArn = trustAnchorArn;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getAliasName() {
        return aliasName;
    }

    public void setAliasName(String aliasName) {
        this.aliasName = aliasName;
    }

    public String getAliasArn() {
        return aliasArn;
    }

    public void setAliasArn(String aliasArn) {
        this.aliasArn = aliasArn;
    }

    public String getPreviousAliasName() {
        return previousAliasName;
    }

    public void setPreviousAliasName(String previousAliasName) {
        this.previousAliasName = previousAliasName;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
