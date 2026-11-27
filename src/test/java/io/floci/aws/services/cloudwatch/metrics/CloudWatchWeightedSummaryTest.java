package io.floci.aws.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.floci.aws.services.cloudwatch.metrics.model.MetricDatum;
import io.floci.aws.services.cloudwatch.metricstreams.CloudWatchMetricStreamsService;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CloudWatchWeightedSummaryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "eu-west-1";
    private static final Instant START = Instant.now().minusSeconds(60);
    private final InMemoryStorage<String, MetricDatum> store = new InMemoryStorage<>();
    private final CloudWatchMetricsService service = new CloudWatchMetricsService(
            store, new InMemoryStorage<>(), new RegionResolver(REGION, "123456789012"));
    private final CloudWatchMetricsJsonHandler json = new CloudWatchMetricsJsonHandler(
            service, mock(CloudWatchDashboardsService.class), mock(CloudWatchMetricStreamsService.class), MAPPER);
    private final CloudWatchMetricsQueryHandler query = new CloudWatchMetricsQueryHandler(
            service, mock(CloudWatchDashboardsService.class), mock(CloudWatchMetricStreamsService.class));

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void statisticValuesBesideValuesAreAcceptedAndTheDistributionIsCountedOnce(boolean useQuery) {
        publish(useQuery, weighted());
        CloudWatchMetricsService.Datapoint point = service.getMetricStatistics(
                "Example/Weighted", "Latency", List.of(), START.minusSeconds(1), START.plusSeconds(1),
                60, List.of("SampleCount", "Sum", "Minimum", "Maximum"), "Milliseconds", REGION).getFirst();
        assertEquals(5, point.sampleCount());
        assertEquals(43, point.sum());
        assertEquals(7, point.minimum());
        assertEquals(11, point.maximum());
        assertEquals(8.6, point.average());
        assertEquals(2, store.keys().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void scalarValueStillConflictsWithWeightedValues(boolean useQuery) {
        ObjectNode datum = weighted();
        datum.remove("StatisticValues");
        datum.put("Value", 7);
        AwsException error = assertThrows(AwsException.class, () -> publish(useQuery, datum));
        assertEquals("InvalidParameterCombination", error.getErrorCode());
        assertTrue(store.keys().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedSummaryStillRejectsTheBatchBeforePublication(boolean useQuery) {
        ObjectNode datum = weighted();
        ((ObjectNode) datum.get("StatisticValues")).remove("Maximum");
        AwsException error = assertThrows(AwsException.class, () -> publish(useQuery, datum));
        assertEquals("MissingParameter", error.getErrorCode());
        assertTrue(store.keys().isEmpty());
    }

    private static ObjectNode weighted() {
        ObjectNode datum = MAPPER.createObjectNode().put("MetricName", "Latency").put("Unit", "Milliseconds")
                .put("Timestamp", START.toString());
        datum.putArray("Values").add(7).add(11);
        datum.putArray("Counts").add(3).add(2);
        datum.putObject("StatisticValues").put("SampleCount", 5).put("Sum", 43)
                .put("Minimum", 7).put("Maximum", 11);
        return datum;
    }

    private void publish(boolean useQuery, ObjectNode datum) {
        ObjectNode request = MAPPER.createObjectNode().put("Namespace", "Example/Weighted");
        request.putArray("MetricData").add(datum);
        MultivaluedMap<String, String> params = CloudWatchTestRequests.toQuery(request);
        try (Response response = useQuery ? query.handle("PutMetricData", params, REGION)
                : json.handle("PutMetricData", request, REGION)) {
            assertEquals(200, response.getStatus());
        }
    }

}
