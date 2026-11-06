package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CloudWatch Metrics")
class CloudWatchTest {

    private static CloudWatchClient cloudWatchClient;

    @BeforeAll
    static void setUp() {
        cloudWatchClient = TestFixtures.cloudWatchClient();
    }

    @AfterAll
    static void tearDown() {
        if (cloudWatchClient != null) {
            cloudWatchClient.close();
        }
    }

    @Test
    @DisplayName("PutMetricData with simple value")
    void testPutMetricDataSimpleValue() {
        String namespace = "JavaTestSimple";
        
        PutMetricDataRequest request = PutMetricDataRequest.builder()
                .namespace(namespace)
                .metricData(datum -> datum
                        .metricName("RequestCount")
                        .value(42.0)
                        .unit(StandardUnit.COUNT)
                )
                .build();
        
        assertThatNoException().isThrownBy(() ->
                cloudWatchClient.putMetricData(request)
        );
    }

    @Test
    @DisplayName("ListMetrics returns metrics")
    void testListMetrics() {
        String namespace = "JavaTestList";
        
        // Setup
        cloudWatchClient.putMetricData(request -> request
                .namespace(namespace)
                .metricData(datum -> datum
                        .metricName("TestMetric")
                        .value(1.0)
                        .unit(StandardUnit.COUNT)
                )
        );

        ListMetricsResponse response = cloudWatchClient.listMetrics(request -> request
                .namespace(namespace)
        );
        
        assertThat(response.metrics()).isNotEmpty();
    }

    @Test
    @DisplayName("GetMetricStatistics returns aggregated data")
    void testGetMetricStatistics() {
        String namespace = "JavaTestStats";
        
        // Setup
        cloudWatchClient.putMetricData(request -> request
                .namespace(namespace)
                .metricData(datum -> datum
                        .metricName("StatsMetric")
                        .value(100.0)
                        .unit(StandardUnit.COUNT)
                )
        );

        Instant now = Instant.now();
        GetMetricStatisticsResponse response = cloudWatchClient.getMetricStatistics(request -> request
                .namespace(namespace)
                .metricName("StatsMetric")
                .startTime(now.minus(1, ChronoUnit.HOURS))
                .endTime(now.plus(1, ChronoUnit.MINUTES))
                .period(3600)
                .statistics(Statistic.SUM)
        );
        
        assertThat(response.datapoints()).isNotEmpty();
    }

    @Test
    @DisplayName("PutMetricData with StatisticValues")
    void testPutMetricDataWithStatisticValues() {
        String namespace = "JavaTestStatisticValues";
        Instant now = Instant.now();

        // Put metric data with pre-calculated statistics
        cloudWatchClient.putMetricData(request -> request
                .namespace(namespace)
                .metricData(datum -> datum
                        .metricName("AggregatedMetric")
                        .statisticValues(stats -> stats
                                .sampleCount(5.0)
                                .sum(150.0)
                                .minimum(20.0)
                                .maximum(40.0)
                        )
                        .unit(StandardUnit.COUNT)
                )
        );

        // Query back the statistics
        GetMetricStatisticsResponse response = cloudWatchClient.getMetricStatistics(request -> request
                .namespace(namespace)
                .metricName("AggregatedMetric")
                .startTime(now.minus(1, ChronoUnit.HOURS))
                .endTime(now.plus(1, ChronoUnit.MINUTES))
                .period(3600)
                .statistics(
                        Statistic.SUM,
                        Statistic.AVERAGE,
                        Statistic.MINIMUM,
                        Statistic.MAXIMUM,
                        Statistic.SAMPLE_COUNT
                )
        );

        assertThat(response.datapoints()).isNotEmpty();
        Datapoint dp = response.datapoints().get(0);
        assertThat(dp.sum()).isEqualTo(150.0);
        assertThat(dp.sampleCount()).isEqualTo(5.0);
        assertThat(dp.minimum()).isEqualTo(20.0);
        assertThat(dp.maximum()).isEqualTo(40.0);
        assertThat(dp.average()).isEqualTo(30.0); // sum / sampleCount
    }

    @Test
    @DisplayName("GetMetricStatistics honors the Unit filter")
    void testGetMetricStatisticsUnitFilter() {
        String namespace = "JavaTestUnitFilter";
        Instant now = Instant.now();

        cloudWatchClient.putMetricData(request -> request
                .namespace(namespace)
                .metricData(
                        datum -> datum
                                .metricName("UnitMetric")
                                .value(10.0)
                                .unit(StandardUnit.COUNT),
                        datum -> datum
                                .metricName("UnitMetric")
                                .value(20.0)
                                .unit(StandardUnit.BYTES)
                )
        );

        GetMetricStatisticsResponse matching = cloudWatchClient.getMetricStatistics(request -> request
                .namespace(namespace)
                .metricName("UnitMetric")
                .startTime(now.minus(1, ChronoUnit.HOURS))
                .endTime(now.plus(1, ChronoUnit.MINUTES))
                .period(3600)
                .statistics(Statistic.SUM)
                .unit(StandardUnit.COUNT)
        );
        assertThat(matching.datapoints()).hasSize(1);
        assertThat(matching.datapoints().get(0).sum()).isEqualTo(10.0);
        assertThat(matching.datapoints().get(0).unit()).isEqualTo(StandardUnit.COUNT);

        GetMetricStatisticsResponse noMatch = cloudWatchClient.getMetricStatistics(request -> request
                .namespace(namespace)
                .metricName("UnitMetric")
                .startTime(now.minus(1, ChronoUnit.HOURS))
                .endTime(now.plus(1, ChronoUnit.MINUTES))
                .period(3600)
                .statistics(Statistic.SUM)
                .unit(StandardUnit.MILLISECONDS)
        );
        assertThat(noMatch.datapoints()).isEmpty();
    }

