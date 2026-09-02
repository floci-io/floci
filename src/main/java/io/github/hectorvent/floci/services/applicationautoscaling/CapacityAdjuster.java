package io.github.hectorvent.floci.services.applicationautoscaling;

/**
 * Reads and writes the actual capacity behind one {@code ScalableDimension}.
 *
 * <p>{@link ScalingPolicyAlarmActionHandler} discovers every CDI bean implementing this
 * interface and delegates to the one whose {@link #supports(String)} matches a scalable
 * target's dimension. A dimension with no adjuster stays exactly as inert as it is today —
 * this is the seam a future DynamoDB/Lambda/etc. adjuster plugs into without touching the
 * alarm-evaluation or scaling-math code.</p>
 */
public interface CapacityAdjuster {

    boolean supports(String scalableDimension);

    int getCurrentCapacity(String resourceId, String region);

    void setCapacity(String resourceId, int capacity, String region);
}
