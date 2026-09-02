package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One entry of a NAT gateway's {@code natGatewayAddressSet}. A public gateway's entry carries the
 * Elastic IP it was given plus the private address and interface AWS creates for it; a private
 * gateway has no Elastic IP and so no {@code publicIp} or {@code associationId}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NatGatewayAddress {

    private String allocationId;
    private String associationId;
    private String networkInterfaceId;
    private String privateIp;
    private String publicIp;
    private boolean primary = true;
    private String status = "succeeded";

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

    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
