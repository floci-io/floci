package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransitGatewayVpcAttachmentOptions {

    private String dnsSupport;
    private String securityGroupReferencingSupport;
    private String ipv6Support;
    private String applianceModeSupport;

    public TransitGatewayVpcAttachmentOptions() {}

    public String getDnsSupport() { return dnsSupport; }
    public void setDnsSupport(String dnsSupport) { this.dnsSupport = dnsSupport; }

    public String getSecurityGroupReferencingSupport() { return securityGroupReferencingSupport; }
    public void setSecurityGroupReferencingSupport(String securityGroupReferencingSupport) {
        this.securityGroupReferencingSupport = securityGroupReferencingSupport;
    }

    public String getIpv6Support() { return ipv6Support; }
    public void setIpv6Support(String ipv6Support) { this.ipv6Support = ipv6Support; }

    public String getApplianceModeSupport() { return applianceModeSupport; }
    public void setApplianceModeSupport(String applianceModeSupport) {
        this.applianceModeSupport = applianceModeSupport;
    }
}
