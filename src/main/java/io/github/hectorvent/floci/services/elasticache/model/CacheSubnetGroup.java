package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ElastiCache CacheSubnetGroup — the same AWS concept as RDS's DBSubnetGroup,
 * scoping a replication group or cache cluster to a set of EC2 subnets within a VPC.
 */
@RegisterForReflection
public class CacheSubnetGroup {

    private String cacheSubnetGroupName;
    private String description;
    private String arn;
    private String vpcId;
    private List<String> subnetIds = new ArrayList<>();
    private Map<String, String> subnetAvailabilityZones = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public CacheSubnetGroup() {}

    public CacheSubnetGroup(String cacheSubnetGroupName, String description, String vpcId,
                            List<String> subnetIds, Map<String, String> subnetAvailabilityZones) {
        this.cacheSubnetGroupName = cacheSubnetGroupName;
        this.description = description;
        this.vpcId = vpcId;
        if (subnetIds != null) {
            this.subnetIds = new ArrayList<>(subnetIds);
        }
        if (subnetAvailabilityZones != null) {
            this.subnetAvailabilityZones = new LinkedHashMap<>(subnetAvailabilityZones);
        }
    }

    public String getCacheSubnetGroupName() { return cacheSubnetGroupName; }
    public void setCacheSubnetGroupName(String cacheSubnetGroupName) { this.cacheSubnetGroupName = cacheSubnetGroupName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCacheSubnetGroupDescription() { return description; }
    public void setCacheSubnetGroupDescription(String description) { this.description = description; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

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

    /**
     * The resource's own tags. Present on the model rather than in a side store on purpose:
     * {@code TaggedResourceScanner} finds a resource for Resource Groups Tagging's
     * {@code GetResources} by looking for a tag collection and an {@code arn} field on the
     * persisted model, so a {@code tags} field beside the existing {@code arn} is all that is
     * needed for this group to appear in an estate-wide tag scan.
     */
    public Map<String, String> getTags() { return Map.copyOf(tags); }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }
}
