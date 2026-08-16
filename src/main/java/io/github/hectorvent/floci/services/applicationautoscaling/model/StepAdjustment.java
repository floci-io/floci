package io.github.hectorvent.floci.services.applicationautoscaling.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One step of a step scaling policy.
 *
 * <p>The bounds are nullable: an omitted lower bound means negative infinity and an
 * omitted upper bound means positive infinity, so they must not be defaulted to zero.</p>
 *
 * @see <a href="https://docs.aws.amazon.com/autoscaling/application/APIReference/API_StepAdjustment.html">StepAdjustment</a>
 */
@RegisterForReflection
public class StepAdjustment {

    private Double metricIntervalLowerBound;
    private Double metricIntervalUpperBound;
    private Integer scalingAdjustment;

    public Double getMetricIntervalLowerBound() { return metricIntervalLowerBound; }
    public void setMetricIntervalLowerBound(Double v) { this.metricIntervalLowerBound = v; }

    public Double getMetricIntervalUpperBound() { return metricIntervalUpperBound; }
    public void setMetricIntervalUpperBound(Double v) { this.metricIntervalUpperBound = v; }

    public Integer getScalingAdjustment() { return scalingAdjustment; }
    public void setScalingAdjustment(Integer v) { this.scalingAdjustment = v; }
}
