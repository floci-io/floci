package io.floci.aws.services.signer.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One signing profile, as {@code GetSigningProfile} and {@code ListSigningProfiles} report it.
 *
 * <p>AWS keeps a version per {@code PutSigningProfile} call, and a profile carries exactly one
 * current version. Floci keeps the current version only: {@code profileVersion} and
 * {@code profileVersionArn} identify it, and the version fields of {@link ProfilePermission} are the
 * version a grant was made against.
 */
@RegisterForReflection
public class SigningProfile {

    /** One grant of a cross-account signing permission on the profile. */
    @RegisterForReflection
    public static class ProfilePermission {
        private String action;
        private String principal;
        private String statementId;
        private String profileVersion;

        public ProfilePermission() {
        }

        public ProfilePermission(String action, String principal, String statementId, String profileVersion) {
            this.action = action;
            this.principal = principal;
            this.statementId = statementId;
            this.profileVersion = profileVersion;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getPrincipal() {
            return principal;
        }

        public void setPrincipal(String principal) {
            this.principal = principal;
        }

        public String getStatementId() {
            return statementId;
        }

        public void setStatementId(String statementId) {
            this.statementId = statementId;
        }

        public String getProfileVersion() {
            return profileVersion;
        }

        public void setProfileVersion(String profileVersion) {
            this.profileVersion = profileVersion;
        }
    }

    private String profileName;
    private String profileVersion;
    private String profileVersionArn;
    private String arn;
    private String platformId;
    private String platformDisplayName;
    private String signingMaterialCertificateArn;
    private String signatureValidityPeriodType;
    private Integer signatureValidityPeriodValue;
    private JsonNode overrides;
    private Map<String, String> signingParameters = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    private String status;
    private String statusReason;
    private Long revocationEffectiveFrom;
    private Long revokedAt;
    private String revokedBy;
    private String revisionId;
    private List<ProfilePermission> permissions = new ArrayList<>();

    public String getProfileName() {
        return profileName;
    }

    public void setProfileName(String profileName) {
        this.profileName = profileName;
    }

    public String getProfileVersion() {
        return profileVersion;
    }

    public void setProfileVersion(String profileVersion) {
        this.profileVersion = profileVersion;
    }

    public String getProfileVersionArn() {
        return profileVersionArn;
    }

    public void setProfileVersionArn(String profileVersionArn) {
        this.profileVersionArn = profileVersionArn;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getPlatformId() {
        return platformId;
    }

    public void setPlatformId(String platformId) {
        this.platformId = platformId;
    }

    public String getPlatformDisplayName() {
        return platformDisplayName;
    }

    public void setPlatformDisplayName(String platformDisplayName) {
        this.platformDisplayName = platformDisplayName;
    }

    public String getSigningMaterialCertificateArn() {
        return signingMaterialCertificateArn;
    }

    public void setSigningMaterialCertificateArn(String signingMaterialCertificateArn) {
        this.signingMaterialCertificateArn = signingMaterialCertificateArn;
    }

    public String getSignatureValidityPeriodType() {
        return signatureValidityPeriodType;
    }

    public void setSignatureValidityPeriodType(String signatureValidityPeriodType) {
        this.signatureValidityPeriodType = signatureValidityPeriodType;
    }

    public Integer getSignatureValidityPeriodValue() {
        return signatureValidityPeriodValue;
    }

    public void setSignatureValidityPeriodValue(Integer signatureValidityPeriodValue) {
        this.signatureValidityPeriodValue = signatureValidityPeriodValue;
    }

    public JsonNode getOverrides() {
        return overrides;
    }

    public void setOverrides(JsonNode overrides) {
        this.overrides = overrides;
    }

    public Map<String, String> getSigningParameters() {
        return signingParameters;
    }

    public void setSigningParameters(Map<String, String> signingParameters) {
        this.signingParameters = signingParameters == null ? new LinkedHashMap<>() : signingParameters;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public Long getRevocationEffectiveFrom() {
        return revocationEffectiveFrom;
    }

    public void setRevocationEffectiveFrom(Long revocationEffectiveFrom) {
        this.revocationEffectiveFrom = revocationEffectiveFrom;
    }

    public Long getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Long revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getRevokedBy() {
        return revokedBy;
    }

    public void setRevokedBy(String revokedBy) {
        this.revokedBy = revokedBy;
    }

    public String getRevisionId() {
        return revisionId;
    }

    public void setRevisionId(String revisionId) {
        this.revisionId = revisionId;
    }

    public List<ProfilePermission> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<ProfilePermission> permissions) {
        this.permissions = permissions == null ? new ArrayList<>() : permissions;
    }
}
