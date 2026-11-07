package io.floci.aws.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code InstanceMaintenancePolicy} member of CreateAutoScalingGroup, UpdateAutoScalingGroup
 * and the {@code AutoScalingGroup} shape. Botocore documents {@code -1} on either percentage as the
 * value that clears a previously set policy.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstanceMaintenancePolicy {

    public static final int REMOVAL_SENTINEL = -1;

    private Integer minHealthyPercentage;
    private Integer maxHealthyPercentage;

    public InstanceMaintenancePolicy() {}

    public InstanceMaintenancePolicy(Integer minHealthyPercentage, Integer maxHealthyPercentage) {
        this.minHealthyPercentage = minHealthyPercentage;
        this.maxHealthyPercentage = maxHealthyPercentage;
    }

    public Integer getMinHealthyPercentage() { return minHealthyPercentage; }
    public void setMinHealthyPercentage(Integer v) { this.minHealthyPercentage = v; }

    public Integer getMaxHealthyPercentage() { return maxHealthyPercentage; }
    public void setMaxHealthyPercentage(Integer v) { this.maxHealthyPercentage = v; }

    @JsonIgnore
    public boolean isRemoval() {
        return Integer.valueOf(REMOVAL_SENTINEL).equals(minHealthyPercentage)
                && Integer.valueOf(REMOVAL_SENTINEL).equals(maxHealthyPercentage);
    }
}
