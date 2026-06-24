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
    private List<Tag> tags = new ArrayList<>();

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

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
