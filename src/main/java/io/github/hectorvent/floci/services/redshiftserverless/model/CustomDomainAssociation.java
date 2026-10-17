package io.github.hectorvent.floci.services.redshiftserverless.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * A custom domain bound to a Redshift Serverless workgroup. The certificate expiry time is read from
 * ACM when the certificate exists in the emulator.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomDomainAssociation {

    private String workgroupName;
    private String customDomainName;
    private String customDomainCertificateArn;
    private Instant customDomainCertificateExpiryTime;
    private String ownerId;

    public CustomDomainAssociation() {
    }

    public CustomDomainAssociation(CustomDomainAssociation other) {
        this.ownerId = other.ownerId;
        this.workgroupName = other.workgroupName;
        this.customDomainName = other.customDomainName;
        this.customDomainCertificateArn = other.customDomainCertificateArn;
        this.customDomainCertificateExpiryTime = other.customDomainCertificateExpiryTime;
    }

    public String getWorkgroupName() {
        return workgroupName;
    }

    public void setWorkgroupName(String workgroupName) {
        this.workgroupName = workgroupName;
    }

    public String getCustomDomainName() {
        return customDomainName;
    }

    public void setCustomDomainName(String customDomainName) {
        this.customDomainName = customDomainName;
    }

    public String getCustomDomainCertificateArn() {
        return customDomainCertificateArn;
    }

    public void setCustomDomainCertificateArn(String customDomainCertificateArn) {
        this.customDomainCertificateArn = customDomainCertificateArn;
    }

    public Instant getCustomDomainCertificateExpiryTime() {
        return customDomainCertificateExpiryTime;
    }

    public void setCustomDomainCertificateExpiryTime(Instant customDomainCertificateExpiryTime) {
        this.customDomainCertificateExpiryTime = customDomainCertificateExpiryTime;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }
}
