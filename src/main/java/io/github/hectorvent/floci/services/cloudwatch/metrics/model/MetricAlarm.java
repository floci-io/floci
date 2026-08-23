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

    // Nullable, and null by default: real AWS's PutMetricAlarm.DatapointsToAlarm is only
    // stored/returned when a caller explicitly sets it - confirmed directly against real AWS
    // (no Terraform, no floci in the loop): a fresh alarm created with no DatapointsToAlarm and
    // EvaluationPeriods=1 comes back from DescribeAlarms with no DatapointsToAlarm field at all,
    // not EvaluationPeriods echoed back as a derived default. Surfaced by
    // live/e2e/corpus-autoscaling-complete's module.auto_rollback/module.step_scaling_alarm
    // alarms, which never set this argument: every stateless replan proposed
    // `- datapoints_to_alarm = 1 -> null` forever, because this field used to default itself to
    // evaluationPeriods on every create.
    private Integer datapointsToAlarm;
    private double threshold;
    private String comparisonOperator;

    // Nullable, and null by default for the same reason: real AWS omits TreatMissingData from
    // DescribeAlarms when a caller never set it (confirmed the same way), and the AWS provider's
    // own Read function supplies its OWN client-side default of "missing" in that case
    // (internal/service/cloudwatch/metric_alarm.go) - a value this store must never invent on
    // its own, because the alarm that DOES set it (module.auto_rollback sets
    // treat_missing_data = "notBreaching") needs that exact value read back, and floci's
    // DescribeAlarms JSON handler never wrote the field into the response at all, explicitly set
    // or not.
    private String treatMissingData;
    private String evaluateLowSampleCountPercentile;
    private Map<String, String> tags = new HashMap<>();

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

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
