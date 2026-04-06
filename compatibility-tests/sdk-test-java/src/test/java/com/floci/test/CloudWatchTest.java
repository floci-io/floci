package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
}
