package io.github.hectorvent.floci.services.efs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@RegisterForReflection
public class MountTarget {

    private String availabilityZoneId;
    private String availabilityZoneName;
    private String fileSystemId;
    private String ipAddress;
    private IpAddressType ipAddressType;
    private String ipv6Address;
    private LifeCycleState lifeCycleState;
    private String mountTargetId;
    private String networkInterfaceId;
    private String ownerId;
    private List<String> securityGroups;
    private String subnetId;
    private String vpcId;

    public String getAvailabilityZoneId() {
        return availabilityZoneId;
    }

    public void setAvailabilityZoneId(String availabilityZoneId) {
        this.availabilityZoneId = availabilityZoneId;
    }

    public String getAvailabilityZoneName() {
        return availabilityZoneName;
    }

    public void setAvailabilityZoneName(String availabilityZoneName) {
        this.availabilityZoneName = availabilityZoneName;
    }

    public String getFileSystemId() {
        return fileSystemId;
    }

    public void setFileSystemId(String fileSystemId) {
        this.fileSystemId = fileSystemId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public IpAddressType getIpAddressType() {
        return ipAddressType;
    }

    public void setIpAddressType(IpAddressType ipAddressType) {
        this.ipAddressType = ipAddressType;
    }

    public String getIpv6Address() {
        return ipv6Address;
    }

    public void setIpv6Address(String ipv6Address) {
        this.ipv6Address = ipv6Address;
    }

    public LifeCycleState getLifeCycleState() {
        return lifeCycleState;
    }

    public void setLifeCycleState(LifeCycleState lifeCycleState) {
        this.lifeCycleState = lifeCycleState;
    }

    public String getMountTargetId() {
        return mountTargetId;
    }

    public void setMountTargetId(String mountTargetId) {
        this.mountTargetId = mountTargetId;
    }

    public String getNetworkInterfaceId() {
        return networkInterfaceId;
    }

    public void setNetworkInterfaceId(String networkInterfaceId) {
        this.networkInterfaceId = networkInterfaceId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public List<String> getSecurityGroups() {
        return securityGroups;
    }

    public void setSecurityGroups(List<String> securityGroups) {
        this.securityGroups = securityGroups;
    }

    public String getSubnetId() {
        return subnetId;
    }

    public void setSubnetId(String subnetId) {
        this.subnetId = subnetId;
    }

    public String getVpcId() {
        return vpcId;
    }

    public void setVpcId(String vpcId) {
        this.vpcId = vpcId;
    }
}