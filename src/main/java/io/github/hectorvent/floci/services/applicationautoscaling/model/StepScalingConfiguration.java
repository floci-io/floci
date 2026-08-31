package io.github.hectorvent.floci.services.applicationautoscaling.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.ArrayList;
import java.util.List;

/**
 * Step scaling configuration for a scaling policy.
 *
 * @see <a href="https://docs.aws.amazon.com/autoscaling/application/APIReference/API_StepScalingPolicyConfiguration.html">StepScalingPolicyConfiguration</a>
 */
@RegisterForReflection
public class StepScalingConfiguration {

    private String adjustmentType;
    private Integer cooldown;
    private String metricAggregationType;
    private Integer minAdjustmentMagnitude;
    private List<StepAdjustment> stepAdjustments = new ArrayList<>();

    public String getAdjustmentType() { return adjustmentType; }
    public void setAdjustmentType(String v) { this.adjustmentType = v; }

    public Integer getCooldown() { return cooldown; }
    public void setCooldown(Integer v) { this.cooldown = v; }

    public String getMetricAggregationType() { return metricAggregationType; }
    public void setMetricAggregationType(String v) { this.metricAggregationType = v; }

    public Integer getMinAdjustmentMagnitude() { return minAdjustmentMagnitude; }
    public void setMinAdjustmentMagnitude(Integer v) { this.minAdjustmentMagnitude = v; }

    public List<StepAdjustment> getStepAdjustments() { return stepAdjustments; }
    public void setStepAdjustments(List<StepAdjustment> v) {
        this.stepAdjustments = v == null ? new ArrayList<>() : new ArrayList<>(v);
    }
}
