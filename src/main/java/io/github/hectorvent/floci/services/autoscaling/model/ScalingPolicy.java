package io.github.hectorvent.floci.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScalingPolicy {

    private String policyName;
    private String policyArn;
    private String autoScalingGroupName;
    private String policyType;          // SimpleScaling | StepScaling | TargetTrackingScaling | PredictiveScaling
    private String adjustmentType;      // ChangeInCapacity | ExactCapacity | PercentChangeInCapacity
    private int scalingAdjustment;

    // Cooldown is nullable, not defaulted to 300 here: the AWS API's own
    // documented default of 300 applies ONLY when PolicyType is
    // SimpleScaling (or absent, which floci and the real API both treat as
    // SimpleScaling) - "otherwise ignored" per PutScalingPolicy's own doc.
    // A StepScaling/TargetTrackingScaling/PredictiveScaling policy that
    // never set cooldown returns none at all from a real DescribePolicies;
    // the type-conditional default is applied once, in
    // AutoScalingService.putScalingPolicy, so every caller sees the same
    // rule rather than re-deriving it.
    private Integer cooldown;

    // Enabled has no field at all before this: PutScalingPolicy's real
    // Enabled parameter (schema default true) was accepted and silently
    // dropped, so DescribePolicies never echoed it and every policy read
    // back as disabled - surfaced by
    // live/e2e/corpus-autoscaling-complete, whose "avg-cpu-policy-greater-
    // than-50"/"predictive-scaling"/"scale-out" policies never set
    // `enabled` in configuration and so rely on the schema default of true.
    private Boolean enabled;

    private String metricAggregationType;
    private Integer estimatedInstanceWarmup;
    private TargetTrackingConfiguration targetTrackingConfiguration;

    // StepAdjustments and PredictiveScalingConfiguration: same shape as
    // Enabled above - accepted on write, never modeled, so a StepScaling or
    // PredictiveScaling policy's whole defining configuration vanished on
    // every stateless replan.
    private List<StepAdjustment> stepAdjustments = new ArrayList<>();
    private PredictiveScalingConfiguration predictiveScalingConfiguration;

    private String region;

    public ScalingPolicy() {}

    public String getPolicyName() { return policyName; }
    public void setPolicyName(String v) { this.policyName = v; }

    public String getPolicyArn() { return policyArn; }
    public void setPolicyArn(String v) { this.policyArn = v; }

    public String getAutoScalingGroupName() { return autoScalingGroupName; }
    public void setAutoScalingGroupName(String v) { this.autoScalingGroupName = v; }

    public String getPolicyType() { return policyType; }
    public void setPolicyType(String v) { this.policyType = v; }

    public String getAdjustmentType() { return adjustmentType; }
    public void setAdjustmentType(String v) { this.adjustmentType = v; }

    public int getScalingAdjustment() { return scalingAdjustment; }
    public void setScalingAdjustment(int v) { this.scalingAdjustment = v; }

    public Integer getCooldown() { return cooldown; }
    public void setCooldown(Integer v) { this.cooldown = v; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean v) { this.enabled = v; }

    public String getMetricAggregationType() { return metricAggregationType; }
    public void setMetricAggregationType(String v) { this.metricAggregationType = v; }

    public Integer getEstimatedInstanceWarmup() { return estimatedInstanceWarmup; }
    public void setEstimatedInstanceWarmup(Integer v) { this.estimatedInstanceWarmup = v; }

    public TargetTrackingConfiguration getTargetTrackingConfiguration() { return targetTrackingConfiguration; }
    public void setTargetTrackingConfiguration(TargetTrackingConfiguration v) { this.targetTrackingConfiguration = v; }

    public List<StepAdjustment> getStepAdjustments() { return stepAdjustments; }
    public void setStepAdjustments(List<StepAdjustment> v) {
        this.stepAdjustments = v != null ? new ArrayList<>(v) : new ArrayList<>();
    }

    public PredictiveScalingConfiguration getPredictiveScalingConfiguration() { return predictiveScalingConfiguration; }
    public void setPredictiveScalingConfiguration(PredictiveScalingConfiguration v) { this.predictiveScalingConfiguration = v; }

    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TargetTrackingConfiguration {
        private PredefinedMetricSpecification predefinedMetricSpecification;
        private Double targetValue;

        public TargetTrackingConfiguration() {}

        public PredefinedMetricSpecification getPredefinedMetricSpecification() { return predefinedMetricSpecification; }
        public void setPredefinedMetricSpecification(PredefinedMetricSpecification v) { this.predefinedMetricSpecification = v; }

        public Double getTargetValue() { return targetValue; }
        public void setTargetValue(Double v) { this.targetValue = v; }
    }

    // PredefinedMetricSpecification is reused, unchanged in shape
    // (PredefinedMetricType + ResourceLabel), by TargetTrackingConfiguration
    // and by PredictiveScalingMetricSpecification's own three predefined
    // variants below - the same two-field pair the real API repeats for
    // every "predefined" metric kind.
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PredefinedMetricSpecification {
        private String predefinedMetricType;
        private String resourceLabel;

        public PredefinedMetricSpecification() {}

        public String getPredefinedMetricType() { return predefinedMetricType; }
        public void setPredefinedMetricType(String v) { this.predefinedMetricType = v; }

        public String getResourceLabel() { return resourceLabel; }
        public void setResourceLabel(String v) { this.resourceLabel = v; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StepAdjustment {
        private Double metricIntervalLowerBound;
        private Double metricIntervalUpperBound;
        private int scalingAdjustment;

        public StepAdjustment() {}

        public Double getMetricIntervalLowerBound() { return metricIntervalLowerBound; }
        public void setMetricIntervalLowerBound(Double v) { this.metricIntervalLowerBound = v; }

        public Double getMetricIntervalUpperBound() { return metricIntervalUpperBound; }
        public void setMetricIntervalUpperBound(Double v) { this.metricIntervalUpperBound = v; }

        public int getScalingAdjustment() { return scalingAdjustment; }
        public void setScalingAdjustment(int v) { this.scalingAdjustment = v; }
    }

    // PredictiveScalingConfiguration covers the "predefined" metric family
    // used by aws_autoscaling_policy's own documentation examples
    // (PredefinedScalingMetricSpecification / PredefinedLoadMetricSpecification
    // / PredefinedMetricPairSpecification - identical shape, reusing
    // PredefinedMetricSpecification above). It deliberately does NOT model
    // CustomizedScalingMetricSpecification / CustomizedLoadMetricSpecification
    // / CustomizedCapacityMetricSpecification, which carry a nested
    // CloudWatch MetricDataQuery list rather than a bare predefined type -
    // a real, separate gap, not silently assumed covered.
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PredictiveScalingConfiguration {
        private List<PredictiveScalingMetricSpecification> metricSpecifications = new ArrayList<>();
        private String mode;
        private Integer schedulingBufferTime;
        private String maxCapacityBreachBehavior;
        private Integer maxCapacityBuffer;

        public PredictiveScalingConfiguration() {}

        public List<PredictiveScalingMetricSpecification> getMetricSpecifications() { return metricSpecifications; }
        public void setMetricSpecifications(List<PredictiveScalingMetricSpecification> v) {
            this.metricSpecifications = v != null ? new ArrayList<>(v) : new ArrayList<>();
        }

        public String getMode() { return mode; }
        public void setMode(String v) { this.mode = v; }

        public Integer getSchedulingBufferTime() { return schedulingBufferTime; }
        public void setSchedulingBufferTime(Integer v) { this.schedulingBufferTime = v; }

        public String getMaxCapacityBreachBehavior() { return maxCapacityBreachBehavior; }
        public void setMaxCapacityBreachBehavior(String v) { this.maxCapacityBreachBehavior = v; }

        public Integer getMaxCapacityBuffer() { return maxCapacityBuffer; }
        public void setMaxCapacityBuffer(Integer v) { this.maxCapacityBuffer = v; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PredictiveScalingMetricSpecification {
        private Double targetValue;
        private PredefinedMetricSpecification predefinedScalingMetricSpecification;
        private PredefinedMetricSpecification predefinedLoadMetricSpecification;
        private PredefinedMetricSpecification predefinedMetricPairSpecification;

        public PredictiveScalingMetricSpecification() {}

        public Double getTargetValue() { return targetValue; }
        public void setTargetValue(Double v) { this.targetValue = v; }

        public PredefinedMetricSpecification getPredefinedScalingMetricSpecification() { return predefinedScalingMetricSpecification; }
        public void setPredefinedScalingMetricSpecification(PredefinedMetricSpecification v) { this.predefinedScalingMetricSpecification = v; }

        public PredefinedMetricSpecification getPredefinedLoadMetricSpecification() { return predefinedLoadMetricSpecification; }
        public void setPredefinedLoadMetricSpecification(PredefinedMetricSpecification v) { this.predefinedLoadMetricSpecification = v; }

        public PredefinedMetricSpecification getPredefinedMetricPairSpecification() { return predefinedMetricPairSpecification; }
        public void setPredefinedMetricPairSpecification(PredefinedMetricSpecification v) { this.predefinedMetricPairSpecification = v; }
    }
}
