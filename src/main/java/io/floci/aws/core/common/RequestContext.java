package io.floci.aws.core.common;

import jakarta.enterprise.context.RequestScoped;

/**
 * Holds per-request derived values, account ID, region and partition, extracted from the
 * incoming AWS credential and Authorization header. Populated by
 * {@link AccountContextFilter} before any handler runs.
 */
@RequestScoped
public class RequestContext {

    private String accountId;
    private String region;
    private String partition;
    private String accessKeyId;
    private String sessionToken;
    private Boolean iamGovernanceBypass;

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    /**
     * The partition the request's signing region belongs to ({@code aws}, {@code aws-cn}, ...),
     * or null when nothing set it, in which case {@link RegionResolver#getPartition()} answers
     * with the deployment's partition.
     */
    public String getPartition() {
        return partition;
    }

    public void setPartition(String partition) {
        this.partition = partition;
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    /**
     * IAM's decision on an S3 delete or retention change that sends
     * {@code x-amz-bypass-governance-retention}: true when it overrides a governance lock and the caller
     * may bypass it, false when it overrides none, so no bypass permission was checked, and null when
     * IAM did not decide. S3Controller lets the operation bypass a lock only as far as this allows.
     */
    public Boolean getIamGovernanceBypass() {
        return iamGovernanceBypass;
    }

    public void setIamGovernanceBypass(Boolean iamGovernanceBypass) {
        this.iamGovernanceBypass = iamGovernanceBypass;
    }
}