    @Test
    @DisplayName("PutMetricData stores datums nested under EntityMetricData")
    void testPutMetricDataWithEntityMetricData() {
        String namespace = "JavaTestEntityMetricData";
        Instant now = Instant.now();

        cloudWatchClient.putMetricData(request -> request
                .namespace(namespace)
                .metricData(datum -> datum
                        .metricName("TopLevel")
                        .value(1.0)
                        .timestamp(now))
                .entityMetricData(entityMetricData -> entityMetricData
                        .entity(entity -> entity
                                .keyAttributes(Map.of(
                                        "Type", "AWS::Resource",
                                        "ResourceType", "AWS::EC2::Instance",
                                        "Identifier", "i-0123456789abcdef0")))
                        .metricData(MetricDatum.builder()
                                .metricName("mem_used_percent")
                                .value(6.5)
                                .unit(StandardUnit.PERCENT)
                                .storageResolution(60)
                                .timestamp(now)
                                .dimensions(Dimension.builder().name("host").value("h1").build())
                                .build()))
                .strictEntityValidation(false)
        );

        ListMetricsResponse listed = cloudWatchClient.listMetrics(request -> request.namespace(namespace));
        assertThat(listed.metrics())
                .extracting(Metric::metricName)
                .containsExactlyInAnyOrder("TopLevel", "mem_used_percent");

        GetMetricStatisticsResponse stats = cloudWatchClient.getMetricStatistics(request -> request
                .namespace(namespace)
                .metricName("mem_used_percent")
                .dimensions(Dimension.builder().name("host").value("h1").build())
                .startTime(now.minus(1, ChronoUnit.HOURS))
                .endTime(now.plus(1, ChronoUnit.MINUTES))
                .period(3600)
                .statistics(Statistic.SUM)
        );
        assertThat(stats.datapoints()).hasSize(1);
        assertThat(stats.datapoints().get(0).sum()).isEqualTo(6.5);
    }

    @Test
    @DisplayName("PutMetricData without MetricName throws MissingRequiredParameterException")
    void testPutMetricDataWithoutMetricNameIsRejected() {
        String namespace = "JavaTestMissingMetricName";

        assertThatThrownBy(() -> cloudWatchClient.putMetricData(request -> request
                .namespace(namespace)
                .entityMetricData(entityMetricData -> entityMetricData
                        .metricData(MetricDatum.builder().value(1.0).build()))))
                .isInstanceOf(MissingRequiredParameterException.class)
                .satisfies(e -> assertThat(((MissingRequiredParameterException) e).awsErrorDetails().errorCode())
                        .isEqualTo("MissingParameter"));

        assertThat(cloudWatchClient.listMetrics(request -> request.namespace(namespace)).metrics()).isEmpty();
    }

    @Test
    @DisplayName("DescribeAlarmHistory lists state transitions newest first and pages")
    void testDescribeAlarmHistory() {
        String alarmName = "java-history-" + System.currentTimeMillis();
        cloudWatchClient.putMetricAlarm(request -> request
                .alarmName(alarmName)
                .metricName("Depth")
                .namespace("JavaTestHistory")
                .statistic(Statistic.MAXIMUM)
                .period(60)
                .evaluationPeriods(1)
                .threshold(1.0)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD)
                .treatMissingData("ignore"));
        try {
            for (StateValue state : List.of(StateValue.OK, StateValue.ALARM, StateValue.OK)) {
                cloudWatchClient.setAlarmState(request -> request
                        .alarmName(alarmName).stateValue(state).stateReason("manual"));
            }

            DescribeAlarmHistoryResponse history = cloudWatchClient.describeAlarmHistory(request -> request
                    .alarmName(alarmName)
                    .historyItemType(HistoryItemType.STATE_UPDATE));
            assertThat(history.alarmHistoryItems())
                    .extracting(AlarmHistoryItem::historySummary)
                    .containsExactly("Alarm updated from ALARM to OK", "Alarm updated from OK to ALARM",
                            "Alarm updated from INSUFFICIENT_DATA to OK");
            assertThat(history.alarmHistoryItems().get(0).alarmType()).isEqualTo(AlarmType.METRIC_ALARM);
            assertThat(history.alarmHistoryItems().get(0).timestamp())
                    .isAfter(Instant.now().minus(5, ChronoUnit.MINUTES));

            DescribeAlarmHistoryResponse page = cloudWatchClient.describeAlarmHistory(request -> request
                    .alarmName(alarmName).maxRecords(1));
            assertThat(page.alarmHistoryItems()).hasSize(1);
            assertThat(page.nextToken()).isNotBlank();

            assertThatThrownBy(() -> cloudWatchClient.describeAlarmHistory(request -> request
                    .startDate(Instant.parse("2026-10-26T16:21:49.900Z"))
                    .endDate(Instant.parse("2026-10-26T16:21:49.500Z"))))
                    .isInstanceOf(InvalidParameterValueException.class)
                    .hasMessageContaining("must be before end date");
        } finally {
            cloudWatchClient.deleteAlarms(request -> request.alarmNames(alarmName));
        }
    }
}
