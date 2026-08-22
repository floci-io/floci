package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NatGateway {

    private String natGatewayId;
    private String subnetId;
    private String vpcId;
    private String allocationId;
    private String state = "available";
    private String connectivityType = "public";
    /**
     * {@code zonal} or {@code regional}. AWS always answers with one of the two, and the Terraform
     * provider switches on it before it reads {@code subnetId} or the address set — an absent
     * value leaves {@code subnet_id} and {@code allocation_id} unset in state, which reads as a
     * forced replacement on the next plan.
     */
    private String availabilityMode = "zonal";
    private Instant createTime;
    private String region;
    private String networkInterfaceId;
    private String privateIp;
    private String publicIp;
    private String associationId;
    private String addressStatus = "succeeded";
    private List<Tag> tags = new ArrayList<>();

    public NatGateway() {}

    public String getNatGatewayId() { return natGatewayId; }
    public void setNatGatewayId(String natGatewayId) { this.natGatewayId = natGatewayId; }

    public String getSubnetId() { return subnetId; }
    public void setSubnetId(String subnetId) { this.subnetId = subnetId; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getAllocationId() { return allocationId; }
    public void setAllocationId(String allocationId) { this.allocationId = allocationId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getConnectivityType() { return connectivityType; }
    public void setConnectivityType(String connectivityType) { this.connectivityType = connectivityType; }

    public String getAvailabilityMode() { return availabilityMode; }
    public void setAvailabilityMode(String availabilityMode) { this.availabilityMode = availabilityMode; }

    public String getNetworkInterfaceId() { return networkInterfaceId; }
    public void setNetworkInterfaceId(String networkInterfaceId) { this.networkInterfaceId = networkInterfaceId; }

    public String getPrivateIp() { return privateIp; }
    public void setPrivateIp(String privateIp) { this.privateIp = privateIp; }

    public String getPublicIp() { return publicIp; }
    public void setPublicIp(String publicIp) { this.publicIp = publicIp; }

    public String getAssociationId() { return associationId; }
    public void setAssociationId(String associationId) { this.associationId = associationId; }

    public String getAddressStatus() { return addressStatus; }
    public void setAddressStatus(String addressStatus) { this.addressStatus = addressStatus; }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
