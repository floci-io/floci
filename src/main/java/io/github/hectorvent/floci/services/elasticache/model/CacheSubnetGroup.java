package io.github.hectorvent.floci.services.elasticache.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CacheSubnetGroup {

    private String name;
    private String description;
    /** Taken from the subnets, which is where AWS gets it: a group cannot span VPCs. */
    private String vpcId;
    /** Subnet id to availability zone, in the order the subnets were given. */
    private Map<String, String> subnetAvailabilityZones = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public CacheSubnetGroup() {
    }

    public CacheSubnetGroup(String name, String description, String vpcId,
                            Map<String, String> subnetAvailabilityZones) {
        this.name = name;
        this.description = description;
        this.vpcId = vpcId;
        setSubnetAvailabilityZones(subnetAvailabilityZones);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public Map<String, String> getSubnetAvailabilityZones() { return subnetAvailabilityZones; }
    public void setSubnetAvailabilityZones(Map<String, String> subnetAvailabilityZones) {
        this.subnetAvailabilityZones = subnetAvailabilityZones == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(subnetAvailabilityZones);
    }
}
