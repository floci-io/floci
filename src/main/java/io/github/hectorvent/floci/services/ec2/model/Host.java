package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An EC2 Dedicated Host from {@code AllocateHosts}. Real AWS allocates synchronously enough
 * that the host is {@code available} on the first describe; it is reported that way here too.
 * A released host stays in {@code DescribeHosts} with state {@code released}, as on AWS.
 *
 * <p>Exactly one of {@code instanceFamily}/{@code instanceType} is set, whichever the caller
 * allocated with: the AWS provider treats both as optional non-computed arguments, so echoing
 * one it did not send would be a perpetual diff.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Host {

    private String hostId;
    private String ownerId;
    private String region;
    private String availabilityZone;
    private String instanceFamily;
    private String instanceType;
    private String autoPlacement = "off";
    private String hostRecovery = "off";
    private String hostMaintenance = "on";
    private String outpostArn;
    private String assetId;
    private String state = "available";
    private Instant allocationTime;
    private Instant releaseTime;
    private List<Tag> tags = new ArrayList<>();

    public Host() {}

    public String getHostId() { return hostId; }
    public void setHostId(String hostId) { this.hostId = hostId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

    public String getInstanceFamily() { return instanceFamily; }
    public void setInstanceFamily(String instanceFamily) { this.instanceFamily = instanceFamily; }

    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String instanceType) { this.instanceType = instanceType; }

    public String getAutoPlacement() { return autoPlacement; }
    public void setAutoPlacement(String autoPlacement) { this.autoPlacement = autoPlacement; }

    public String getHostRecovery() { return hostRecovery; }
    public void setHostRecovery(String hostRecovery) { this.hostRecovery = hostRecovery; }

    public String getHostMaintenance() { return hostMaintenance; }
    public void setHostMaintenance(String hostMaintenance) { this.hostMaintenance = hostMaintenance; }

    public String getOutpostArn() { return outpostArn; }
    public void setOutpostArn(String outpostArn) { this.outpostArn = outpostArn; }

    public String getAssetId() { return assetId; }
    public void setAssetId(String assetId) { this.assetId = assetId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Instant getAllocationTime() { return allocationTime; }
    public void setAllocationTime(Instant allocationTime) { this.allocationTime = allocationTime; }

    public Instant getReleaseTime() { return releaseTime; }
    public void setReleaseTime(Instant releaseTime) { this.releaseTime = releaseTime; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
