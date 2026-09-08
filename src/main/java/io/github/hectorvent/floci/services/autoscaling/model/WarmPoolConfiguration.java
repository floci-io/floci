package io.github.hectorvent.floci.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A warm pool attached to an Auto Scaling group (PutWarmPool/DescribeWarmPool/
 * DeleteWarmPool). PutWarmPool is a full replace, not a merge - per botocore's
 * own documentation on MinSize ("Defaults to 0 if not specified") and PoolState
 * ("Default is Stopped"), a Put that omits a field resets it to that field's
 * declared default rather than leaving a prior value in place. MaxGroupPreparedCapacity
 * is the one field with no default: absent unless the caller sets it, and a caller
 * passing exactly -1 clears a previously-set value back to absent (the wire model's
 * own documented sentinel for "unset this").
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class WarmPoolConfiguration {

    private String autoScalingGroupName;
    private Integer maxGroupPreparedCapacity;
    private int minSize;
    private String poolState = "Stopped";
    private boolean reuseOnScaleIn;
    private String region;

    public WarmPoolConfiguration() {}

    public String getAutoScalingGroupName() { return autoScalingGroupName; }
    public void setAutoScalingGroupName(String v) { this.autoScalingGroupName = v; }

    public Integer getMaxGroupPreparedCapacity() { return maxGroupPreparedCapacity; }
    public void setMaxGroupPreparedCapacity(Integer v) { this.maxGroupPreparedCapacity = v; }

    public int getMinSize() { return minSize; }
    public void setMinSize(int v) { this.minSize = v; }

    public String getPoolState() { return poolState; }
    public void setPoolState(String v) { this.poolState = v; }

    public boolean isReuseOnScaleIn() { return reuseOnScaleIn; }
    public void setReuseOnScaleIn(boolean v) { this.reuseOnScaleIn = v; }

    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }
}
