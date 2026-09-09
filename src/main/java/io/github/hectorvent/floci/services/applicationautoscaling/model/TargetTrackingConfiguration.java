package io.github.hectorvent.floci.services.applicationautoscaling.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Map;

/**
 * Target tracking configuration for a scaling policy.
 *
 * <p>Every field here is echoed back by DescribeScalingPolicies. Optional numerics stay
 * nullable so that omitted values are not invented on read, which would surface as
 * Terraform drift.</p>
 *
 * @see <a href="https://docs.aws.amazon.com/autoscaling/application/APIReference/API_TargetTrackingScalingPolicyConfiguration.html">TargetTrackingScalingPolicyConfiguration</a>
 */
@RegisterForReflection
public class TargetTrackingConfiguration {

    private Double targetValue;
    private PredefinedMetricSpecification predefinedMetricSpecification;
    private Map<String, Object> customizedMetricSpecification;
    private Boolean disableScaleIn;
    private Integer scaleInCooldown;
    private Integer scaleOutCooldown;

    public Double getTargetValue() { return targetValue; }
    public void setTargetValue(Double v) { this.targetValue = v; }

    public PredefinedMetricSpecification getPredefinedMetricSpecification() { return predefinedMetricSpecification; }
    public void setPredefinedMetricSpecification(PredefinedMetricSpecification v) { this.predefinedMetricSpecification = v; }

    public Map<String, Object> getCustomizedMetricSpecification() { return customizedMetricSpecification; }
    public void setCustomizedMetricSpecification(Map<String, Object> v) { this.customizedMetricSpecification = v; }

    public Boolean getDisableScaleIn() { return disableScaleIn; }
    public void setDisableScaleIn(Boolean v) { this.disableScaleIn = v; }

    public Integer getScaleInCooldown() { return scaleInCooldown; }
    public void setScaleInCooldown(Integer v) { this.scaleInCooldown = v; }

    public Integer getScaleOutCooldown() { return scaleOutCooldown; }
    public void setScaleOutCooldown(Integer v) { this.scaleOutCooldown = v; }
}
