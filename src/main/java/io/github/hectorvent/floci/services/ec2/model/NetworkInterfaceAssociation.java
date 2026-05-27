package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NetworkInterfaceAssociation {

    private String publicIp;
    private String allocationId;
    private String associationId;
    private String ipOwnerId;
    private String publicDnsName;
    private String carrierIp;

    public NetworkInterfaceAssociation() {}

    public String getPublicIp() { return publicIp; }
    public void setPublicIp(String publicIp) { this.publicIp = publicIp; }

    public String getAllocationId() { return allocationId; }
    public void setAllocationId(String allocationId) { this.allocationId = allocationId; }

    public String getAssociationId() { return associationId; }
    public void setAssociationId(String associationId) { this.associationId = associationId; }

    public String getIpOwnerId() { return ipOwnerId; }
    public void setIpOwnerId(String ipOwnerId) { this.ipOwnerId = ipOwnerId; }

    public String getPublicDnsName() { return publicDnsName; }
    public void setPublicDnsName(String publicDnsName) { this.publicDnsName = publicDnsName; }

    public String getCarrierIp() { return carrierIp; }
    public void setCarrierIp(String carrierIp) { this.carrierIp = carrierIp; }
}
