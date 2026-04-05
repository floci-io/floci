package io.github.hectorvent.floci.services.ec2.model;

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
}
