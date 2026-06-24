package io.github.hectorvent.floci.services.appsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DomainName {
    private String domainName;
    private String description;
    private String certificateArn;
    private String appsyncDomainName;
    private String hostedZoneId;
    private String domainNameArn;
    private Map<String, String> tags = new HashMap<>();

    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCertificateArn() { return certificateArn; }
    public void setCertificateArn(String certificateArn) { this.certificateArn = certificateArn; }

    public String getAppsyncDomainName() { return appsyncDomainName; }
    public void setAppsyncDomainName(String appsyncDomainName) { this.appsyncDomainName = appsyncDomainName; }

    public String getHostedZoneId() { return hostedZoneId; }
    public void setHostedZoneId(String hostedZoneId) { this.hostedZoneId = hostedZoneId; }

    public String getDomainNameArn() { return domainNameArn; }
    public void setDomainNameArn(String domainNameArn) { this.domainNameArn = domainNameArn; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
