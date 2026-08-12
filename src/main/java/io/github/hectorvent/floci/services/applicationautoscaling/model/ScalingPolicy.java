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
}
