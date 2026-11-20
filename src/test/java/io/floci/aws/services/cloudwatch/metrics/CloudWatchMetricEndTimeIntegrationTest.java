package io.floci.aws.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CloudWatchMetricEndTimeIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @Test
    void sdkHourlyWindowsDoNotDoubleCountTheSharedBoundary() {
        String namespace = "EndBoundary/" + UUID.randomUUID();
        Instant start = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(2, ChronoUnit.HOURS);
        Instant middle = start.plus(1, ChronoUnit.HOURS);
        Instant end = middle.plus(1, ChronoUnit.HOURS);
        try (CloudWatchClient client = AwsTestClients.create(CloudWatchClient.builder(), "test", Region.US_EAST_1)) {
            client.putMetricData(request -> request.namespace(namespace).metricData(
                    MetricDatum.builder().metricName("Counter").timestamp(start).value(3.0).build(),
                    MetricDatum.builder().metricName("Counter").timestamp(middle).value(7.0).build(),
                    MetricDatum.builder().metricName("Counter").timestamp(end).value(11.0).build()));
            assertEquals(List.of(3.0), client.getMetricStatistics(request -> request.namespace(namespace)
                    .metricName("Counter").startTime(start).endTime(middle).period(3600).statistics(Statistic.SUM))
                    .datapoints().stream().map(point -> point.sum()).toList());
            assertEquals(List.of(7.0), client.getMetricStatistics(request -> request.namespace(namespace)
                    .metricName("Counter").startTime(middle).endTime(end).period(3600).statistics(Statistic.SUM))
                    .datapoints().stream().map(point -> point.sum()).toList());
            assertEquals(List.of(3.0), client.getMetricData(request -> request.startTime(start).endTime(middle)
                    .metricDataQueries(query -> query.id("m").returnData(true).metricStat(stat -> stat
                            .metric(metric -> metric.namespace(namespace).metricName("Counter"))
                            .period(3600).stat("Sum")))).metricDataResults().getFirst().values());
        }
    }

    @Test
    void queryAndJsonExcludeTheSharedBoundary() throws JsonProcessingException {
        String namespace = "EndBoundary/" + UUID.randomUUID();
        Instant start = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(2, ChronoUnit.HOURS);
        Instant end = start.plus(1, ChronoUnit.HOURS);
        String scope = "AWS4-HMAC-SHA256 Credential=test/20261118/us-east-1/monitoring/aws4_request";
        try (CloudWatchClient client = AwsTestClients.create(CloudWatchClient.builder(), "test", Region.US_EAST_1)) {
            client.putMetricData(request -> request.namespace(namespace).metricData(
                    MetricDatum.builder().metricName("Counter").timestamp(start).value(3.0).build(),
                    MetricDatum.builder().metricName("Counter").timestamp(end).value(7.0).build()));
        }
        CloudWatchTestRequests.call(endpoint, scope, false, "GetMetricStatistics", Map.of(
                "Namespace", namespace, "MetricName", "Counter", "StartTime", start.toString(),
                "EndTime", end.toString(), "Period", 3600, "Statistics", List.of("Sum")))
                .then().statusCode(200)
                .body("GetMetricStatisticsResponse.GetMetricStatisticsResult.Datapoints.member.size()", equalTo(1))
                .body("GetMetricStatisticsResponse.GetMetricStatisticsResult.Datapoints.member.Sum", equalTo("3.0"));
        CloudWatchTestRequests.call(endpoint, scope, true, "GetMetricStatistics", Map.of(
                "Namespace", namespace, "MetricName", "Counter", "StartTime", start.getEpochSecond(),
                "EndTime", end.getEpochSecond(), "Period", 3600, "Statistics", List.of("Sum")))
                .then().statusCode(200).body("Datapoints.size()", equalTo(1))
                .body("Datapoints[0].Sum", equalTo(3.0f));
    }
}
