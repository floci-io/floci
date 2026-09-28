package io.github.hectorvent.floci.services.dms.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReplicationSubnetGroup {

    private String replicationSubnetGroupIdentifier;
    private String replicationSubnetGroupDescription;
    private String vpcId;
    private String subnetGroupStatus = "Complete";
    private List<String> subnetIds = new ArrayList<>();
    private Map<String, String> subnetAvailabilityZones = new LinkedHashMap<>();
    private List<String> supportedNetworkTypes = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public ReplicationSubnetGroup() {
    }

    public String getReplicationSubnetGroupIdentifier() {
        return replicationSubnetGroupIdentifier;
    }

    public void setReplicationSubnetGroupIdentifier(String replicationSubnetGroupIdentifier) {
        this.replicationSubnetGroupIdentifier = replicationSubnetGroupIdentifier;
    }

    public String getReplicationSubnetGroupDescription() {
        return replicationSubnetGroupDescription;
    }

    public void setReplicationSubnetGroupDescription(String replicationSubnetGroupDescription) {
        this.replicationSubnetGroupDescription = replicationSubnetGroupDescription;
    }

    public String getVpcId() {
        return vpcId;
    }

    public void setVpcId(String vpcId) {
        this.vpcId = vpcId;
    }

    public String getSubnetGroupStatus() {
        return subnetGroupStatus;
    }

    public void setSubnetGroupStatus(String subnetGroupStatus) {
        this.subnetGroupStatus = subnetGroupStatus;
    }

    public List<String> getSubnetIds() {
        return List.copyOf(subnetIds);
    }

    public void setSubnetIds(List<String> subnetIds) {
        this.subnetIds = subnetIds != null ? new ArrayList<>(subnetIds) : new ArrayList<>();
    }

    public Map<String, String> getSubnetAvailabilityZones() {
        return Map.copyOf(subnetAvailabilityZones);
    }

    public void setSubnetAvailabilityZones(Map<String, String> subnetAvailabilityZones) {
        this.subnetAvailabilityZones = subnetAvailabilityZones != null
                ? new LinkedHashMap<>(subnetAvailabilityZones)
                : new LinkedHashMap<>();
    }

    public List<String> getSupportedNetworkTypes() {
        return List.copyOf(supportedNetworkTypes);
    }

    public void setSupportedNetworkTypes(List<String> supportedNetworkTypes) {
        this.supportedNetworkTypes = supportedNetworkTypes != null
                ? new ArrayList<>(supportedNetworkTypes)
                : new ArrayList<>();
    }

    public Map<String, String> getTags() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }
}
