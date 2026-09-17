package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.InvalidParameterException;
import software.amazon.awssdk.services.cloudwatchlogs.model.MetricFilter;
import software.amazon.awssdk.services.cloudwatchlogs.model.MetricFilterMatchRecord;
import software.amazon.awssdk.services.cloudwatchlogs.model.MetricTransformation;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;
import software.amazon.awssdk.services.cloudwatchlogs.model.StandardUnit;
import software.amazon.awssdk.services.sts.StsClient;

import java.time.Instant;
import java.util.ArrayList;
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
    void aFilterIsStoredDescribedTestedAndPublishesItsMetric() throws InterruptedException {
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
        assertThat(matches.get(0).eventNumber()).isEqualTo(1);
        assertThat(matches.get(0).extractedValues()).containsEntry("$size", "1534").containsEntry("$status_code", "200")
                .containsEntry("$1", "127.0.0.1");

        List<MetricFilterMatchRecord> jsonMatches = logs.testMetricFilter(r -> r
                .filterPattern("{ $.latency = * }")
                .logEventMessages("plain text", "{\"latency\":50}")).matches();
        assertThat(jsonMatches).hasSize(1);
        assertThat(jsonMatches.get(0).eventNumber()).isEqualTo(2);
        assertThat(jsonMatches.get(0).extractedValues()).isEmpty();

        long now = System.currentTimeMillis();
        logs.putLogEvents(r -> r.logGroupName(group).logStreamName("web").logEvents(
                InputLogEvent.builder().timestamp(now).message(
                        "127.0.0.1 - frank [10/Oct/2000:13:25:15 -0700] \"GET /index.html HTTP/1.0\" 200 1534").build(),
                InputLogEvent.builder().timestamp(now + 1).message(
                        "127.0.0.1 - frank [10/Oct/2000:13:35:22 -0700] \"GET /index.html HTTP/1.0\" 500 5324").build(),
                InputLogEvent.builder().timestamp(now + 2).message(
                        "127.0.0.1 - frank [10/Oct/2000:13:50:35 -0700] \"GET /index.html HTTP/1.0\" 200 4355").build()));

        long deadline = System.nanoTime() + 10_000_000_000L;
        List<Datapoint> datapoints;
        do {
            datapoints = cloudWatch.getMetricStatistics(r -> r
                    .namespace(namespace)
                    .metricName("Volume")
                    .dimensions(Dimension.builder().name("Status").value("200").build())
                    .startTime(Instant.ofEpochMilli(now).minusSeconds(120))
                    .endTime(Instant.ofEpochMilli(now).plusSeconds(120))
                    .period(300)
                    .statistics(Statistic.SUM, Statistic.SAMPLE_COUNT)).datapoints();
            if (datapoints.stream().mapToDouble(Datapoint::sampleCount).sum() >= 2) {
                break;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
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
        assertThatThrownBy(() -> logs.describeMetricFilters(r -> r.metricNamespace(namespace)))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("must contain both MetricName and MetricNamespace");
        assertThatThrownBy(() -> logs.describeMetricFilters(r -> r.metricName("A")))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("must contain both MetricName and MetricNamespace");
    }

    @Test
    void recordedDefaultsAndQuietTrafficReadIdenticallyOverJsonAndQuery() throws Exception {
        JsonNode fixture = publishingFixture().get("metricDefaults");
        String isolated = createIsolatedGroup();
        Instant minute = Instant.ofEpochSecond((Instant.now().getEpochSecond() / 60 - 30) * 60);
        try {
            int index = 0;
            for (JsonNode scenario : fixture.get("cases")) {
                String metric = "defaults" + index++;
                put(isolated, metric, fixture, null, List.of());
                for (JsonNode batch : scenario.get("batches")) {
                    List<String> messages = new ArrayList<>();
                    batch.forEach(m -> messages.add(m.asText()));
                    ingest(isolated, minute, messages);
                }
                assertSeries(metric, minute, List.of(), scenario.get("expected"));
                minute = minute.plusSeconds(60);
            }
            put(isolated, "quiet", fixture, null, List.of());
            ingest(isolated, minute, List.of("INFO", "INFO", "INFO"));
            JsonNode quiet = new ObjectMapper().readTree("{\"Sum\":21,\"SampleCount\":3}");
            assertSeries("quiet", minute, List.of(), quiet);
            assertSeries("quiet", minute, List.of(), quiet);
            assertSeries("quiet", minute.plusSeconds(60), List.of(), null);
        } finally {
            logs.deleteLogGroup(r -> r.logGroupName(isolated));
        }
    }

    @Test
    void recordedExtractionAndOrdinaryDimensionSeriesReadIdenticallyOverJsonAndQuery() throws Exception {
        JsonNode fixture = publishingFixture();
        String isolated = createIsolatedGroup();
        Instant minute = Instant.ofEpochSecond((Instant.now().getEpochSecond() / 60 - 20) * 60);
        try {
            JsonNode extraction = fixture.get("extraction");
            put(isolated, "extraction", extraction, null, List.of());
            for (JsonNode scenario : extraction.get("cases")) {
                ingest(isolated, minute, List.of(scenario.get("message").toString()));
                assertSeries("extraction", minute, List.of(), scenario.get("expected"));
                minute = minute.plusSeconds(60);
            }
            JsonNode dimensions = fixture.get("ordinaryDimensions");
            put(isolated, "dimensions", dimensions, Map.of("A", "$.a", "B", "$.b"), List.of());
            List<String> messages = new ArrayList<>();
            dimensions.get("messages").forEach(m -> messages.add(m.toString()));
            ingest(isolated, minute, messages);
            for (JsonNode series : dimensions.get("expectedSeries")) {
                assertSeries("dimensions", minute, dimensions(series.get("dimensions"), ""), series);
            }
            for (JsonNode series : dimensions.get("absentSeries")) {
                assertSeries("dimensions", minute, dimensions(series.get("dimensions"), ""), null);
            }
            assertThat(cloudWatch.listMetrics(r -> r.namespace(namespace).metricName("dimensions")).metrics())
                    .extracting(m -> m.dimensions().stream().map(d -> d.name() + "=" + d.value()).sorted().toList())
                    .containsExactlyInAnyOrder(List.of(), List.of("A=alpha", "B=beta"));
        } finally {
            logs.deleteLogGroup(r -> r.logGroupName(isolated));
        }
    }

    @Test
    void recordedSystemDimensionsDoNotAttachToPatternNonmatchDefaults() throws Exception {
        JsonNode fixture = publishingFixture().get("systemDimensions");
        String isolated = createIsolatedGroup();
        String account;
        try (StsClient sts = TestFixtures.stsClient()) {
            account = sts.getCallerIdentity().account();
        }
        Instant minute = Instant.ofEpochSecond((Instant.now().getEpochSecond() / 60 - 10) * 60);
        try {
            int index = 0;
            for (JsonNode scenario : fixture.get("cases")) {
                List<String> fields = new ArrayList<>();
                scenario.get("emitSystemFieldDimensions").forEach(f -> fields.add(f.asText()));
                String metric = "system" + index++;
                put(isolated, metric, fixture, null, fields);
                ingest(isolated, minute, List.of("ERROR", "INFO"));
                for (JsonNode series : scenario.get("expectedSeries")) {
                    assertSeries(metric, minute, dimensions(series.get("dimensions"), account), series);
                }
                assertThat(cloudWatch.listMetrics(r -> r.namespace(namespace).metricName(metric)).metrics()).hasSize(2);
                minute = minute.plusSeconds(60);
            }
        } finally {
            logs.deleteLogGroup(r -> r.logGroupName(isolated));
        }
    }

    @Test
    void quietDefaultsDriveASumAlarmWithoutLaterIngestion() throws Exception {
        String isolated = createIsolatedGroup();
        String alarm = TestFixtures.uniqueName("metric-filter-alarm");
        try {
            put(isolated, "alarm", publishingFixture().get("metricDefaults"), null, List.of());
            cloudWatch.putMetricAlarm(r -> r.alarmName(alarm).namespace(namespace).metricName("alarm")
                    .statistic(Statistic.SUM).period(60).evaluationPeriods(1).threshold(20.0)
                    .comparisonOperator("GreaterThanThreshold").treatMissingData("notBreaching").actionsEnabled(false));
            Instant minute = Instant.ofEpochSecond((Instant.now().getEpochSecond() / 60 - 1) * 60);
            ingest(isolated, minute, List.of("INFO", "INFO", "INFO"));
            assertSeries("alarm", minute, List.of(), new ObjectMapper().readTree("{\"Sum\":21,\"SampleCount\":3}"));
            long deadline = System.nanoTime() + 25_000_000_000L;
            String state;
            do {
                state = cloudWatch.describeAlarms(r -> r.alarmNames(alarm)).metricAlarms().get(0).stateValueAsString();
                if ("ALARM".equals(state)) {
                    break;
                }
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            assertThat(state).isEqualTo("ALARM");
        } finally {
            cloudWatch.deleteAlarms(r -> r.alarmNames(alarm));
            logs.deleteLogGroup(r -> r.logGroupName(isolated));
        }
    }

    private static JsonNode publishingFixture() throws Exception {
        return MetricFilterFixture.load();
    }

    private static String createIsolatedGroup() {
        String isolated = group + "-" + TestFixtures.uniqueName("publishing");
        logs.createLogGroup(r -> r.logGroupName(isolated));
        try {
            logs.createLogStream(r -> r.logGroupName(isolated).logStreamName("s"));
        } catch (RuntimeException failure) {
            logs.deleteLogGroup(r -> r.logGroupName(isolated));
            throw failure;
        }
        return isolated;
    }

    private static void put(String isolated, String metric, JsonNode definition,
                            Map<String, String> dimensions, List<String> systemFields) {
        logs.putMetricFilter(r -> r.logGroupName(isolated).filterName("probe")
                .filterPattern(definition.get("filterPattern").asText()).emitSystemFieldDimensions(systemFields)
                .metricTransformations(MetricTransformation.builder().metricNamespace(namespace).metricName(metric)
                        .metricValue(definition.get("metricValue").asText())
                        .defaultValue(definition.has("defaultValue") ? definition.get("defaultValue").asDouble() : null)
                        .dimensions(dimensions).unit(StandardUnit.COUNT).build()));
    }

    private static void ingest(String isolated, Instant minute, List<String> messages) {
        List<InputLogEvent> events = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            events.add(InputLogEvent.builder().timestamp(minute.toEpochMilli() + i).message(messages.get(i)).build());
        }
        assertThat(logs.putLogEvents(r -> r.logGroupName(isolated).logStreamName("s").logEvents(events)))
                .satisfies(response -> assertThat(response.rejectedLogEventsInfo()).isNull());
    }

    private static List<Dimension> dimensions(JsonNode values, String account) {
        List<Dimension> dimensions = new ArrayList<>();
        values.fields().forEachRemaining(e -> dimensions.add(Dimension.builder().name(e.getKey()).value(
                e.getKey().equals("@aws.region") ? "us-east-1"
                        : e.getValue().asText().replace("<CALLER_ACCOUNT>", account)).build()));
        return dimensions;
    }

    private static void assertSeries(String metric, Instant minute, List<Dimension> dimensions, JsonNode expected)
            throws Exception {
        boolean present = expected != null && expected.has("Sum");
        List<Datapoint> points = cloudWatch.getMetricStatistics(r -> r.namespace(namespace).metricName(metric)
                .dimensions(dimensions).startTime(minute).endTime(minute.plusSeconds(60)).period(60)
                .statistics(Statistic.SUM, Statistic.SAMPLE_COUNT)).datapoints();
        assertThat(points).as(metric + " at " + minute + " " + dimensions).hasSize(present ? 1 : 0);
        if (present) {
            assertThat(points.get(0).timestamp()).isEqualTo(minute);
            assertThat(points.get(0).sum()).isEqualTo(expected.get("Sum").asDouble());
            assertThat(points.get(0).sampleCount()).isEqualTo(expected.get("SampleCount").asDouble());
            assertThat(points.get(0).unitAsString()).isEqualTo("Count");
        }
        List<MetricDataQuery> queries = new ArrayList<>();
        for (String stat : List.of("Sum", "SampleCount")) {
            queries.add(MetricDataQuery.builder().id(stat.toLowerCase()).returnData(true).metricStat(
                    MetricStat.builder().period(60).stat(stat).metric(Metric.builder().namespace(namespace)
                            .metricName(metric).dimensions(dimensions).build()).build()).build());
        }
        List<MetricDataResult> results = cloudWatch.getMetricData(r -> r.startTime(minute)
                .endTime(minute.plusSeconds(60)).metricDataQueries(queries)).metricDataResults();
        assertThat(results).extracting(MetricDataResult::id).containsExactlyInAnyOrder("sum", "samplecount");
        for (MetricDataResult result : results) {
            String stat = result.id().equals("sum") ? "Sum" : "SampleCount";
            assertThat(result.statusCodeAsString()).isEqualTo("Complete");
            assertThat(result.timestamps()).containsExactlyElementsOf(present ? List.of(minute) : List.of());
            assertThat(result.values()).containsExactlyElementsOf(
                    present ? List.of(expected.get(stat).asDouble()) : List.of());
        }
        MetricFilterQueryAssertions.assertSeries(namespace, metric, minute, dimensions, expected);
    }
}
