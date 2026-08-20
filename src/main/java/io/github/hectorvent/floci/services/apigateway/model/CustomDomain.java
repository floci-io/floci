package io.github.hectorvent.floci.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomDomain {
    private String domainName;
    private String certificateName;
    private String certificateArn;
    private String certificateUploadDate;
    private String regionalDomainName;
    private String regionalHostedZoneId;
    private String regionalCertificateName;
    private String regionalCertificateArn;
    private String distributionDomainName;
    private String distributionHostedZoneId;
    private String endpointConfigurationType; // REGIONAL or EDGE
    private String domainNameStatus; // AVAILABLE, UPDATING, PENDING
    private String securityPolicy;
    private Map<String, String> tags;

    public CustomDomain() {
        this.domainNameStatus = "AVAILABLE";
        this.endpointConfigurationType = "REGIONAL";
        this.securityPolicy = "TLS_1_2";
    }

    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }

    public String getCertificateName() { return certificateName; }
    public void setCertificateName(String certificateName) { this.certificateName = certificateName; }

    public String getCertificateArn() { return certificateArn; }
    public void setCertificateArn(String certificateArn) { this.certificateArn = certificateArn; }

    public String getCertificateUploadDate() { return certificateUploadDate; }
    public void setCertificateUploadDate(String certificateUploadDate) { this.certificateUploadDate = certificateUploadDate; }

    public String getRegionalDomainName() { return regionalDomainName; }
    public void setRegionalDomainName(String regionalDomainName) { this.regionalDomainName = regionalDomainName; }

    public String getRegionalHostedZoneId() { return regionalHostedZoneId; }
    public void setRegionalHostedZoneId(String regionalHostedZoneId) { this.regionalHostedZoneId = regionalHostedZoneId; }

    public String getRegionalCertificateName() { return regionalCertificateName; }
    public void setRegionalCertificateName(String regionalCertificateName) { this.regionalCertificateName = regionalCertificateName; }

    public String getRegionalCertificateArn() { return regionalCertificateArn; }
    public void setRegionalCertificateArn(String regionalCertificateArn) { this.regionalCertificateArn = regionalCertificateArn; }

    public String getDistributionDomainName() { return distributionDomainName; }
    public void setDistributionDomainName(String distributionDomainName) { this.distributionDomainName = distributionDomainName; }

    public String getDistributionHostedZoneId() { return distributionHostedZoneId; }
    public void setDistributionHostedZoneId(String distributionHostedZoneId) { this.distributionHostedZoneId = distributionHostedZoneId; }

    public String getEndpointConfigurationType() { return endpointConfigurationType; }
    public void setEndpointConfigurationType(String endpointConfigurationType) { this.endpointConfigurationType = endpointConfigurationType; }

    public String getDomainNameStatus() { return domainNameStatus; }
    public void setDomainNameStatus(String domainNameStatus) { this.domainNameStatus = domainNameStatus; }

    public String getSecurityPolicy() { return securityPolicy; }
    public void setSecurityPolicy(String securityPolicy) { this.securityPolicy = securityPolicy; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
