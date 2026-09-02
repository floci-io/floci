package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class ClusterSubnetGroup {
    private String clusterSubnetGroupName;
    private String description;
    private String vpcId;
    private List<String> subnetIds = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public ClusterSubnetGroup() {}

    public ClusterSubnetGroup(String clusterSubnetGroupName, String description, String vpcId, List<String> subnetIds) {
        this.clusterSubnetGroupName = clusterSubnetGroupName;
        this.description = description;
        this.vpcId = vpcId;
        this.subnetIds = subnetIds;
    }

    public String getClusterSubnetGroupName() { return clusterSubnetGroupName; }
    public void setClusterSubnetGroupName(String clusterSubnetGroupName) { this.clusterSubnetGroupName = clusterSubnetGroupName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }
    public List<String> getSubnetIds() { return subnetIds; }
    public void setSubnetIds(List<String> subnetIds) { this.subnetIds = subnetIds; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
