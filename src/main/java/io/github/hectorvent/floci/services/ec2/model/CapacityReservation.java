package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A reservation of EC2 instance capacity in a specific Availability Zone, created ahead of
 * time so a later {@code RunInstances} (or an Auto Scaling warm pool / launch template) is
 * guaranteed the capacity it targets.
 *
 * <p>Real AWS transitions a Capacity Reservation through {@code payment-pending}/
 * {@code assessing} before {@code active}; nothing about creating one is slow here, so it is
 * {@code active} on the create response and on the first describe, the same synchronous-create
 * simplification the other regional EC2 resources make.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CapacityReservation {

    private String capacityReservationId;
    private String ownerId;
    private String capacityReservationArn;
    private String availabilityZone;
    private String availabilityZoneId;
    private String instanceType;
    private String instancePlatform;
    private String tenancy = "default";
    private int totalInstanceCount;
    private int availableInstanceCount;
    private boolean ebsOptimized;
    private boolean ephemeralStorage;
    private String state = "active";
    private Instant startDate;
    private Instant endDate;
    private String endDateType = "unlimited";
    private String instanceMatchCriteria = "open";
    private Instant createDate;
    private String outpostArn;
    private String placementGroupArn;
    private String region;
    private List<Tag> tags = new ArrayList<>();

    public CapacityReservation() {}

    public String getCapacityReservationId() { return capacityReservationId; }
    public void setCapacityReservationId(String capacityReservationId) { this.capacityReservationId = capacityReservationId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getCapacityReservationArn() { return capacityReservationArn; }
    public void setCapacityReservationArn(String capacityReservationArn) { this.capacityReservationArn = capacityReservationArn; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }
    public String getAvailabilityZoneId() { return availabilityZoneId; }
    public void setAvailabilityZoneId(String availabilityZoneId) { this.availabilityZoneId = availabilityZoneId; }

    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String instanceType) { this.instanceType = instanceType; }

    public String getInstancePlatform() { return instancePlatform; }
    public void setInstancePlatform(String instancePlatform) { this.instancePlatform = instancePlatform; }

    public String getTenancy() { return tenancy; }
    public void setTenancy(String tenancy) { this.tenancy = tenancy; }

    public int getTotalInstanceCount() { return totalInstanceCount; }
    public void setTotalInstanceCount(int totalInstanceCount) { this.totalInstanceCount = totalInstanceCount; }

    public int getAvailableInstanceCount() { return availableInstanceCount; }
    public void setAvailableInstanceCount(int availableInstanceCount) { this.availableInstanceCount = availableInstanceCount; }

    public boolean isEbsOptimized() { return ebsOptimized; }
    public void setEbsOptimized(boolean ebsOptimized) { this.ebsOptimized = ebsOptimized; }

    public boolean isEphemeralStorage() { return ephemeralStorage; }
    public void setEphemeralStorage(boolean ephemeralStorage) { this.ephemeralStorage = ephemeralStorage; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Instant getStartDate() { return startDate; }
    public void setStartDate(Instant startDate) { this.startDate = startDate; }

    public Instant getEndDate() { return endDate; }
    public void setEndDate(Instant endDate) { this.endDate = endDate; }

    public String getEndDateType() { return endDateType; }
    public void setEndDateType(String endDateType) { this.endDateType = endDateType; }

    public String getInstanceMatchCriteria() { return instanceMatchCriteria; }
    public void setInstanceMatchCriteria(String instanceMatchCriteria) { this.instanceMatchCriteria = instanceMatchCriteria; }

    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }

    public String getOutpostArn() { return outpostArn; }
    public void setOutpostArn(String outpostArn) { this.outpostArn = outpostArn; }

    public String getPlacementGroupArn() { return placementGroupArn; }
    public void setPlacementGroupArn(String placementGroupArn) { this.placementGroupArn = placementGroupArn; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
