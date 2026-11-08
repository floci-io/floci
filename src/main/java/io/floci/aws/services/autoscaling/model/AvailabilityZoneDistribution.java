package io.floci.aws.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code AvailabilityZoneDistribution} member of CreateAutoScalingGroup, UpdateAutoScalingGroup
 * and the {@code AutoScalingGroup} shape.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AvailabilityZoneDistribution {

    private String capacityDistributionStrategy;

    public AvailabilityZoneDistribution() {}

    public AvailabilityZoneDistribution(String capacityDistributionStrategy) {
        this.capacityDistributionStrategy = capacityDistributionStrategy;
    }

    public String getCapacityDistributionStrategy() { return capacityDistributionStrategy; }
    public void setCapacityDistributionStrategy(String v) { this.capacityDistributionStrategy = v; }
}
