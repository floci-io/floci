package io.github.hectorvent.floci.services.autoscaling.model;

/**
 * The optional scalar members that {@code CreateAutoScalingGroup} and
 * {@code UpdateAutoScalingGroup} share and that {@code DescribeAutoScalingGroups} echoes back.
 *
 * <p>Carried as one object rather than four more positional parameters on
 * {@code AutoScalingService.createAutoScalingGroup}, whose argument list is already long.
 *
 * <p>A {@code null} component means the request did not set the member. On update that means
 * "leave the stored value alone", which is UpdateAutoScalingGroup's partial-update behaviour for
 * every other member.
 *
 * <p>{@code DefaultInstanceWarmup} carries a sentinel: botocore documents {@code -1} as the value
 * that removes a previously set warmup, so it clears the stored value instead of storing -1.
 */
public record AsgOptionalFields(String desiredCapacityType,
                                Boolean capacityRebalance,
                                Integer maxInstanceLifetime,
                                Integer defaultInstanceWarmup) {

    public static final int DEFAULT_INSTANCE_WARMUP_REMOVAL_SENTINEL = -1;

    private static final AsgOptionalFields NONE = new AsgOptionalFields(null, null, null, null);

    public static AsgOptionalFields none() {
        return NONE;
    }

    /** Applies onto a freshly created group, where every absent member simply stays unset. */
    public void applyToNewGroup(AutoScalingGroup asg) {
        asg.setDesiredCapacityType(desiredCapacityType);
        asg.setCapacityRebalance(capacityRebalance);
        asg.setMaxInstanceLifetime(maxInstanceLifetime);
        asg.setDefaultInstanceWarmup(resolvedDefaultInstanceWarmup());
    }

    /** Applies onto an existing group, overwriting only the members this request set. */
    public void applyToExistingGroup(AutoScalingGroup asg) {
        if (desiredCapacityType != null) {
            asg.setDesiredCapacityType(desiredCapacityType);
        }
        if (capacityRebalance != null) {
            asg.setCapacityRebalance(capacityRebalance);
        }
        if (maxInstanceLifetime != null) {
            asg.setMaxInstanceLifetime(maxInstanceLifetime);
        }
        if (defaultInstanceWarmup != null) {
            asg.setDefaultInstanceWarmup(resolvedDefaultInstanceWarmup());
        }
    }

    private Integer resolvedDefaultInstanceWarmup() {
        if (defaultInstanceWarmup != null
                && defaultInstanceWarmup == DEFAULT_INSTANCE_WARMUP_REMOVAL_SENTINEL) {
            return null;
        }
        return defaultInstanceWarmup;
    }
}
