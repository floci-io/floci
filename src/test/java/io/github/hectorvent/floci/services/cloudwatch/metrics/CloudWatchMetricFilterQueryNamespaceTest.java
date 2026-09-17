package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudWatchMetricFilterQueryNamespaceTest {
    @ParameterizedTest
    @ValueSource(strings = {"GetMetricStatistics", "GetMetricData"})
    void metricFilterReadApisUseTheCloudWatchQueryNamespace(String action) throws Exception {
        CloudWatchMetricsService metrics = new CloudWatchMetricsService(new InMemoryStorage<>(),
                new InMemoryStorage<>(), new RegionResolver("us-east-1", "000000000000"));
        CloudWatchMetricsQueryHandler handler = new CloudWatchMetricsQueryHandler(metrics, null, null);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Namespace", "MetricFilters");
        params.putSingle("MetricName", "Count");
        params.putSingle("Period", "60");
        params.putSingle("StartTime", "2026-09-16T00:00:00Z");
        params.putSingle("EndTime", "2026-09-16T00:01:00Z");
        try (Response response = handler.handle(action, params, "us-east-1")) {
            assertEquals(200, response.getStatus());
            assertEquals(AwsNamespaces.CW, XmlParser.parseDocument((String) response.getEntity())
                    .getDocumentElement().getNamespaceURI());
        }
    }
}
