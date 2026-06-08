package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NatGatewayAddress {

    private String allocationId;
    private String associationId;
    private String networkInterfaceId;
    private String privateIp;
    private String publicIp;

    public NatGatewayAddress() {}

    public String getAllocationId() { return allocationId; }
    public void setAllocationId(String allocationId) { this.allocationId = allocationId; }

    public String getAssociationId() { return associationId; }
    public void setAssociationId(String associationId) { this.associationId = associationId; }

    public String getNetworkInterfaceId() { return networkInterfaceId; }
    public void setNetworkInterfaceId(String networkInterfaceId) { this.networkInterfaceId = networkInterfaceId; }

    public String getPrivateIp() { return privateIp; }
    public void setPrivateIp(String privateIp) { this.privateIp = privateIp; }

    public String getPublicIp() { return publicIp; }
    public void setPublicIp(String publicIp) { this.publicIp = publicIp; }
}
