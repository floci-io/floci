package io.github.hectorvent.floci.services.cognito.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserPoolDomain {
    private String domain;
    private String userPoolId;
    private String awsAccountId;
    private String status = "ACTIVE";
    private String version;
    private String s3Bucket;
    private String cloudFrontDistribution;
    private Integer managedLoginVersion;

    // Custom-domain-only fields (null for an Amazon Cognito prefix domain).
    private String certificateArn;
    private String securityPolicy;

    private long creationDate;
    private long lastModifiedDate;

    public UserPoolDomain() {
        long now = System.currentTimeMillis() / 1000L;
        this.creationDate = now;
        this.lastModifiedDate = now;
    }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getUserPoolId() { return userPoolId; }
    public void setUserPoolId(String userPoolId) { this.userPoolId = userPoolId; }

    public String getAwsAccountId() { return awsAccountId; }
    public void setAwsAccountId(String awsAccountId) { this.awsAccountId = awsAccountId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getS3Bucket() { return s3Bucket; }
    public void setS3Bucket(String s3Bucket) { this.s3Bucket = s3Bucket; }

    public String getCloudFrontDistribution() { return cloudFrontDistribution; }
    public void setCloudFrontDistribution(String cloudFrontDistribution) { this.cloudFrontDistribution = cloudFrontDistribution; }

    public Integer getManagedLoginVersion() { return managedLoginVersion; }
    public void setManagedLoginVersion(Integer managedLoginVersion) { this.managedLoginVersion = managedLoginVersion; }

    public String getCertificateArn() { return certificateArn; }
    public void setCertificateArn(String certificateArn) { this.certificateArn = certificateArn; }

    public String getSecurityPolicy() { return securityPolicy; }
    public void setSecurityPolicy(String securityPolicy) { this.securityPolicy = securityPolicy; }

    public boolean isCustomDomain() { return certificateArn != null; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public long getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(long lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }
}
