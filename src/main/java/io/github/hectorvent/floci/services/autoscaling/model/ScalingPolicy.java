package io.github.hectorvent.floci.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScalingPolicy {

    private String policyName;
    private String policyArn;
    private String autoScalingGroupName;
    private String policyType;          // SimpleScaling | StepScaling | TargetTrackingScaling
    private String adjustmentType;      // ChangeInCapacity | ExactCapacity | PercentChangeInCapacity
    private int scalingAdjustment;
    private int cooldown;
    private String metricAggregationType;
    private Integer estimatedInstanceWarmup;
    private TargetTrackingConfiguration targetTrackingConfiguration;
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

    public int getCooldown() { return cooldown; }
    public void setCooldown(int v) { this.cooldown = v; }

    public String getMetricAggregationType() { return metricAggregationType; }
    public void setMetricAggregationType(String v) { this.metricAggregationType = v; }

    public Integer getEstimatedInstanceWarmup() { return estimatedInstanceWarmup; }
    public void setEstimatedInstanceWarmup(Integer v) { this.estimatedInstanceWarmup = v; }

    public TargetTrackingConfiguration getTargetTrackingConfiguration() { return targetTrackingConfiguration; }
    public void setTargetTrackingConfiguration(TargetTrackingConfiguration v) { this.targetTrackingConfiguration = v; }

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

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PredefinedMetricSpecification {
        private String predefinedMetricType;

        public PredefinedMetricSpecification() {}

        public String getPredefinedMetricType() { return predefinedMetricType; }
        public void setPredefinedMetricType(String v) { this.predefinedMetricType = v; }
    }
}
