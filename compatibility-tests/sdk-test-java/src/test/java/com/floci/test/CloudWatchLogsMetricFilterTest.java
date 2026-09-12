package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.InvalidParameterException;
import software.amazon.awssdk.services.cloudwatchlogs.model.MetricFilter;
import software.amazon.awssdk.services.cloudwatchlogs.model.MetricFilterMatchRecord;
import software.amazon.awssdk.services.cloudwatchlogs.model.MetricTransformation;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;
import software.amazon.awssdk.services.cloudwatchlogs.model.StandardUnit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Metric filters through the CloudWatch Logs SDK: put, describe, test and delete, and the metric a
 * filter publishes for matching log events, read back through the CloudWatch SDK.
 */
@DisplayName("CloudWatch Logs metric filters")
class CloudWatchLogsMetricFilterTest {

    private static CloudWatchLogsClient logs;
    private static CloudWatchClient cloudWatch;
    private static String group;
    private static String namespace;

    @BeforeAll
    static void setup() {
        logs = TestFixtures.cloudWatchLogsClient();
        cloudWatch = TestFixtures.cloudWatchClient();
        group = "/test/" + TestFixtures.uniqueName("metric-filter");
        namespace = TestFixtures.uniqueName("MetricFilterTest");
        logs.createLogGroup(r -> r.logGroupName(group));
        logs.createLogStream(r -> r.logGroupName(group).logStreamName("web"));
    }

    @AfterAll
    static void cleanup() {
        if (logs != null) {
            try {
                logs.deleteLogGroup(r -> r.logGroupName(group));
            } catch (Exception e) {
                System.err.println("metric filter cleanup skipped: " + e.getMessage());
            }
            logs.close();
        }
        if (cloudWatch != null) {
            cloudWatch.close();
        }
    }

    @Test
    void aFilterIsStoredDescribedTestedAndPublishesItsMetric() {
        logs.putMetricFilter(r -> r
                .logGroupName(group)
                .filterName("volume")
                .filterPattern("[..., status_code, size]")
                .metricTransformations(MetricTransformation.builder()
                        .metricName("Volume")
                        .metricNamespace(namespace)
                        .metricValue("$size")
                        .dimensions(Map.of("Status", "$status_code"))
                        .unit(StandardUnit.BYTES)
                        .build()));

        List<MetricFilter> described = logs.describeMetricFilters(r -> r.logGroupName(group)).metricFilters();
        assertThat(described).hasSize(1);
        MetricFilter filter = described.get(0);
        assertThat(filter.filterName()).isEqualTo("volume");
        assertThat(filter.logGroupName()).isEqualTo(group);
        assertThat(filter.filterPattern()).isEqualTo("[..., status_code, size]");
        assertThat(filter.creationTime()).isPositive();
        MetricTransformation t = filter.metricTransformations().get(0);
        assertThat(t.metricName()).isEqualTo("Volume");
        assertThat(t.metricNamespace()).isEqualTo(namespace);
        assertThat(t.metricValue()).isEqualTo("$size");
        assertThat(t.dimensions()).containsExactlyEntriesOf(Map.of("Status", "$status_code"));
        assertThat(t.unit()).isEqualTo(StandardUnit.BYTES);
        assertThat(logs.describeMetricFilters(r -> r.metricName("Volume").metricNamespace(namespace)).metricFilters())
                .extracting(MetricFilter::logGroupName)
                .contains(group);
        assertThat(logs.describeLogGroups(r -> r.logGroupNamePrefix(group)).logGroups().get(0).metricFilterCount())
                .isEqualTo(1);

        List<MetricFilterMatchRecord> matches = logs.testMetricFilter(r -> r
                .filterPattern("[..., status_code=200, size]")
                .logEventMessages(
                        "127.0.0.1 - frank [10/Oct/2000:13:25:15 -0700] \"GET /apache_pb.gif HTTP/1.0\" 200 1534",
                        "127.0.0.1 - frank [10/Oct/2000:13:35:22 -0700] \"GET /apache_pb.gif HTTP/1.0\" 500 5324"))
                .matches();
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).eventNumber()).isZero();
        assertThat(matches.get(0).extractedValues()).containsEntry("$size", "1534").containsEntry("$status_code", "200")
                .containsEntry("$1", "127.0.0.1");

        long now = System.currentTimeMillis();
        logs.putLogEvents(r -> r.logGroupName(group).logStreamName("web").logEvents(
                InputLogEvent.builder().timestamp(now).message(
                        "127.0.0.1 - frank [10/Oct/2000:13:25:15 -0700] \"GET /index.html HTTP/1.0\" 200 1534").build(),
                InputLogEvent.builder().timestamp(now + 1).message(
                        "127.0.0.1 - frank [10/Oct/2000:13:35:22 -0700] \"GET /index.html HTTP/1.0\" 500 5324").build(),
                InputLogEvent.builder().timestamp(now + 2).message(
                        "127.0.0.1 - frank [10/Oct/2000:13:50:35 -0700] \"GET /index.html HTTP/1.0\" 200 4355").build()));

        List<Datapoint> datapoints = cloudWatch.getMetricStatistics(r -> r
                .namespace(namespace)
                .metricName("Volume")
                .dimensions(Dimension.builder().name("Status").value("200").build())
                .startTime(Instant.ofEpochMilli(now).minusSeconds(120))
                .endTime(Instant.ofEpochMilli(now).plusSeconds(120))
                .period(300)
                .statistics(Statistic.SUM, Statistic.SAMPLE_COUNT)).datapoints();
        assertThat(datapoints.stream().mapToDouble(Datapoint::sum).sum()).isEqualTo(1534 + 4355);
        assertThat(datapoints.stream().mapToDouble(Datapoint::sampleCount).sum()).isEqualTo(2);

        logs.deleteMetricFilter(r -> r.logGroupName(group).filterName("volume"));
        assertThat(logs.describeMetricFilters(r -> r.logGroupName(group)).metricFilters()).isEmpty();
        assertThatThrownBy(() -> logs.deleteMetricFilter(r -> r.logGroupName(group).filterName("volume")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theApiRejectsWhatAwsRejects() {
        assertThatThrownBy(() -> logs.putMetricFilter(r -> r
                .logGroupName(group)
                .filterName("broken")
                .filterPattern("{ $.a = }")
                .metricTransformations(MetricTransformation.builder()
                        .metricName("A").metricNamespace(namespace).metricValue("1").build())))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Invalid filter pattern");
        assertThatThrownBy(() -> logs.putMetricFilter(r -> r
                .logGroupName("/test/no-such-group")
                .filterName("x")
                .filterPattern("ERROR")
                .metricTransformations(MetricTransformation.builder()
                        .metricName("A").metricNamespace(namespace).metricValue("1").build())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> logs.putMetricFilter(r -> r
                .logGroupName(group)
                .filterName("dims")
                .filterPattern("ERROR")
                .metricTransformations(MetricTransformation.builder()
                        .metricName("A").metricNamespace(namespace).metricValue("1")
                        .dimensions(Map.of("a", "$.a")).build())))
                .as("dimensions need a JSON or space-delimited pattern")
                .isInstanceOf(InvalidParameterException.class);
    }
}
