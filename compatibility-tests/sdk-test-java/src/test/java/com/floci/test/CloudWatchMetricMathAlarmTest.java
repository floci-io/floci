package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.ComparisonOperator;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CloudWatch - Metric-math alarm definitions")
class CloudWatchMetricMathAlarmTest {

    private CloudWatchClient cloudWatch;
    private String alarmName;

    @BeforeEach
    void setup() {
        cloudWatch = TestFixtures.cloudWatchClient();
        alarmName = TestFixtures.uniqueName("sdk-metric-math-alarm");
    }

    @AfterEach
    void cleanup() {
        try {
            cloudWatch.deleteAlarms(request -> request.alarmNames(alarmName));
        } finally {
            cloudWatch.close();
        }
    }

    @Test
    void metricsRoundTripWithoutInventingSingleMetricFields() {
        List<MetricDataQuery> metrics = metrics();
        putMathAlarm(metrics);
        assertMathAlarm(metrics);
    }

    @Test
    void updatesReplaceQueriesAndSwitchBackToASingleMetric() {
        putSingleMetricAlarm(300);
        List<MetricDataQuery> original = metrics();
        putMathAlarm(original);
        assertMathAlarm(original);

        List<MetricDataQuery> replacement = List.of(
                original.get(0).toBuilder().expression("errors / requests").label("Ratio").build(),
                original.get(1), original.get(2));
        putMathAlarm(replacement);
        assertMathAlarm(replacement);

        putSingleMetricAlarm(120);
        MetricAlarm alarm = describe();
        assertThat(alarm.hasMetrics()).isFalse();
        assertThat(alarm.period()).isEqualTo(120);
        assertThat(alarm.metricName()).isEqualTo("5xx");
        assertThat(alarm.namespace()).isEqualTo("AWS/ApiGateway");
    }

    private void putMathAlarm(List<MetricDataQuery> metrics) {
        cloudWatch.putMetricAlarm(request -> request.alarmName(alarmName)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .evaluationPeriods(2).threshold(5.0).metrics(metrics));
    }

    private void putSingleMetricAlarm(int period) {
        cloudWatch.putMetricAlarm(request -> request.alarmName(alarmName)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .evaluationPeriods(2).threshold(5.0).namespace("AWS/ApiGateway")
                .metricName("5xx").statistic(Statistic.SUM).period(period));
    }

    private MetricAlarm describe() {
        List<MetricAlarm> alarms = cloudWatch.describeAlarms(request -> request.alarmNames(alarmName)).metricAlarms();
        assertThat(alarms).hasSize(1);
        return alarms.get(0);
    }

    private void assertMathAlarm(List<MetricDataQuery> metrics) {
        MetricAlarm alarm = describe();
        assertThat(alarm.metrics()).containsExactlyElementsOf(metrics);
        assertThat(alarm.period()).isNull();
        assertThat(alarm.metricName()).isNull();
        assertThat(alarm.namespace()).isNull();
        assertThat(alarm.statistic()).isNull();
        assertThat(alarm.extendedStatistic()).isNull();
        assertThat(alarm.unit()).isNull();
        assertThat(alarm.hasDimensions()).isFalse();
        assertThat(alarm.evaluationPeriods()).isEqualTo(2);
        assertThat(alarm.threshold()).isEqualTo(5.0);
    }

    private static List<MetricDataQuery> metrics() {
        return List.of(
                MetricDataQuery.builder().id("rate").expression("100 * errors / requests")
                        .returnData(true).period(300).label("Error <rate> & requests").build(),
                MetricDataQuery.builder().id("errors").returnData(false).accountId("123456789012")
                        .metricStat(MetricStat.builder().period(300).stat("Sum").unit(StandardUnit.COUNT)
                                .metric(Metric.builder().namespace("AWS/ApiGateway").metricName("5xx")
                                        .dimensions(Dimension.builder().name("ApiName").value("api<&>").build())
                                        .build()).build()).build(),
                MetricDataQuery.builder().id("requests").returnData(false)
                        .metricStat(MetricStat.builder().period(300).stat("Sum")
                                .metric(Metric.builder().namespace("AWS/ApiGateway").metricName("Count").build())
                                .build()).build());
    }
}
