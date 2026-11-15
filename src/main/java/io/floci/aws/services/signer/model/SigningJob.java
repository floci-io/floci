package io.floci.aws.services.signer.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One {@code StartSigningJob} record, as {@code DescribeSigningJob} and {@code ListSigningJobs}
 * report it.
 *
 * <p>The job keeps the S3 source, the S3 destination and the profile version it was started
 * against. Floci completes a job without signing anything: see {@code SignerService} for what that
 * means for the fields a real job would fill in from the signed object.
 */
@RegisterForReflection
public class SigningJob {

    private String jobId;
    private String jobArn;
    private String clientRequestToken;
    private String profileName;
    private String profileVersion;
    private String platformId;
    private String platformDisplayName;
    private String status;
    private String statusReason;
    private Long createdAt;
    private Long completedAt;
    private Long signatureExpiresAt;
    private String requestedBy;
    private String jobOwner;
    private String jobInvoker;
    private String sourceBucketName;
    private String sourceKey;
    private String sourceVersion;
    private String destinationBucketName;
    private String destinationPrefix;
    private String signedObjectBucketName;
    private String signedObjectKey;
    private boolean revoked;
    private String revocationReason;
    private Long revokedAt;
    private String revokedBy;

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getJobArn() {
        return jobArn;
    }

    public void setJobArn(String jobArn) {
        this.jobArn = jobArn;
    }

    public String getClientRequestToken() {
        return clientRequestToken;
    }

    public void setClientRequestToken(String clientRequestToken) {
        this.clientRequestToken = clientRequestToken;
    }

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

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Long completedAt) {
        this.completedAt = completedAt;
    }

    public Long getSignatureExpiresAt() {
        return signatureExpiresAt;
    }

    public void setSignatureExpiresAt(Long signatureExpiresAt) {
        this.signatureExpiresAt = signatureExpiresAt;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getJobOwner() {
        return jobOwner;
    }

    public void setJobOwner(String jobOwner) {
        this.jobOwner = jobOwner;
    }

    public String getJobInvoker() {
        return jobInvoker;
    }

    public void setJobInvoker(String jobInvoker) {
        this.jobInvoker = jobInvoker;
    }

    public String getSourceBucketName() {
        return sourceBucketName;
    }

    public void setSourceBucketName(String sourceBucketName) {
        this.sourceBucketName = sourceBucketName;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public void setSourceKey(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public void setSourceVersion(String sourceVersion) {
        this.sourceVersion = sourceVersion;
    }

    public String getDestinationBucketName() {
        return destinationBucketName;
    }

    public void setDestinationBucketName(String destinationBucketName) {
        this.destinationBucketName = destinationBucketName;
    }

    public String getDestinationPrefix() {
        return destinationPrefix;
    }

    public void setDestinationPrefix(String destinationPrefix) {
        this.destinationPrefix = destinationPrefix;
    }

    public String getSignedObjectBucketName() {
        return signedObjectBucketName;
    }

    public void setSignedObjectBucketName(String signedObjectBucketName) {
        this.signedObjectBucketName = signedObjectBucketName;
    }

    public String getSignedObjectKey() {
        return signedObjectKey;
    }

    public void setSignedObjectKey(String signedObjectKey) {
        this.signedObjectKey = signedObjectKey;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void setRevoked(boolean revoked) {
        this.revoked = revoked;
    }

    public String getRevocationReason() {
        return revocationReason;
    }

    public void setRevocationReason(String revocationReason) {
        this.revocationReason = revocationReason;
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
}
