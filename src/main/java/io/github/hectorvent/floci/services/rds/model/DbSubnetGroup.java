package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class DbSubnetGroup {

    private String dbSubnetGroupName;
    private String description;
    private String dbSubnetGroupArn;
    private String vpcId;
    private String subnetGroupStatus = "Complete";
    private List<String> subnetIds = new ArrayList<>();
    private Map<String, String> subnetAvailabilityZones = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public DbSubnetGroup() {}

    public DbSubnetGroup(String dbSubnetGroupName, String description, String vpcId,
                         List<String> subnetIds, Map<String, String> subnetAvailabilityZones) {
        this.dbSubnetGroupName = dbSubnetGroupName;
        this.description = description;
        this.vpcId = vpcId;
        if (subnetIds != null) {
            this.subnetIds = new ArrayList<>(subnetIds);
        }
        if (subnetAvailabilityZones != null) {
            this.subnetAvailabilityZones = new LinkedHashMap<>(subnetAvailabilityZones);
        }
    }

    public String getDbSubnetGroupName() { return dbSubnetGroupName; }
    public void setDbSubnetGroupName(String dbSubnetGroupName) { this.dbSubnetGroupName = dbSubnetGroupName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getDbSubnetGroupDescription() { return description; }
    public void setDbSubnetGroupDescription(String description) { this.description = description; }
    public String getDbSubnetGroupArn() { return dbSubnetGroupArn; }
    public void setDbSubnetGroupArn(String dbSubnetGroupArn) { this.dbSubnetGroupArn = dbSubnetGroupArn; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getSubnetGroupStatus() { return subnetGroupStatus; }
    public void setSubnetGroupStatus(String subnetGroupStatus) { this.subnetGroupStatus = subnetGroupStatus; }

    public List<String> getSubnetIds() { return List.copyOf(subnetIds); }
    public void setSubnetIds(List<String> subnetIds) {
        this.subnetIds = subnetIds != null ? new ArrayList<>(subnetIds) : new ArrayList<>();
    }

    public Map<String, String> getSubnetAvailabilityZones() { return Map.copyOf(subnetAvailabilityZones); }
    public void setSubnetAvailabilityZones(Map<String, String> subnetAvailabilityZones) {
        this.subnetAvailabilityZones = subnetAvailabilityZones != null
                ? new LinkedHashMap<>(subnetAvailabilityZones)
                : new LinkedHashMap<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>(); }
}
