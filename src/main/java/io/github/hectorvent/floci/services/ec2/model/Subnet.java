package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Subnet {

    private String subnetId;
    private String vpcId;
    private String cidrBlock;
    private String state;
    private String availabilityZone;
    private String availabilityZoneId;
    private int availableIpAddressCount;
    private boolean defaultForAz;
    private boolean mapPublicIpOnLaunch;
    private String ownerId;
    private String region;
    private String subnetArn;
    private List<Tag> tags = new ArrayList<>();

    public Subnet() {}

    public String getSubnetId() { return subnetId; }
    public void setSubnetId(String subnetId) { this.subnetId = subnetId; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getCidrBlock() { return cidrBlock; }
    public void setCidrBlock(String cidrBlock) { this.cidrBlock = cidrBlock; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

    public String getAvailabilityZoneId() { return availabilityZoneId; }
    public void setAvailabilityZoneId(String availabilityZoneId) { this.availabilityZoneId = availabilityZoneId; }

    public int getAvailableIpAddressCount() { return availableIpAddressCount; }
    public void setAvailableIpAddressCount(int availableIpAddressCount) { this.availableIpAddressCount = availableIpAddressCount; }

    public boolean isDefaultForAz() { return defaultForAz; }
    public void setDefaultForAz(boolean defaultForAz) { this.defaultForAz = defaultForAz; }

    public boolean isMapPublicIpOnLaunch() { return mapPublicIpOnLaunch; }
    public void setMapPublicIpOnLaunch(boolean mapPublicIpOnLaunch) { this.mapPublicIpOnLaunch = mapPublicIpOnLaunch; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getSubnetArn() { return subnetArn; }
    public void setSubnetArn(String subnetArn) { this.subnetArn = subnetArn; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
