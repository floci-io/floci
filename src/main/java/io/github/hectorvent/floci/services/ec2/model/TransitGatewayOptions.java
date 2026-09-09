package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransitGatewayOptions {

    private Long amazonSideAsn;
    private List<String> transitGatewayCidrBlocks = new ArrayList<>();
    private String autoAcceptSharedAttachments;
    private String defaultRouteTableAssociation;
    private String associationDefaultRouteTableId;
    private String defaultRouteTablePropagation;
    private String propagationDefaultRouteTableId;
    private String vpnEcmpSupport;
    private String dnsSupport;
    private String securityGroupReferencingSupport;
    private String multicastSupport;

    public TransitGatewayOptions() {}

    public Long getAmazonSideAsn() { return amazonSideAsn; }
    public void setAmazonSideAsn(Long amazonSideAsn) { this.amazonSideAsn = amazonSideAsn; }

    public List<String> getTransitGatewayCidrBlocks() { return transitGatewayCidrBlocks; }
    public void setTransitGatewayCidrBlocks(List<String> transitGatewayCidrBlocks) {
        this.transitGatewayCidrBlocks = transitGatewayCidrBlocks;
    }

    public String getAutoAcceptSharedAttachments() { return autoAcceptSharedAttachments; }
    public void setAutoAcceptSharedAttachments(String autoAcceptSharedAttachments) {
        this.autoAcceptSharedAttachments = autoAcceptSharedAttachments;
    }

    public String getDefaultRouteTableAssociation() { return defaultRouteTableAssociation; }
    public void setDefaultRouteTableAssociation(String defaultRouteTableAssociation) {
        this.defaultRouteTableAssociation = defaultRouteTableAssociation;
    }

    public String getAssociationDefaultRouteTableId() { return associationDefaultRouteTableId; }
    public void setAssociationDefaultRouteTableId(String associationDefaultRouteTableId) {
        this.associationDefaultRouteTableId = associationDefaultRouteTableId;
    }

    public String getDefaultRouteTablePropagation() { return defaultRouteTablePropagation; }
    public void setDefaultRouteTablePropagation(String defaultRouteTablePropagation) {
        this.defaultRouteTablePropagation = defaultRouteTablePropagation;
    }

    public String getPropagationDefaultRouteTableId() { return propagationDefaultRouteTableId; }
    public void setPropagationDefaultRouteTableId(String propagationDefaultRouteTableId) {
        this.propagationDefaultRouteTableId = propagationDefaultRouteTableId;
    }

    public String getVpnEcmpSupport() { return vpnEcmpSupport; }
    public void setVpnEcmpSupport(String vpnEcmpSupport) { this.vpnEcmpSupport = vpnEcmpSupport; }

    public String getDnsSupport() { return dnsSupport; }
    public void setDnsSupport(String dnsSupport) { this.dnsSupport = dnsSupport; }

    public String getSecurityGroupReferencingSupport() { return securityGroupReferencingSupport; }
    public void setSecurityGroupReferencingSupport(String securityGroupReferencingSupport) {
        this.securityGroupReferencingSupport = securityGroupReferencingSupport;
    }

    public String getMulticastSupport() { return multicastSupport; }
    public void setMulticastSupport(String multicastSupport) { this.multicastSupport = multicastSupport; }
}
