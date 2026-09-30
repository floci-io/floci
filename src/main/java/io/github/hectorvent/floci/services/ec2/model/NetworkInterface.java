package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NetworkInterface {

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
    private String availabilityZone;
    private String interfaceType = "interface";
    /**
     * Whether AWS itself manages this interface, rather than the account it is billed to.
     * False for anything a customer created; true for the service-owned interfaces AWS
     * creates on the customer's behalf, of which an interface VPC endpoint's is one.
     */
    private boolean requesterManaged;
    /**
     * The alias or account of the principal that created the interface, for a
     * requester-managed one. Left null rather than filled with a plausible-looking id:
     * real AWS reports an internal service account here, which floci cannot know, and an
     * invented twelve-digit account would be a wrong answer rather than a missing one.
     */
    private String requesterId;
    private List<GroupIdentifier> groups = new ArrayList<>();
    private NetworkInterfaceAttachment attachment;
    private List<Tag> tagSet = new ArrayList<>();
    private List<NetworkInterfacePrivateIpAddress> privateIpAddresses = new ArrayList<>();

    public NetworkInterface() {}

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

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

    public String getInterfaceType() { return interfaceType; }
    public void setInterfaceType(String interfaceType) { this.interfaceType = interfaceType; }

    public boolean isRequesterManaged() { return requesterManaged; }
    public void setRequesterManaged(boolean requesterManaged) { this.requesterManaged = requesterManaged; }

    public String getRequesterId() { return requesterId; }
    public void setRequesterId(String requesterId) { this.requesterId = requesterId; }

    public NetworkInterfaceAttachment getAttachment() { return attachment; }
    public void setAttachment(NetworkInterfaceAttachment attachment) { this.attachment = attachment; }

    public List<Tag> getTagSet() { return tagSet; }
    public void setTagSet(List<Tag> tagSet) { this.tagSet = tagSet; }

    public List<NetworkInterfacePrivateIpAddress> getPrivateIpAddresses() { return privateIpAddresses; }
    public void setPrivateIpAddresses(List<NetworkInterfacePrivateIpAddress> privateIpAddresses) { this.privateIpAddresses = privateIpAddresses; }
}
