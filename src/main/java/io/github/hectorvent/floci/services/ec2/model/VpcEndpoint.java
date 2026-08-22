package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VpcEndpoint {

    private String vpcEndpointId;
    private String vpcId;
    private String serviceName;
    private String vpcEndpointType = "Gateway";
    private String state = "available";
    private Instant creationTimestamp;
    private String region;
    private List<String> routeTableIds = new ArrayList<>();
    private List<String> subnetIds = new ArrayList<>();
    private List<String> securityGroupIds = new ArrayList<>();
    private boolean privateDnsEnabled;
    /**
     * The endpoint's access policy. AWS attaches a full-access document when the caller supplies
     * none and returns it on every describe, so an endpoint created without a policy still reads
     * one back; {@code ModifyVpcEndpoint} with {@code ResetPolicy} restores it.
     */
    private String policyDocument;
    private String ownerId;
    private String ipAddressType = "ipv4";
    private String dnsRecordIpType = "ipv4";
    private String serviceRegion;
    private boolean requesterManaged;
    /**
     * The IPv4/IPv6 addresses the caller pinned per subnet via {@code CreateVpcEndpoint}'s
     * {@code SubnetConfiguration} parameter. Per {@code API_SubnetConfiguration.html}, an address
     * given at creation is assigned to the endpoint's network interface in that subnet and must be
     * readable back unchanged on every subsequent describe; a subnet with no explicit configuration
     * has no entry here and keeps floci's synthesized address.
     */
    private List<VpcEndpointSubnetConfiguration> subnetConfigurations = new ArrayList<>();
    private List<Tag> tags = new ArrayList<>();

    /** The full-access document AWS attaches to an endpoint created without an explicit policy. */
    public static final String DEFAULT_POLICY_DOCUMENT =
            "{\"Version\":\"2008-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":\"*\",\"Action\":\"*\",\"Resource\":\"*\"}]}";

    public VpcEndpoint() {}

    public String getVpcEndpointId() { return vpcEndpointId; }
    public void setVpcEndpointId(String vpcEndpointId) { this.vpcEndpointId = vpcEndpointId; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getVpcEndpointType() { return vpcEndpointType; }
    public void setVpcEndpointType(String vpcEndpointType) { this.vpcEndpointType = vpcEndpointType; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Instant getCreationTimestamp() { return creationTimestamp; }
    public void setCreationTimestamp(Instant creationTimestamp) { this.creationTimestamp = creationTimestamp; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<String> getRouteTableIds() { return routeTableIds; }
    public void setRouteTableIds(List<String> routeTableIds) { this.routeTableIds = routeTableIds; }

    public List<String> getSubnetIds() { return subnetIds; }
    public void setSubnetIds(List<String> subnetIds) { this.subnetIds = subnetIds; }

    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> securityGroupIds) { this.securityGroupIds = securityGroupIds; }

    public boolean isPrivateDnsEnabled() { return privateDnsEnabled; }
    public void setPrivateDnsEnabled(boolean privateDnsEnabled) { this.privateDnsEnabled = privateDnsEnabled; }

    public String getPolicyDocument() { return policyDocument; }
    public void setPolicyDocument(String policyDocument) { this.policyDocument = policyDocument; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getIpAddressType() { return ipAddressType; }
    public void setIpAddressType(String ipAddressType) { this.ipAddressType = ipAddressType; }

    public String getDnsRecordIpType() { return dnsRecordIpType; }
    public void setDnsRecordIpType(String dnsRecordIpType) { this.dnsRecordIpType = dnsRecordIpType; }

    public String getServiceRegion() { return serviceRegion; }
    public void setServiceRegion(String serviceRegion) { this.serviceRegion = serviceRegion; }

    public boolean isRequesterManaged() { return requesterManaged; }
    public void setRequesterManaged(boolean requesterManaged) { this.requesterManaged = requesterManaged; }

    public List<VpcEndpointSubnetConfiguration> getSubnetConfigurations() { return subnetConfigurations; }
    public void setSubnetConfigurations(List<VpcEndpointSubnetConfiguration> subnetConfigurations) {
        this.subnetConfigurations = subnetConfigurations;
    }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
