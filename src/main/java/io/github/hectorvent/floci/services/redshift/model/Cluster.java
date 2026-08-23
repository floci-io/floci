package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class Cluster {
    private String clusterIdentifier;
    private String nodeType;
    private String masterUsername;
    private String masterPassword;
    private String clusterStatus;
    private Endpoint endpoint;
    private String clusterSubnetGroupName;
    private String clusterParameterGroupName;
    private List<String> vpcSecurityGroupIds = new ArrayList<>();

    public String getClusterIdentifier() { return clusterIdentifier; }
    public void setClusterIdentifier(String clusterIdentifier) { this.clusterIdentifier = clusterIdentifier; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public String getMasterUsername() { return masterUsername; }
    public void setMasterUsername(String masterUsername) { this.masterUsername = masterUsername; }
    public String getMasterPassword() { return masterPassword; }
    public void setMasterPassword(String masterPassword) { this.masterPassword = masterPassword; }
    public String getClusterStatus() { return clusterStatus; }
    public void setClusterStatus(String clusterStatus) { this.clusterStatus = clusterStatus; }
    public Endpoint getEndpoint() { return endpoint; }
    public void setEndpoint(Endpoint endpoint) { this.endpoint = endpoint; }
    public String getClusterSubnetGroupName() { return clusterSubnetGroupName; }
    public void setClusterSubnetGroupName(String clusterSubnetGroupName) { this.clusterSubnetGroupName = clusterSubnetGroupName; }
    public String getClusterParameterGroupName() { return clusterParameterGroupName; }
    public void setClusterParameterGroupName(String clusterParameterGroupName) { this.clusterParameterGroupName = clusterParameterGroupName; }
    public List<String> getVpcSecurityGroupIds() { return vpcSecurityGroupIds; }
    public void setVpcSecurityGroupIds(List<String> vpcSecurityGroupIds) { this.vpcSecurityGroupIds = vpcSecurityGroupIds; }

    private Map<String, String> tags = new LinkedHashMap<>();

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
