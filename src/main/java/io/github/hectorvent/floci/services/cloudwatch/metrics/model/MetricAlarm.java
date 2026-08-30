package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricAlarm {
    private String alarmName;
    private String alarmArn;
    private String alarmDescription;
    private long alarmConfigurationUpdatedTimestamp;
    private boolean actionsEnabled;
    private List<String> okActions = new ArrayList<>();
    private List<String> alarmActions = new ArrayList<>();
    private List<String> insufficientDataActions = new ArrayList<>();
    private String stateValue = "INSUFFICIENT_DATA";
    private String stateReason = "Unchecked";
    private String stateReasonData;
    private long stateUpdatedTimestamp;
    private String metricName;
    private String namespace;
    private String statistic;
    private List<Dimension> dimensions = new ArrayList<>();
    private int period;
    private String unit;
    private int evaluationPeriods;
    private int datapointsToAlarm;
    private double threshold;
    private String comparisonOperator;
    private String treatMissingData = "missing";
    private String evaluateLowSampleCountPercentile;
    private Map<String, String> tags = new HashMap<>();
    private String region;

    public MetricAlarm() {
        long now = Instant.now().getEpochSecond();
        this.alarmConfigurationUpdatedTimestamp = now;
        this.stateUpdatedTimestamp = now;
    }

    public String getAlarmName() { return alarmName; }
    public void setAlarmName(String alarmName) { this.alarmName = alarmName; }

    public String getAlarmArn() { return alarmArn; }
    public void setAlarmArn(String alarmArn) { this.alarmArn = alarmArn; }

    public String getAlarmDescription() { return alarmDescription; }
    public void setAlarmDescription(String alarmDescription) { this.alarmDescription = alarmDescription; }

    public long getAlarmConfigurationUpdatedTimestamp() { return alarmConfigurationUpdatedTimestamp; }
    public void setAlarmConfigurationUpdatedTimestamp(long timestamp) { this.alarmConfigurationUpdatedTimestamp = timestamp; }

    public boolean isActionsEnabled() { return actionsEnabled; }
    public void setActionsEnabled(boolean actionsEnabled) { this.actionsEnabled = actionsEnabled; }

    public List<String> getOkActions() { return okActions; }
    public void setOkActions(List<String> okActions) { this.okActions = okActions; }

    public List<String> getAlarmActions() { return alarmActions; }
    public void setAlarmActions(List<String> alarmActions) { this.alarmActions = alarmActions; }

    public List<String> getInsufficientDataActions() { return insufficientDataActions; }
    public void setInsufficientDataActions(List<String> insufficientDataActions) { this.insufficientDataActions = insufficientDataActions; }

    public String getStateValue() { return stateValue; }
    public void setStateValue(String stateValue) { this.stateValue = stateValue; }

    public String getStateReason() { return stateReason; }
    public void setStateReason(String stateReason) { this.stateReason = stateReason; }

    public String getStateReasonData() { return stateReasonData; }
    public void setStateReasonData(String stateReasonData) { this.stateReasonData = stateReasonData; }

    public long getStateUpdatedTimestamp() { return stateUpdatedTimestamp; }
    public void setStateUpdatedTimestamp(long stateUpdatedTimestamp) { this.stateUpdatedTimestamp = stateUpdatedTimestamp; }

    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getStatistic() { return statistic; }
    public void setStatistic(String statistic) { this.statistic = statistic; }

    public List<Dimension> getDimensions() { return dimensions; }
    public void setDimensions(List<Dimension> dimensions) { this.dimensions = dimensions; }

    public int getPeriod() { return period; }
    public void setPeriod(int period) { this.period = period; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public int getEvaluationPeriods() { return evaluationPeriods; }
    public void setEvaluationPeriods(int evaluationPeriods) { this.evaluationPeriods = evaluationPeriods; }

    public int getDatapointsToAlarm() { return datapointsToAlarm; }
    public void setDatapointsToAlarm(int datapointsToAlarm) { this.datapointsToAlarm = datapointsToAlarm; }

    public double getThreshold() { return threshold; }
    public void setThreshold(double threshold) { this.threshold = threshold; }

    public String getComparisonOperator() { return comparisonOperator; }
    public void setComparisonOperator(String comparisonOperator) { this.comparisonOperator = comparisonOperator; }

    public String getTreatMissingData() { return treatMissingData; }
    public void setTreatMissingData(String treatMissingData) { this.treatMissingData = treatMissingData; }

    public String getEvaluateLowSampleCountPercentile() { return evaluateLowSampleCountPercentile; }
    public void setEvaluateLowSampleCountPercentile(String percentile) { this.evaluateLowSampleCountPercentile = percentile; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    /** Not part of the AWS shape; set by {@code putMetricAlarm} so a background evaluator
     * that only holds the alarm object still knows which region's metric data to query. */
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
}
