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
        // lex00/floci#119's own disclaimed sub-gap: CustomizedMetricSpecification was accepted by
        // PutScalingPolicy and then dropped entirely - not even @JsonIgnoreProperties, there was
        // simply no field - so DescribePolicies could never echo back a customized (as opposed to
        // predefined) target-tracking metric. Oracle: botocore's autoscaling/2011-01-01/
        // service-2.json TargetTrackingConfiguration/CustomizedMetricSpecification shapes. Covers
        // both the classic bare-metric form (MetricName/Namespace/Dimensions/Statistic/Unit) and,
        // as of lex00/floci#122, the metric-math form (Metrics, a list of
        // TargetTrackingMetricDataQuery) that terraform-aws-modules/terraform-aws-autoscaling's
        // own flagship "complete" example actually sends - the shape #121's round left unmodeled,
        // which is why that round's own test passed while the real crossing still lost the field.
        private CustomizedMetricSpecification customizedMetricSpecification;
        private Double targetValue;
        private Boolean disableScaleIn;

        public TargetTrackingConfiguration() {}

        public PredefinedMetricSpecification getPredefinedMetricSpecification() { return predefinedMetricSpecification; }
        public void setPredefinedMetricSpecification(PredefinedMetricSpecification v) { this.predefinedMetricSpecification = v; }

        public CustomizedMetricSpecification getCustomizedMetricSpecification() { return customizedMetricSpecification; }
        public void setCustomizedMetricSpecification(CustomizedMetricSpecification v) { this.customizedMetricSpecification = v; }

        public Double getTargetValue() { return targetValue; }
        public void setTargetValue(Double v) { this.targetValue = v; }

        public Boolean getDisableScaleIn() { return disableScaleIn; }
        public void setDisableScaleIn(Boolean v) { this.disableScaleIn = v; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MetricDimension {
        private String name;
        private String value;

        public MetricDimension() {}

        public MetricDimension(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public void setName(String v) { this.name = v; }

        public String getValue() { return value; }
        public void setValue(String v) { this.value = v; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CustomizedMetricSpecification {
        private String metricName;
        private String namespace;
        private List<MetricDimension> dimensions = new ArrayList<>();
        private String statistic;
        private String unit;
        private Integer period;
        // lex00/floci#122: the "classic" bare-metric fields above
        // (MetricName/Namespace/Dimensions/Statistic/Unit/Period) are ONE of
        // the two shapes CustomizedMetricSpecification supports; the other -
        // Metrics, a list of TargetTrackingMetricDataQuery entries doing
        // CloudWatch metric math - is what terraform-aws-modules/
        // terraform-aws-autoscaling's own flagship "complete" example uses
        // (its "metric_math" scaling policy), and PR #121's round only
        // modeled the classic shape, leaving this one silently dropped the
        // same way the whole field was dropped before #119. Oracle:
        // botocore's autoscaling/2011-01-01/service-2.json
        // CustomizedMetricSpecification/Metrics and
        // TargetTrackingMetricDataQuery shapes.
        private List<TargetTrackingMetricDataQuery> metrics = new ArrayList<>();

        public CustomizedMetricSpecification() {}

        public String getMetricName() { return metricName; }
        public void setMetricName(String v) { this.metricName = v; }

        public String getNamespace() { return namespace; }
        public void setNamespace(String v) { this.namespace = v; }

        public List<MetricDimension> getDimensions() { return dimensions; }
        public void setDimensions(List<MetricDimension> v) { this.dimensions = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

        public String getStatistic() { return statistic; }
        public void setStatistic(String v) { this.statistic = v; }

        public String getUnit() { return unit; }
        public void setUnit(String v) { this.unit = v; }

        public Integer getPeriod() { return period; }
        public void setPeriod(Integer v) { this.period = v; }

        public List<TargetTrackingMetricDataQuery> getMetrics() { return metrics; }
        public void setMetrics(List<TargetTrackingMetricDataQuery> v) {
            this.metrics = v != null ? new ArrayList<>(v) : new ArrayList<>();
        }
    }

    // TargetTrackingMetricDataQuery/TargetTrackingMetricStat/Metric: the
    // metric-math form of CustomizedMetricSpecification - see its own doc
    // comment on the `metrics` field above for why this exists. Field names
    // and nesting are taken directly from botocore's own shapes rather than
    // guessed: TargetTrackingMetricDataQuery{Id,Expression,MetricStat,Label,
    // Period,ReturnData}, TargetTrackingMetricStat{Metric,Stat,Unit,Period},
    // Metric{Namespace,MetricName,Dimensions}.
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TargetTrackingMetricDataQuery {
        private String id;
        private String expression;
        private TargetTrackingMetricStat metricStat;
        private String label;
        private Integer period;
        private Boolean returnData;

        public TargetTrackingMetricDataQuery() {}

        public String getId() { return id; }
        public void setId(String v) { this.id = v; }

        public String getExpression() { return expression; }
        public void setExpression(String v) { this.expression = v; }

        public TargetTrackingMetricStat getMetricStat() { return metricStat; }
        public void setMetricStat(TargetTrackingMetricStat v) { this.metricStat = v; }

        public String getLabel() { return label; }
        public void setLabel(String v) { this.label = v; }

        public Integer getPeriod() { return period; }
        public void setPeriod(Integer v) { this.period = v; }

        public Boolean getReturnData() { return returnData; }
        public void setReturnData(Boolean v) { this.returnData = v; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TargetTrackingMetricStat {
        private Metric metric;
        private String stat;
        private String unit;
        private Integer period;

        public TargetTrackingMetricStat() {}

        public Metric getMetric() { return metric; }
        public void setMetric(Metric v) { this.metric = v; }

        public String getStat() { return stat; }
        public void setStat(String v) { this.stat = v; }

        public String getUnit() { return unit; }
        public void setUnit(String v) { this.unit = v; }

        public Integer getPeriod() { return period; }
        public void setPeriod(Integer v) { this.period = v; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metric {
        private String namespace;
        private String metricName;
        private List<MetricDimension> dimensions = new ArrayList<>();

        public Metric() {}

        public String getNamespace() { return namespace; }
        public void setNamespace(String v) { this.namespace = v; }

        public String getMetricName() { return metricName; }
        public void setMetricName(String v) { this.metricName = v; }

        public List<MetricDimension> getDimensions() { return dimensions; }
        public void setDimensions(List<MetricDimension> v) { this.dimensions = v != null ? new ArrayList<>(v) : new ArrayList<>(); }
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
