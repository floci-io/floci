package io.floci.aws.services.rolesanywhere.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A trust anchor, as {@code TrustAnchorDetail} reports it: the certificate authority Roles Anywhere trusts,
 * given as a bundle of PEM certificates or a self-signed repository, with its notification settings, alias and tags.
 * An {@code AWS_ACM_PCA} source is refused at create, so {@code acmPcaArn} is never set by this service.
 */
@RegisterForReflection
public class TrustAnchor {

    private String trustAnchorId;
    private String trustAnchorArn;
    private String name;
    private String sourceType;
    private String x509CertificateData;
    private String acmPcaArn;
    private boolean enabled;
    private Boolean crlDpRevocationEnabled;
    private Instant createdAt;
    private Instant updatedAt;
    private List<NotificationSetting> notificationSettings = new ArrayList<>();
    private String aliasName;
    private String aliasArn;
    private String previousAliasName;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getTrustAnchorId() {
        return trustAnchorId;
    }

    public void setTrustAnchorId(String trustAnchorId) {
        this.trustAnchorId = trustAnchorId;
    }

    public String getTrustAnchorArn() {
        return trustAnchorArn;
    }

    public void setTrustAnchorArn(String trustAnchorArn) {
        this.trustAnchorArn = trustAnchorArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getX509CertificateData() {
        return x509CertificateData;
    }

    public void setX509CertificateData(String x509CertificateData) {
        this.x509CertificateData = x509CertificateData;
    }

    public String getAcmPcaArn() {
        return acmPcaArn;
    }

    public void setAcmPcaArn(String acmPcaArn) {
        this.acmPcaArn = acmPcaArn;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getCrlDpRevocationEnabled() {
        return crlDpRevocationEnabled;
    }

    public void setCrlDpRevocationEnabled(Boolean crlDpRevocationEnabled) {
        this.crlDpRevocationEnabled = crlDpRevocationEnabled;
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

    public List<NotificationSetting> getNotificationSettings() {
        return notificationSettings;
    }

    public void setNotificationSettings(List<NotificationSetting> notificationSettings) {
        this.notificationSettings = notificationSettings == null ? new ArrayList<>() : new ArrayList<>(notificationSettings);
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
