package io.github.hectorvent.floci.services.applicationautoscaling.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.ArrayList;
import java.util.List;

/**
 * A scaling policy attached to a scalable target.
 *
 * <p>Note that {@code policyArn} uses the {@code autoscaling} service name rather than
 * {@code application-autoscaling} — the two ARN families differ, which AWS does not
 * document in one place.</p>
 *
 * @see <a href="https://docs.aws.amazon.com/autoscaling/application/APIReference/API_ScalingPolicy.html">ScalingPolicy</a>
 */
@RegisterForReflection
public class ScalingPolicy {

    private String policyArn;
    private String policyName;
    private String policyType;
    private String serviceNamespace;
    private String resourceId;
    private String scalableDimension;
    private double creationTime;
    private List<Alarm> alarms = new ArrayList<>();
    private TargetTrackingConfiguration targetTrackingConfiguration;
    private StepScalingConfiguration stepScalingConfiguration;
    private double lastScaleOutTime;
    private double lastScaleInTime;
    private Integer lastAppliedCapacity;
    private Integer lastScaleOutCapacity;

    public String getPolicyArn() { return policyArn; }
    public void setPolicyArn(String v) { this.policyArn = v; }

    public String getPolicyName() { return policyName; }
    public void setPolicyName(String v) { this.policyName = v; }

    public String getPolicyType() { return policyType; }
    public void setPolicyType(String v) { this.policyType = v; }

    public String getServiceNamespace() { return serviceNamespace; }
    public void setServiceNamespace(String v) { this.serviceNamespace = v; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String v) { this.resourceId = v; }

    public String getScalableDimension() { return scalableDimension; }
    public void setScalableDimension(String v) { this.scalableDimension = v; }

    public double getCreationTime() { return creationTime; }
    public void setCreationTime(double v) { this.creationTime = v; }

    public List<Alarm> getAlarms() { return alarms; }
    public void setAlarms(List<Alarm> v) { this.alarms = v == null ? new ArrayList<>() : new ArrayList<>(v); }

    public TargetTrackingConfiguration getTargetTrackingConfiguration() { return targetTrackingConfiguration; }
    public void setTargetTrackingConfiguration(TargetTrackingConfiguration v) { this.targetTrackingConfiguration = v; }

    public StepScalingConfiguration getStepScalingConfiguration() { return stepScalingConfiguration; }
    public void setStepScalingConfiguration(StepScalingConfiguration v) { this.stepScalingConfiguration = v; }

    /** Epoch seconds of this policy's last scale-out action; 0 if it has never scaled out.
     * Used to enforce {@code ScaleOutCooldown} / {@code Cooldown}. */
    public double getLastScaleOutTime() { return lastScaleOutTime; }
    public void setLastScaleOutTime(double v) { this.lastScaleOutTime = v; }

    /** Epoch seconds of this policy's last scale-in action; 0 if it has never scaled in.
     * Used to enforce {@code ScaleInCooldown} / {@code Cooldown}. */
    public double getLastScaleInTime() { return lastScaleInTime; }
    public void setLastScaleInTime(double v) { this.lastScaleInTime = v; }

    /** The capacity this policy's last successful action (scale-out <em>or</em> scale-in) set;
     * {@code null} if it has never acted. Detects external interference: if the target's actual
     * current capacity no longer matches this, something other than this policy's own last
     * action changed it, and the scale-out cooldown carve-out below is treated as satisfied
     * unconditionally so a persistent breach isn't stuck stranded below where it belongs. */
    public Integer getLastAppliedCapacity() { return lastAppliedCapacity; }
    public void setLastAppliedCapacity(Integer v) { this.lastAppliedCapacity = v; }

    /** The capacity this policy's last successful <em>scale-out</em> action set; {@code null}
     * if it has never scaled out. Deliberately separate from {@link #getLastAppliedCapacity},
     * which a scale-in also updates — comparing against that shared field instead of this one
     * would let an intervening scale-in corrupt the scale-out cooldown carve-out's high-water
     * mark. Per AWS's documented target-tracking cooldown semantics, a scale-out during cooldown
     * is only allowed through when it would set a capacity <em>larger</em> than this. */
    public Integer getLastScaleOutCapacity() { return lastScaleOutCapacity; }
    public void setLastScaleOutCapacity(Integer v) { this.lastScaleOutCapacity = v; }
}
