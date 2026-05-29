package io.github.hectorvent.floci.services.apigatewayv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/** API Gateway v2 VPC Link; Floci has no real VPC so it's provisioned AVAILABLE immediately. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VpcLink {
    private String vpcLinkId;
    private String name;
    private List<String> subnetIds;
    private List<String> securityGroupIds;
    private String vpcLinkStatus; // PENDING, AVAILABLE, DELETING, FAILED, INACTIVE
    private long createdDate;
    private Map<String, String> tags;

    public VpcLink() {}

    public String getVpcLinkId() { return vpcLinkId; }
    public void setVpcLinkId(String vpcLinkId) { this.vpcLinkId = vpcLinkId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<String> getSubnetIds() { return subnetIds; }
    public void setSubnetIds(List<String> subnetIds) { this.subnetIds = subnetIds; }

    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> securityGroupIds) { this.securityGroupIds = securityGroupIds; }

    public String getVpcLinkStatus() { return vpcLinkStatus; }
    public void setVpcLinkStatus(String vpcLinkStatus) { this.vpcLinkStatus = vpcLinkStatus; }

    public long getCreatedDate() { return createdDate; }
    public void setCreatedDate(long createdDate) { this.createdDate = createdDate; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
