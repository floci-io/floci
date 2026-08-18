package io.github.hectorvent.floci.services.redshift.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RedshiftSubnetGroup {

    private String clusterSubnetGroupName;
    private String description;
    private String vpcId;
    private String subnetGroupStatus;
    private List<String> subnetIds = new ArrayList<>();
    private Map<String, String> subnetAvailabilityZones = new LinkedHashMap<>();
    private List<String> supportedClusterIpAddressTypes = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getClusterSubnetGroupName() { return clusterSubnetGroupName; }
    public void setClusterSubnetGroupName(String clusterSubnetGroupName) {
        this.clusterSubnetGroupName = clusterSubnetGroupName;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getSubnetGroupStatus() { return subnetGroupStatus; }
    public void setSubnetGroupStatus(String subnetGroupStatus) { this.subnetGroupStatus = subnetGroupStatus; }

    public List<String> getSubnetIds() { return subnetIds; }
    public void setSubnetIds(List<String> subnetIds) { this.subnetIds = subnetIds; }

    public Map<String, String> getSubnetAvailabilityZones() { return subnetAvailabilityZones; }
    public void setSubnetAvailabilityZones(Map<String, String> subnetAvailabilityZones) {
        this.subnetAvailabilityZones = subnetAvailabilityZones;
    }

    public List<String> getSupportedClusterIpAddressTypes() { return supportedClusterIpAddressTypes; }
    public void setSupportedClusterIpAddressTypes(List<String> supportedClusterIpAddressTypes) {
        this.supportedClusterIpAddressTypes = supportedClusterIpAddressTypes;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
