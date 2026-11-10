package io.floci.aws.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Address {

    private String allocationId;
    private String publicIp;
    private String domain = "vpc";
    private String publicIpv4Pool;
    private String networkBorderGroup;
    private String instanceId;
    private String associationId;
    private String networkInterfaceId;
    private String privateIpAddress;
    private String region;
    /**
     * The address AllocateAddress invented for this allocation, kept so disassociation can
     * restore it after association re-points the EIP at a reachable address.
     */
    private String allocatedPublicIp;
    private List<Tag> tags = new ArrayList<>();

    public Address() {}

    public String getAllocationId() { return allocationId; }
    public void setAllocationId(String allocationId) { this.allocationId = allocationId; }

    public String getPublicIp() { return publicIp; }
    public void setPublicIp(String publicIp) { this.publicIp = publicIp; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    // Older persisted allocations used the regional Amazon pool without recording its metadata.
    public String getPublicIpv4Pool() { return publicIpv4Pool != null ? publicIpv4Pool : "amazon"; }
    public void setPublicIpv4Pool(String publicIpv4Pool) { this.publicIpv4Pool = publicIpv4Pool; }

    public String getNetworkBorderGroup() { return networkBorderGroup != null ? networkBorderGroup : region; }
    public void setNetworkBorderGroup(String networkBorderGroup) { this.networkBorderGroup = networkBorderGroup; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getAssociationId() { return associationId; }
    public void setAssociationId(String associationId) { this.associationId = associationId; }

    public String getNetworkInterfaceId() { return networkInterfaceId; }
    public void setNetworkInterfaceId(String networkInterfaceId) { this.networkInterfaceId = networkInterfaceId; }

    public String getPrivateIpAddress() { return privateIpAddress; }
    public void setPrivateIpAddress(String privateIpAddress) { this.privateIpAddress = privateIpAddress; }

    public String getAllocatedPublicIp() { return allocatedPublicIp; }
    public void setAllocatedPublicIp(String allocatedPublicIp) { this.allocatedPublicIp = allocatedPublicIp; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
