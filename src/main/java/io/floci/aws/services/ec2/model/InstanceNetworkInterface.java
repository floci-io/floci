package io.floci.aws.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstanceNetworkInterface {

    private String networkInterfaceId;
    private String subnetId;
    private String vpcId;
    private String description;
    private String ownerId;
    private String status = "in-use";
    private String macAddress;
    private String privateIpAddress;
    private String privateDnsName;
    private boolean sourceDestCheck = true;
    private List<GroupIdentifier> groups = new ArrayList<>();
    private List<String> ipv6Addresses = new ArrayList<>();
    private List<String> ipv4Prefixes = new ArrayList<>();
    private List<String> subnetIpv6CidrBlocks = new ArrayList<>();
    private List<String> vpcIpv6CidrBlocks = new ArrayList<>();
    private String attachmentId;
    private int deviceIndex;
    private String attachTime;
    private boolean deleteOnTermination = true;

    public InstanceNetworkInterface() {}

    public String getNetworkInterfaceId() { return networkInterfaceId; }
    public void setNetworkInterfaceId(String networkInterfaceId) { this.networkInterfaceId = networkInterfaceId; }

    public String getSubnetId() { return subnetId; }
    public void setSubnetId(String subnetId) { this.subnetId = subnetId; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMacAddress() { return macAddress; }
    public void setMacAddress(String macAddress) { this.macAddress = macAddress; }

    public String getPrivateIpAddress() { return privateIpAddress; }
    public void setPrivateIpAddress(String privateIpAddress) { this.privateIpAddress = privateIpAddress; }

    public String getPrivateDnsName() { return privateDnsName; }
    public void setPrivateDnsName(String privateDnsName) { this.privateDnsName = privateDnsName; }

    public boolean isSourceDestCheck() { return sourceDestCheck; }
    public void setSourceDestCheck(boolean sourceDestCheck) { this.sourceDestCheck = sourceDestCheck; }

    public List<GroupIdentifier> getGroups() { return groups; }
    public void setGroups(List<GroupIdentifier> groups) { this.groups = groups; }

    public String getAttachmentId() { return attachmentId; }
    public void setAttachmentId(String attachmentId) { this.attachmentId = attachmentId; }

    public int getDeviceIndex() { return deviceIndex; }
    public void setDeviceIndex(int deviceIndex) { this.deviceIndex = deviceIndex; }

    public String getAttachTime() { return attachTime; }
    public void setAttachTime(String attachTime) { this.attachTime = attachTime; }

    public boolean isDeleteOnTermination() { return deleteOnTermination; }
    public void setDeleteOnTermination(boolean deleteOnTermination) { this.deleteOnTermination = deleteOnTermination; }

    public List<String> getIpv6Addresses() { return ipv6Addresses; }
    public void setIpv6Addresses(List<String> ipv6Addresses) {
        this.ipv6Addresses = ipv6Addresses != null ? ipv6Addresses : new ArrayList<>();
    }

    public List<String> getIpv4Prefixes() { return ipv4Prefixes; }
    public void setIpv4Prefixes(List<String> ipv4Prefixes) { this.ipv4Prefixes = ipv4Prefixes; }

    public List<String> getSubnetIpv6CidrBlocks() { return subnetIpv6CidrBlocks; }
    public void setSubnetIpv6CidrBlocks(List<String> subnetIpv6CidrBlocks) {
        this.subnetIpv6CidrBlocks = subnetIpv6CidrBlocks != null ? subnetIpv6CidrBlocks : new ArrayList<>();
    }

    public List<String> getVpcIpv6CidrBlocks() { return vpcIpv6CidrBlocks; }
    public void setVpcIpv6CidrBlocks(List<String> vpcIpv6CidrBlocks) {
        this.vpcIpv6CidrBlocks = vpcIpv6CidrBlocks != null ? vpcIpv6CidrBlocks : new ArrayList<>();
    }

    /** A copy with lists of its own, so a change to the copy does not reach this interface. */
    public InstanceNetworkInterface copy() {
        InstanceNetworkInterface copy = new InstanceNetworkInterface();
        copy.networkInterfaceId = networkInterfaceId;
        copy.subnetId = subnetId;
        copy.vpcId = vpcId;
        copy.description = description;
        copy.ownerId = ownerId;
        copy.status = status;
        copy.macAddress = macAddress;
        copy.privateIpAddress = privateIpAddress;
        copy.privateDnsName = privateDnsName;
        copy.sourceDestCheck = sourceDestCheck;
        copy.groups = new ArrayList<>(groups);
        copy.ipv6Addresses = new ArrayList<>(ipv6Addresses);
        copy.ipv4Prefixes = new ArrayList<>(ipv4Prefixes);
        copy.subnetIpv6CidrBlocks = new ArrayList<>(subnetIpv6CidrBlocks);
        copy.vpcIpv6CidrBlocks = new ArrayList<>(vpcIpv6CidrBlocks);
        copy.attachmentId = attachmentId;
        copy.deviceIndex = deviceIndex;
        copy.attachTime = attachTime;
        copy.deleteOnTermination = deleteOnTermination;
        return copy;
    }
}
