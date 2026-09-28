package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResourcesVpcConfig {

    @JsonProperty("subnetIds")
    private List<String> subnetIds;

    @JsonProperty("securityGroupIds")
    private List<String> securityGroupIds;

    @JsonProperty("clusterSecurityGroupId")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String clusterSecurityGroupId;

    @JsonProperty("vpcId")
    private String vpcId;

    @JsonProperty("endpointPublicAccess")
    private Boolean endpointPublicAccess;

    @JsonProperty("endpointPrivateAccess")
    private Boolean endpointPrivateAccess;

    @JsonProperty("publicAccessCidrs")
    private List<String> publicAccessCidrs;

    public ResourcesVpcConfig() {}

    public List<String> getSubnetIds() { return subnetIds; }
    public void setSubnetIds(List<String> subnetIds) { this.subnetIds = subnetIds; }

    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> securityGroupIds) { this.securityGroupIds = securityGroupIds; }

    public String getClusterSecurityGroupId() { return clusterSecurityGroupId; }
    public void setClusterSecurityGroupId(String clusterSecurityGroupId) { this.clusterSecurityGroupId = clusterSecurityGroupId; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public Boolean getEndpointPublicAccess() { return endpointPublicAccess; }
    public void setEndpointPublicAccess(Boolean endpointPublicAccess) { this.endpointPublicAccess = endpointPublicAccess; }

    public Boolean getEndpointPrivateAccess() { return endpointPrivateAccess; }
    public void setEndpointPrivateAccess(Boolean endpointPrivateAccess) { this.endpointPrivateAccess = endpointPrivateAccess; }

    public List<String> getPublicAccessCidrs() { return publicAccessCidrs; }
    public void setPublicAccessCidrs(List<String> publicAccessCidrs) { this.publicAccessCidrs = publicAccessCidrs; }
}
