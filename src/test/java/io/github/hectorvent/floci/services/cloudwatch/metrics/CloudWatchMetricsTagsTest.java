package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.github.hectorvent.floci.services.cloudwatch.metricstreams.CloudWatchMetricStreamsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudWatchMetricsTagsTest {

    private static final String REGION = "us-east-1";
    private CloudWatchMetricsService service;
    private CloudWatchMetricsQueryHandler queryHandler;
    private CloudWatchMetricsJsonHandler jsonHandler;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new CloudWatchMetricsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000")
        );
        CloudWatchDashboardsService dashboardsService = new CloudWatchDashboardsService(
                new InMemoryStorage<>(), new RegionResolver("us-east-1", "000000000000"));
        CloudWatchMetricStreamsService metricStreamsService = new CloudWatchMetricStreamsService(
                new InMemoryStorage<>(), new RegionResolver("us-east-1", "000000000000"));
        queryHandler = new CloudWatchMetricsQueryHandler(service, dashboardsService, metricStreamsService);
        jsonHandler = new CloudWatchMetricsJsonHandler(service, dashboardsService, metricStreamsService, objectMapper);
    }

    @Test
    void listTagsReturnsEmptyForNoTags() {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("test-alarm");
        alarm.setAlarmArn("arn:aws:cloudwatch:us-east-1:000000000000:alarm:test-alarm");
        service.putMetricAlarm(alarm, REGION);

        Map<String, String> tags = service.listTagsForResource(alarm.getAlarmArn(), REGION);
        assertTrue(tags.isEmpty());
    }

    @Test
    void tagAndListTags() {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("test-alarm");
        alarm.setAlarmArn("arn:aws:cloudwatch:us-east-1:000000000000:alarm:test-alarm");
        service.putMetricAlarm(alarm, REGION);

        service.tagResource(alarm.getAlarmArn(), Map.of("env", "prod", "team", "ops"), REGION);

        Map<String, String> tags = service.listTagsForResource(alarm.getAlarmArn(), REGION);
        assertEquals(2, tags.size());
        assertEquals("prod", tags.get("env"));
        assertEquals("ops", tags.get("team"));
    }

    @Test
    void untagResource() {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("test-alarm");
        alarm.setAlarmArn("arn:aws:cloudwatch:us-east-1:000000000000:alarm:test-alarm");
        service.putMetricAlarm(alarm, REGION);

        service.tagResource(alarm.getAlarmArn(), Map.of("env", "prod", "team", "ops"), REGION);
        service.untagResource(alarm.getAlarmArn(), List.of("team"), REGION);

        Map<String, String> tags = service.listTagsForResource(alarm.getAlarmArn(), REGION);
        assertEquals(1, tags.size());
        assertEquals("prod", tags.get("env"));
        assertNull(tags.get("team"));
    }

    @Test
    void tagsViaPutMetricAlarmQuery() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("AlarmName", "query-alarm");
        params.add("Tags.member.1.Key", "env");
        params.add("Tags.member.1.Value", "dev");
        params.add("Tags.member.2.Key", "app");
        params.add("Tags.member.2.Value", "floci");

        queryHandler.handle("PutMetricAlarm", params, REGION);

        MetricAlarm alarm = service.describeAlarms(List.of("query-alarm"), null, REGION).getFirst();
        Map<String, String> tags = service.listTagsForResource(alarm.getAlarmArn(), REGION);
        assertEquals("dev", tags.get("env"));
        assertEquals("floci", tags.get("app"));
    }

    @Test
    void listTagsQueryResponse() {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("test-alarm");
        alarm.setAlarmArn("arn:aws:cloudwatch:us-east-1:000000000000:alarm:test-alarm");
        service.putMetricAlarm(alarm, REGION);
        service.tagResource(alarm.getAlarmArn(), Map.of("env", "prod"), REGION);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("ResourceARN", alarm.getAlarmArn());

        Response response = queryHandler.handle("ListTagsForResource", params, REGION);
        String xml = (String) response.getEntity();

        assertTrue(xml.contains("<Tags>"));
        assertTrue(xml.contains("<member>"));
        assertTrue(xml.contains("<Key>env</Key>"));
        assertTrue(xml.contains("<Value>prod</Value>"));
    }

    @Test
    void tagResourceQueryResponseCarriesTheResultWrapper() {
        // TagResourceOutput is an empty structure, so the Query response still declares a
        // TagResourceResult element. The AWS Go SDK v2 unmarshaler rejects a response without it.
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("test-alarm");
        alarm.setAlarmArn("arn:aws:cloudwatch:us-east-1:000000000000:alarm:test-alarm");
        service.putMetricAlarm(alarm, REGION);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("ResourceARN", alarm.getAlarmArn());
        params.add("Tags.member.1.Key", "env");
        params.add("Tags.member.1.Value", "prod");

        Response response = queryHandler.handle("TagResource", params, REGION);
        String xml = (String) response.getEntity();

        assertTrue(xml.contains("<TagResourceResponse>"), xml);
        assertTrue(xml.contains("<TagResourceResult"), xml);
        assertTrue(xml.contains("<ResponseMetadata>"), xml);
        assertEquals("prod", service.listTagsForResource(alarm.getAlarmArn(), REGION).get("env"));
    }

    @Test
    void untagResourceQueryResponseCarriesTheResultWrapper() {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("test-alarm");
        alarm.setAlarmArn("arn:aws:cloudwatch:us-east-1:000000000000:alarm:test-alarm");
        service.putMetricAlarm(alarm, REGION);
        service.tagResource(alarm.getAlarmArn(), Map.of("env", "prod"), REGION);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("ResourceARN", alarm.getAlarmArn());
        params.add("TagKeys.member.1", "env");

        Response response = queryHandler.handle("UntagResource", params, REGION);
        String xml = (String) response.getEntity();

        assertTrue(xml.contains("<UntagResourceResponse>"), xml);
        assertTrue(xml.contains("<UntagResourceResult"), xml);
        assertTrue(service.listTagsForResource(alarm.getAlarmArn(), REGION).isEmpty());
    }

    @Test
    void listTagsJsonResponse() {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("test-alarm");
        alarm.setAlarmArn("arn:aws:cloudwatch:us-east-1:000000000000:alarm:test-alarm");
        service.putMetricAlarm(alarm, REGION);
        service.tagResource(alarm.getAlarmArn(), Map.of("env", "prod"), REGION);

        JsonNode request = objectMapper.createObjectNode().put("ResourceARN", alarm.getAlarmArn());
        Response response = jsonHandler.handle("ListTagsForResource", request, REGION);
        JsonNode entity = (JsonNode) response.getEntity();

        assertTrue(entity.has("Tags"));
        JsonNode tags = entity.get("Tags");
        assertTrue(tags.isArray());
        assertEquals(1, tags.size());
        assertEquals("env", tags.get(0).get("Key").asText());
        assertEquals("prod", tags.get(0).get("Value").asText());
    }
}
