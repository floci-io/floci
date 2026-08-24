package io.github.hectorvent.floci.services.autoscaling.model;

/**
 * lex00/floci#112: the optional CreateAutoScalingGroup/UpdateAutoScalingGroup fields that
 * {@link AutoScalingGroup} previously had no field for at all - see that class's own doc comment
 * for the oracle and the crossing that surfaced this. Bundled into one object instead of growing
 * createAutoScalingGroup/updateAutoScalingGroup's already-long positional parameter list further.
 *
 * <p>A {@code null} field means "not present in this request" - for
 * {@link #applyToExistingGroup}, that means "leave the existing group's value alone",
 * matching UpdateAutoScalingGroup's real partial-update semantics for every other field.
 */
public class AsgOptionalFields {
    private final Integer defaultInstanceWarmup;
    private final Boolean capacityRebalance;
    private final Integer maxInstanceLifetime;
    private final String serviceLinkedRoleArn;
    private final AutoScalingGroup.InstanceMaintenancePolicy instanceMaintenancePolicy;
    private final AutoScalingGroup.AvailabilityZoneDistribution availabilityZoneDistribution;
    private final AutoScalingGroup.CapacityReservationSpecification capacityReservationSpecification;
    // lex00/floci#112's round-5 re-measure: see AutoScalingGroup's own doc comment on
    // desiredCapacityType for why this exists.
    private final String desiredCapacityType;

    public AsgOptionalFields(Integer defaultInstanceWarmup, Boolean capacityRebalance,
                             Integer maxInstanceLifetime, String serviceLinkedRoleArn,
                             AutoScalingGroup.InstanceMaintenancePolicy instanceMaintenancePolicy,
                             AutoScalingGroup.AvailabilityZoneDistribution availabilityZoneDistribution,
                             AutoScalingGroup.CapacityReservationSpecification capacityReservationSpecification,
                             String desiredCapacityType) {
        this.defaultInstanceWarmup = defaultInstanceWarmup;
        this.capacityRebalance = capacityRebalance;
        this.maxInstanceLifetime = maxInstanceLifetime;
        this.serviceLinkedRoleArn = serviceLinkedRoleArn;
        this.instanceMaintenancePolicy = instanceMaintenancePolicy;
        this.availabilityZoneDistribution = availabilityZoneDistribution;
        this.capacityReservationSpecification = capacityReservationSpecification;
        this.desiredCapacityType = desiredCapacityType;
    }

    public Integer getDefaultInstanceWarmup() { return defaultInstanceWarmup; }
    public Boolean getCapacityRebalance() { return capacityRebalance; }
    public Integer getMaxInstanceLifetime() { return maxInstanceLifetime; }
    public String getServiceLinkedRoleArn() { return serviceLinkedRoleArn; }
    public AutoScalingGroup.InstanceMaintenancePolicy getInstanceMaintenancePolicy() { return instanceMaintenancePolicy; }
    public AutoScalingGroup.AvailabilityZoneDistribution getAvailabilityZoneDistribution() { return availabilityZoneDistribution; }
    public AutoScalingGroup.CapacityReservationSpecification getCapacityReservationSpecification() { return capacityReservationSpecification; }
    public String getDesiredCapacityType() { return desiredCapacityType; }

    /** Applies onto a freshly created group - every present field is set outright. */
    public void applyToNewGroup(AutoScalingGroup asg) {
        asg.setDefaultInstanceWarmup(defaultInstanceWarmup);
        asg.setCapacityRebalance(capacityRebalance);
        asg.setMaxInstanceLifetime(maxInstanceLifetime);
        asg.setServiceLinkedRoleArn(serviceLinkedRoleArn);
        asg.setInstanceMaintenancePolicy(instanceMaintenancePolicy);
        asg.setAvailabilityZoneDistribution(availabilityZoneDistribution);
        asg.setCapacityReservationSpecification(capacityReservationSpecification);
        asg.setDesiredCapacityType(desiredCapacityType);
    }

    /** Applies onto an existing group - only fields this request actually set are overwritten. */
    public void applyToExistingGroup(AutoScalingGroup asg) {
        if (defaultInstanceWarmup != null) asg.setDefaultInstanceWarmup(defaultInstanceWarmup);
        if (capacityRebalance != null) asg.setCapacityRebalance(capacityRebalance);
        if (maxInstanceLifetime != null) asg.setMaxInstanceLifetime(maxInstanceLifetime);
        if (serviceLinkedRoleArn != null) asg.setServiceLinkedRoleArn(serviceLinkedRoleArn);
        if (instanceMaintenancePolicy != null) asg.setInstanceMaintenancePolicy(instanceMaintenancePolicy);
        if (availabilityZoneDistribution != null) asg.setAvailabilityZoneDistribution(availabilityZoneDistribution);
        if (capacityReservationSpecification != null) asg.setCapacityReservationSpecification(capacityReservationSpecification);
        if (desiredCapacityType != null) asg.setDesiredCapacityType(desiredCapacityType);
    }
}
