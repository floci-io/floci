package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.InvalidParameterCombinationException;
import software.amazon.awssdk.services.cloudwatch.model.InvalidParameterValueException;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.MissingRequiredParameterException;
import software.amazon.awssdk.services.cloudwatch.model.StatisticSet;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudWatchMetricDatumValidationIntegrationTest {

    private static StatisticSet.Builder statistics() {
        return StatisticSet.builder().sampleCount(2.0).sum(6.0).minimum(2.0).maximum(4.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SampleCount", "Sum", "Minimum", "Maximum"})
    void sdkIncompleteStatisticsRejectTheWholeBatch(String missing) {
        StatisticSet.Builder stats = statistics();
        switch (missing) {
            case "SampleCount" -> stats.sampleCount(null);
            case "Sum" -> stats.sum(null);
            case "Minimum" -> stats.minimum(null);
            case "Maximum" -> stats.maximum(null);
            default -> throw new IllegalArgumentException(missing);
        }
        for (Region region : List.of(Region.US_EAST_1, Region.US_GOV_WEST_1)) {
            String namespace = "DatumValidation/" + UUID.randomUUID();
            try (CloudWatchClient client = AwsTestClients.create(
                    CloudWatchClient.builder(), "000000000356", region)) {
                MissingRequiredParameterException error = assertThrows(MissingRequiredParameterException.class,
                        () -> client.putMetricData(request -> request.namespace(namespace).metricData(
                                MetricDatum.builder().metricName("First").value(1.0).build(),
                                MetricDatum.builder().metricName("Invalid").statisticValues(stats.build()).build())));

                assertEquals(400, error.statusCode());
                assertTrue(error.getMessage().contains("MetricData.member.2.StatisticValues." + missing));
                assertTrue(client.listMetrics(request -> request.namespace(namespace)).metrics().isEmpty());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void sdkScalarAndStatisticsRejectTheWholeBatch(String region) {
        String namespace = "DatumValidation/" + UUID.randomUUID();
        try (CloudWatchClient client = AwsTestClients.create(
                CloudWatchClient.builder(), "000000000356", Region.of(region))) {
            InvalidParameterCombinationException error = assertThrows(InvalidParameterCombinationException.class,
                    () -> client.putMetricData(request -> request.namespace(namespace).metricData(
                            MetricDatum.builder().metricName("First").value(1.0).build(),
                            MetricDatum.builder().metricName("Invalid").value(7.0)
                                    .statisticValues(statistics().build()).build())));

            assertEquals(400, error.statusCode());
            assertTrue(client.listMetrics(request -> request.namespace(namespace)).metrics().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void sdkValuesAtTheDocumentedBoundsPublishAndAdjacentValuesRejectTheWholeBatch(String region) {
        double bound = Math.scalb(1.0, 360);
        try (CloudWatchClient client = AwsTestClients.create(
                CloudWatchClient.builder(), "000000000356", Region.of(region))) {
            for (double value : List.of(-bound, bound)) {
                String namespace = "DatumBounds/" + UUID.randomUUID();
                Instant now = Instant.now();
                client.putMetricData(request -> request.namespace(namespace).metricData(
                        MetricDatum.builder().metricName("Boundary").value(value).timestamp(now).build()));
                assertEquals(value, client.getMetricStatistics(request -> request.namespace(namespace)
                        .metricName("Boundary").startTime(now.minusSeconds(120)).endTime(now.plusSeconds(120))
                        .period(60).statisticsWithStrings("Sum")).datapoints().getFirst().sum());
            }
            for (double value : List.of(Math.nextDown(-bound), Math.nextUp(bound))) {
                String namespace = "DatumBounds/" + UUID.randomUUID();
                InvalidParameterValueException error = assertThrows(InvalidParameterValueException.class,
                        () -> client.putMetricData(request -> request.namespace(namespace).metricData(
                                MetricDatum.builder().metricName("First").value(1.0).build(),
                                MetricDatum.builder().metricName("Outside").value(value).build())));
                assertEquals(400, error.statusCode());
                assertTrue(client.listMetrics(request -> request.namespace(namespace)).metrics().isEmpty());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void sdkValidScalarAndCompleteStatisticsRetainTheirValues(String region) {
        String namespace = "DatumValidation/" + UUID.randomUUID();
        Instant now = Instant.now();
        try (CloudWatchClient client = AwsTestClients.create(
                CloudWatchClient.builder(), "000000000356", Region.of(region))) {
            client.putMetricData(request -> request.namespace(namespace).metricData(
                    MetricDatum.builder().metricName("Scalar").value(7.0).timestamp(now).build(),
                    MetricDatum.builder().metricName("Statistics").statisticValues(statistics().build())
                            .timestamp(now).build()));

            for (String metric : List.of("Scalar", "Statistics")) {
                double expected = metric.equals("Scalar") ? 7.0 : 6.0;
                assertEquals(expected, client.getMetricStatistics(request -> request.namespace(namespace)
                        .metricName(metric).startTime(now.minusSeconds(120)).endTime(now.plusSeconds(120))
                        .period(60).statisticsWithStrings("Sum")).datapoints().getFirst().sum());
            }
        }
    }
}
