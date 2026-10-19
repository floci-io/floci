package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** An EC2 Instance Connect Endpoint: the tunnel entry point into one subnet's VPC. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstanceConnectEndpoint {

    private String instanceConnectEndpointId;
    private String instanceConnectEndpointArn;
    private String ownerId;
    private String state;
    private String dnsName;
    private String fipsDnsName;
    private List<String> networkInterfaceIds = new ArrayList<>();
    private String vpcId;
    private String subnetId;
    private String availabilityZone;
    private String availabilityZoneId;
    private Instant createdAt;
    private boolean preserveClientIp;
    private List<String> securityGroupIds = new ArrayList<>();
    private String ipAddressType;
    private String region;
    /** The endpoint's interface address in its subnet: the source instances see when the client IP is not preserved. */
    private String privateIpAddress;
    private String clientToken;
    private List<Tag> tags = new ArrayList<>();

    public InstanceConnectEndpoint() {}

    public String getInstanceConnectEndpointId() { return instanceConnectEndpointId; }
    public void setInstanceConnectEndpointId(String id) { this.instanceConnectEndpointId = id; }

    public String getInstanceConnectEndpointArn() { return instanceConnectEndpointArn; }
    public void setInstanceConnectEndpointArn(String arn) { this.instanceConnectEndpointArn = arn; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getDnsName() { return dnsName; }
    public void setDnsName(String dnsName) { this.dnsName = dnsName; }

    public String getFipsDnsName() { return fipsDnsName; }
    public void setFipsDnsName(String fipsDnsName) { this.fipsDnsName = fipsDnsName; }

    public List<String> getNetworkInterfaceIds() { return networkInterfaceIds; }
    public void setNetworkInterfaceIds(List<String> ids) { this.networkInterfaceIds = ids != null ? ids : new ArrayList<>(); }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getSubnetId() { return subnetId; }
    public void setSubnetId(String subnetId) { this.subnetId = subnetId; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

    public String getAvailabilityZoneId() { return availabilityZoneId; }
    public void setAvailabilityZoneId(String availabilityZoneId) { this.availabilityZoneId = availabilityZoneId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public boolean isPreserveClientIp() { return preserveClientIp; }
    public void setPreserveClientIp(boolean preserveClientIp) { this.preserveClientIp = preserveClientIp; }

    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> ids) { this.securityGroupIds = ids != null ? ids : new ArrayList<>(); }

    public String getIpAddressType() { return ipAddressType; }
    public void setIpAddressType(String ipAddressType) { this.ipAddressType = ipAddressType; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getPrivateIpAddress() { return privateIpAddress; }
    public void setPrivateIpAddress(String privateIpAddress) { this.privateIpAddress = privateIpAddress; }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags != null ? tags : new ArrayList<>(); }
}
