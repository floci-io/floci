package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricAlarm extends Alarm {
    @Override
    public String getAlarmType() { return "MetricAlarm"; }

    private String metricName;
    private String namespace;
    private String statistic;
    private List<Dimension> dimensions = new ArrayList<>();
    /** Single-metric period; absent for alarms whose periods are defined in Metrics. */
    private Integer period;
    private List<AlarmMetricDataQuery> metrics = new ArrayList<>();
    private String unit;
    private int evaluationPeriods;
    /**
     * The M of an "M out of N" alarm, exactly as the caller set it, or null when the caller set
     * nothing. It stays nullable so a response can tell the two apart: AWS omits the member from
     * DescribeAlarms for an alarm that never carried one, and echoing a number nobody asked for
     * reads as drift to any client that re-plans. {@link AlarmEvaluator} supplies EvaluationPeriods
     * for the evaluation maths instead of storing it here.
     */
    private Integer datapointsToAlarm;
    private double threshold;
    private String comparisonOperator;
    private String treatMissingData = "missing";
    private String evaluateLowSampleCountPercentile;

    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getStatistic() { return statistic; }
    public void setStatistic(String statistic) { this.statistic = statistic; }

    public List<Dimension> getDimensions() { return dimensions; }
    public void setDimensions(List<Dimension> dimensions) { this.dimensions = dimensions; }

    public Integer getPeriod() { return period; }
    public void setPeriod(Integer period) { this.period = period; }

    public List<AlarmMetricDataQuery> getMetrics() { return metrics; }
    public void setMetrics(List<AlarmMetricDataQuery> metrics) {
        this.metrics = metrics == null ? new ArrayList<>() : new ArrayList<>(metrics);
    }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public int getEvaluationPeriods() { return evaluationPeriods; }
    public void setEvaluationPeriods(int evaluationPeriods) { this.evaluationPeriods = evaluationPeriods; }

    public Integer getDatapointsToAlarm() { return datapointsToAlarm; }
    public void setDatapointsToAlarm(Integer datapointsToAlarm) { this.datapointsToAlarm = datapointsToAlarm; }

    public double getThreshold() { return threshold; }
    public void setThreshold(double threshold) { this.threshold = threshold; }

    public String getComparisonOperator() { return comparisonOperator; }
    public void setComparisonOperator(String comparisonOperator) { this.comparisonOperator = comparisonOperator; }

    public String getTreatMissingData() { return treatMissingData; }
    public void setTreatMissingData(String treatMissingData) { this.treatMissingData = treatMissingData; }

    public String getEvaluateLowSampleCountPercentile() { return evaluateLowSampleCountPercentile; }
    public void setEvaluateLowSampleCountPercentile(String percentile) { this.evaluateLowSampleCountPercentile = percentile; }

}
