package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricStream;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudWatchMetricStreamsTest {

    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudWatchMetricsService service;
    private CloudWatchMetricsQueryHandler queryHandler;
    private CloudWatchMetricsJsonHandler jsonHandler;

    @BeforeEach
    void setUp() {
        service = new CloudWatchMetricsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000")
        );
        queryHandler = new CloudWatchMetricsQueryHandler(service);
        jsonHandler = new CloudWatchMetricsJsonHandler(service, MAPPER);
    }

    private MultivaluedMap<String, String> putParams(String name) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("Name", name);
        params.add("FirehoseArn", "arn:aws:firehose:us-east-1:000000000000:deliverystream/metrics");
        params.add("RoleArn", "arn:aws:iam::000000000000:role/metric-stream-role");
        params.add("OutputFormat", "json");
        params.add("ExcludeFilters.member.1.Namespace", "AWS/S3");
        params.add("ExcludeFilters.member.1.MetricNames.member.1", "BucketSizeBytes");
        return params;
    }

    @Test
    void putMetricStreamReturnsArnAndStoresDefinition() {
        Response response = queryHandler.handle("PutMetricStream", putParams("stream-a"), REGION);
        String xml = (String) response.getEntity();

        assertTrue(xml.contains("<PutMetricStreamResult>"));
        assertTrue(xml.contains("arn:aws:cloudwatch:us-east-1:000000000000:metric-stream/stream-a"));

        MetricStream stored = service.getMetricStream("stream-a", REGION);
        assertEquals("json", stored.getOutputFormat());
        assertEquals(MetricStream.STATE_RUNNING, stored.getState());
        assertEquals("AWS/S3", stored.getExcludeFilters().getFirst().getNamespace());
        assertEquals(List.of("BucketSizeBytes"), stored.getExcludeFilters().getFirst().getMetricNames());
    }

    @Test
    void getMetricStreamQueryReturnsFilters() {
        queryHandler.handle("PutMetricStream", putParams("stream-b"), REGION);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("Name", "stream-b");
        String xml = (String) queryHandler.handle("GetMetricStream", params, REGION).getEntity();

        assertTrue(xml.contains("<Name>stream-b</Name>"));
        assertTrue(xml.contains("<State>running</State>"));
        assertTrue(xml.contains("<ExcludeFilters>"));
        assertTrue(xml.contains("<Namespace>AWS/S3</Namespace>"));
        assertTrue(xml.contains("<member>BucketSizeBytes</member>"));
    }

    @Test
    void listMetricStreamsQueryReturnsEveryStream() {
        queryHandler.handle("PutMetricStream", putParams("stream-c"), REGION);
        queryHandler.handle("PutMetricStream", putParams("stream-d"), REGION);

        String xml = (String) queryHandler.handle("ListMetricStreams", new MultivaluedHashMap<>(), REGION).getEntity();

        assertTrue(xml.contains("<Entries>"));
        assertTrue(xml.contains("<Name>stream-c</Name>"));
        assertTrue(xml.contains("<Name>stream-d</Name>"));
    }

    @Test
    void deleteMetricStreamRemovesIt() {
        queryHandler.handle("PutMetricStream", putParams("stream-e"), REGION);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("Name", "stream-e");
        queryHandler.handle("DeleteMetricStream", params, REGION);

        assertThrows(AwsException.class, () -> service.getMetricStream("stream-e", REGION));
        assertTrue(service.listMetricStreams(REGION).isEmpty());
    }

    @Test
    void getUnknownMetricStreamThrowsResourceNotFound() {
        AwsException e = assertThrows(AwsException.class, () -> service.getMetricStream("absent", REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void stopAndStartMetricStreamsToggleState() {
        queryHandler.handle("PutMetricStream", putParams("stream-f"), REGION);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("Names.member.1", "stream-f");
        queryHandler.handle("StopMetricStreams", params, REGION);
        assertEquals(MetricStream.STATE_STOPPED, service.getMetricStream("stream-f", REGION).getState());

        queryHandler.handle("StartMetricStreams", params, REGION);
        assertEquals(MetricStream.STATE_RUNNING, service.getMetricStream("stream-f", REGION).getState());
    }

    @Test
    void putMetricStreamTagsAreReadableByListTagsForResource() {
        MultivaluedMap<String, String> params = putParams("stream-g");
        params.add("Tags.member.1.Key", "tofu-estate");
        params.add("Tags.member.1.Value", "probe1");
        queryHandler.handle("PutMetricStream", params, REGION);

        String arn = service.getMetricStream("stream-g", REGION).getArn();
        assertEquals("probe1", service.listTagsForResource(arn, REGION).get("tofu-estate"));

        service.tagResource(arn, Map.of("owner", "platform"), REGION);
        assertEquals("platform", service.listTagsForResource(arn, REGION).get("owner"));

        service.untagResource(arn, List.of("owner"), REGION);
        assertFalse(service.listTagsForResource(arn, REGION).containsKey("owner"));
    }

    @Test
    void reputtingAStreamKeepsCreationDateAndTags() {
        MultivaluedMap<String, String> params = putParams("stream-h");
        params.add("Tags.member.1.Key", "env");
        params.add("Tags.member.1.Value", "dev");
        queryHandler.handle("PutMetricStream", params, REGION);
        long createdAt = service.getMetricStream("stream-h", REGION).getCreationDate();

        MultivaluedMap<String, String> update = putParams("stream-h");
        update.putSingle("OutputFormat", "opentelemetry1.0");
        queryHandler.handle("PutMetricStream", update, REGION);

        MetricStream stored = service.getMetricStream("stream-h", REGION);
        assertEquals(createdAt, stored.getCreationDate());
        assertEquals("opentelemetry1.0", stored.getOutputFormat());
        assertEquals("dev", stored.getTags().get("env"));
    }

    @Test
    void jsonHandlerRoundTripsAStream() throws Exception {
        JsonNode request = MAPPER.readTree("""
                {
                  "Name": "json-stream",
                  "FirehoseArn": "arn:aws:firehose:us-east-1:000000000000:deliverystream/metrics",
                  "RoleArn": "arn:aws:iam::000000000000:role/metric-stream-role",
                  "OutputFormat": "json",
                  "ExcludeFilters": [{"Namespace": "AWS/Lambda", "MetricNames": ["Errors"]}],
                  "StatisticsConfigurations": [
                    {"IncludeMetrics": [{"Namespace": "AWS/EC2", "MetricName": "CPUUtilization"}],
                     "AdditionalStatistics": ["p99"]}
                  ]
                }
                """);
        Response put = jsonHandler.handle("PutMetricStream", request, REGION);
        JsonNode putBody = MAPPER.valueToTree(put.getEntity());
        assertTrue(putBody.path("Arn").asText().endsWith("metric-stream/json-stream"));

        JsonNode get = MAPPER.valueToTree(jsonHandler.handle("GetMetricStream",
                MAPPER.readTree("{\"Name\": \"json-stream\"}"), REGION).getEntity());
        assertEquals("json-stream", get.path("Name").asText());
        assertEquals("running", get.path("State").asText());
        assertEquals("AWS/Lambda", get.path("ExcludeFilters").get(0).path("Namespace").asText());
        assertEquals("Errors", get.path("ExcludeFilters").get(0).path("MetricNames").get(0).asText());
        assertEquals("p99",
                get.path("StatisticsConfigurations").get(0).path("AdditionalStatistics").get(0).asText());

        JsonNode list = MAPPER.valueToTree(jsonHandler.handle("ListMetricStreams",
                MAPPER.createObjectNode(), REGION).getEntity());
        assertEquals(1, list.path("Entries").size());
        assertEquals("json-stream", list.path("Entries").get(0).path("Name").asText());
    }
}
