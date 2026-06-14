package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
public class DbSubnetGroup {

    private String dbSubnetGroupName;
    private String dbSubnetGroupDescription;
    private String vpcId;
    private List<String> subnetIds = new ArrayList<>();

    public DbSubnetGroup() {}

    public DbSubnetGroup(String dbSubnetGroupName, String dbSubnetGroupDescription,
                         String vpcId, List<String> subnetIds) {
        this.dbSubnetGroupName = dbSubnetGroupName;
        this.dbSubnetGroupDescription = dbSubnetGroupDescription;
        this.vpcId = vpcId;
        this.subnetIds = subnetIds != null ? new ArrayList<>(subnetIds) : new ArrayList<>();
    }

    public String getDbSubnetGroupName() { return dbSubnetGroupName; }
    public void setDbSubnetGroupName(String dbSubnetGroupName) { this.dbSubnetGroupName = dbSubnetGroupName; }

    public String getDbSubnetGroupDescription() { return dbSubnetGroupDescription; }
    public void setDbSubnetGroupDescription(String dbSubnetGroupDescription) { this.dbSubnetGroupDescription = dbSubnetGroupDescription; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public List<String> getSubnetIds() { return List.copyOf(subnetIds); }
    public void setSubnetIds(List<String> subnetIds) {
        this.subnetIds = subnetIds != null ? new ArrayList<>(subnetIds) : new ArrayList<>();
    }
}
