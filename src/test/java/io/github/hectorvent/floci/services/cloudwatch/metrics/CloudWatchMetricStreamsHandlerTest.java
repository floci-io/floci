package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.github.hectorvent.floci.services.cloudwatch.metricstreams.CloudWatchMetricStreamsService;
import io.github.hectorvent.floci.services.cloudwatch.metricstreams.model.MetricStream;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both CloudWatch handlers must serve the metric stream operations with the same semantics,
 * since the Terraform provider speaks Query and the CLI and SDK v3 speak JSON.
 */
class CloudWatchMetricStreamsHandlerTest {

    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudWatchMetricStreamsService streamsService;
    private CloudWatchMetricsQueryHandler queryHandler;
    private CloudWatchMetricsJsonHandler jsonHandler;

    @BeforeEach
    void setUp() {
        RegionResolver resolver = new RegionResolver(REGION, "000000000000");
        CloudWatchMetricsService metricsService = new CloudWatchMetricsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), resolver);
        CloudWatchDashboardsService dashboardsService =
                new CloudWatchDashboardsService(new InMemoryStorage<>(), resolver);
        streamsService = new CloudWatchMetricStreamsService(new InMemoryStorage<>(), resolver);
        queryHandler = new CloudWatchMetricsQueryHandler(metricsService, dashboardsService, streamsService);
        jsonHandler = new CloudWatchMetricsJsonHandler(metricsService, dashboardsService, streamsService, MAPPER);
    }

    private static MultivaluedMap<String, String> putParams(String name) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("Name", name);
        params.add("FirehoseArn", "arn:aws:firehose:us-east-1:000000000000:deliverystream/metrics");
        params.add("RoleArn", "arn:aws:iam::000000000000:role/metric-stream-role");
        params.add("OutputFormat", "json");
        params.add("ExcludeFilters.member.1.Namespace", "AWS/S3");
        params.add("ExcludeFilters.member.1.MetricNames.member.1", "BucketSizeBytes");
        params.add("StatisticsConfigurations.member.1.IncludeMetrics.member.1.Namespace", "AWS/EC2");
        params.add("StatisticsConfigurations.member.1.IncludeMetrics.member.1.MetricName", "CPUUtilization");
        params.add("StatisticsConfigurations.member.1.AdditionalStatistics.member.1", "p99");
        return params;
    }

    private String query(String action, MultivaluedMap<String, String> params) {
        Response response = queryHandler.handle(action, params, REGION);
        assertEquals(200, response.getStatus());
        return (String) response.getEntity();
    }

    @Test
    void queryPutParsesTheNestedMembersAndAnswersWithTheArn() {
        String xml = query("PutMetricStream", putParams("stream-a"));

        assertTrue(xml.contains("<PutMetricStreamResult>"));
        assertTrue(xml.contains("<Arn>arn:aws:cloudwatch:us-east-1:000000000000:metric-stream/stream-a</Arn>"));

        MetricStream stored = streamsService.getMetricStream("stream-a", REGION);
        assertEquals("AWS/S3", stored.getExcludeFilters().get(0).getNamespace());
        assertEquals(List.of("BucketSizeBytes"), stored.getExcludeFilters().get(0).getMetricNames());
        assertEquals("CPUUtilization",
                stored.getStatisticsConfigurations().get(0).getIncludeMetrics().get(0).metricName());
        assertEquals(List.of("p99"), stored.getStatisticsConfigurations().get(0).getAdditionalStatistics());
    }

    @Test
    void queryGetRendersFiltersAndStatisticsConfigurations() {
        query("PutMetricStream", putParams("stream-b"));

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("Name", "stream-b");
        String xml = query("GetMetricStream", params);

        assertTrue(xml.contains("<GetMetricStreamResult>"));
        assertTrue(xml.contains("<Name>stream-b</Name>"));
        assertTrue(xml.contains("<State>running</State>"));
        assertTrue(xml.contains("<IncludeLinkedAccountsMetrics>false</IncludeLinkedAccountsMetrics>"));
        assertTrue(xml.contains("<ExcludeFilters><member><Namespace>AWS/S3</Namespace>"
                + "<MetricNames><member>BucketSizeBytes</member></MetricNames></member></ExcludeFilters>"));
        assertTrue(xml.contains("<AdditionalStatistics><member>p99</member></AdditionalStatistics>"));
        assertFalse(xml.contains("<IncludeFilters>"));
    }

    @Test
    void queryListStopStartAndDeleteRoundTrip() {
        query("PutMetricStream", putParams("stream-c"));
        query("PutMetricStream", putParams("stream-d"));

        String list = query("ListMetricStreams", new MultivaluedHashMap<>());
        assertTrue(list.contains("<Entries><member><Arn>"));
        assertTrue(list.contains("<Name>stream-c</Name>"));
        assertTrue(list.contains("<Name>stream-d</Name>"));

        MultivaluedMap<String, String> names = new MultivaluedHashMap<>();
        names.add("Names.member.1", "stream-c");
        names.add("Names.member.2", "stream-d");
        assertTrue(query("StopMetricStreams", names).contains("<StopMetricStreamsResult"));
        assertEquals(MetricStream.STATE_STOPPED, streamsService.getMetricStream("stream-d", REGION).getState());
        assertTrue(query("StartMetricStreams", names).contains("<StartMetricStreamsResult"));
        assertEquals(MetricStream.STATE_RUNNING, streamsService.getMetricStream("stream-d", REGION).getState());

        MultivaluedMap<String, String> delete = new MultivaluedHashMap<>();
        delete.add("Name", "stream-c");
        assertTrue(query("DeleteMetricStream", delete).contains("<DeleteMetricStreamResult"));
        assertEquals(1, streamsService.listMetricStreams(REGION).size());
    }

    @Test
    void bothProtocolsPageTheListWithMaxResultsAndNextToken() throws Exception {
        query("PutMetricStream", putParams("page-a"));
        query("PutMetricStream", putParams("page-b"));
        query("PutMetricStream", putParams("page-c"));

        MultivaluedMap<String, String> first = new MultivaluedHashMap<>();
        first.add("MaxResults", "2");
        String xml = query("ListMetricStreams", first);
        assertTrue(xml.contains("<Name>page-a</Name>"));
        assertTrue(xml.contains("<Name>page-b</Name>"));
        assertFalse(xml.contains("<Name>page-c</Name>"));
        String token = xml.substring(xml.indexOf("<NextToken>") + "<NextToken>".length(), xml.indexOf("</NextToken>"));

        JsonNode second = MAPPER.valueToTree(jsonHandler.handle("ListMetricStreams",
                MAPPER.readTree("{\"MaxResults\": 2, \"NextToken\": \"" + token + "\"}"), REGION).getEntity());
        assertEquals(1, second.path("Entries").size());
        assertEquals("page-c", second.path("Entries").get(0).path("Name").asText());
        assertFalse(second.has("NextToken"));

        // The Query controller renders AwsException as the XML error envelope.
        AwsException bad = assertThrows(AwsException.class,
                () -> queryHandler.handle("ListMetricStreams", withToken("not base64!"), REGION));
        assertEquals("InvalidNextToken", bad.getErrorCode());
    }

    private static MultivaluedMap<String, String> withToken(String token) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("NextToken", token);
        return params;
    }

    @Test
    void queryTagOperationsRouteAMetricStreamArn() {
        MultivaluedMap<String, String> params = putParams("stream-e");
        params.add("Tags.member.1.Key", "team");
        params.add("Tags.member.1.Value", "platform");
        query("PutMetricStream", params);
        String arn = streamsService.getMetricStream("stream-e", REGION).getArn();

        MultivaluedMap<String, String> tag = new MultivaluedHashMap<>();
        tag.add("ResourceARN", arn);
        tag.add("Tags.member.1.Key", "owner");
        tag.add("Tags.member.1.Value", "ops");
        query("TagResource", tag);

        MultivaluedMap<String, String> list = new MultivaluedHashMap<>();
        list.add("ResourceARN", arn);
        String xml = query("ListTagsForResource", list);
        assertTrue(xml.contains("<Key>team</Key><Value>platform</Value>"));
        assertTrue(xml.contains("<Key>owner</Key><Value>ops</Value>"));

        MultivaluedMap<String, String> untag = new MultivaluedHashMap<>();
        untag.add("ResourceARN", arn);
        untag.add("TagKeys.member.1", "team");
        query("UntagResource", untag);
        assertFalse(query("ListTagsForResource", list).contains("<Key>team</Key>"));
    }

    @Test
    void jsonHandlerRoundTripsAStream() throws Exception {
        JsonNode request = MAPPER.readTree("""
                {
                  "Name": "json-stream",
                  "FirehoseArn": "arn:aws:firehose:us-east-1:000000000000:deliverystream/metrics",
                  "RoleArn": "arn:aws:iam::000000000000:role/metric-stream-role",
                  "OutputFormat": "opentelemetry1.0",
                  "IncludeLinkedAccountsMetrics": true,
                  "IncludeFilters": [{"Namespace": "AWS/Lambda", "MetricNames": ["Errors"]}],
                  "StatisticsConfigurations": [
                    {"IncludeMetrics": [{"Namespace": "AWS/EC2", "MetricName": "CPUUtilization"}],
                     "AdditionalStatistics": ["p99"]}
                  ],
                  "Tags": [{"Key": "team", "Value": "platform"}]
                }
                """);
        JsonNode put = MAPPER.valueToTree(jsonHandler.handle("PutMetricStream", request, REGION).getEntity());
        assertEquals("arn:aws:cloudwatch:us-east-1:000000000000:metric-stream/json-stream", put.path("Arn").asText());

        JsonNode get = MAPPER.valueToTree(jsonHandler.handle("GetMetricStream",
                MAPPER.readTree("{\"Name\": \"json-stream\"}"), REGION).getEntity());
        assertEquals("json-stream", get.path("Name").asText());
        assertEquals("running", get.path("State").asText());
        assertEquals("opentelemetry1.0", get.path("OutputFormat").asText());
        assertTrue(get.path("IncludeLinkedAccountsMetrics").asBoolean());
        assertTrue(get.path("CreationDate").isNumber());
        assertEquals("AWS/Lambda", get.path("IncludeFilters").get(0).path("Namespace").asText());
        assertEquals("Errors", get.path("IncludeFilters").get(0).path("MetricNames").get(0).asText());
        assertFalse(get.has("ExcludeFilters"));
        assertEquals("p99",
                get.path("StatisticsConfigurations").get(0).path("AdditionalStatistics").get(0).asText());

        JsonNode tags = MAPPER.valueToTree(jsonHandler.handle("ListTagsForResource",
                MAPPER.readTree("{\"ResourceARN\": \"" + put.path("Arn").asText() + "\"}"), REGION).getEntity());
        assertEquals("team", tags.path("Tags").get(0).path("Key").asText());

        jsonHandler.handle("StopMetricStreams", MAPPER.readTree("{\"Names\": [\"json-stream\"]}"), REGION);
        JsonNode list = MAPPER.valueToTree(jsonHandler.handle("ListMetricStreams",
                MAPPER.createObjectNode(), REGION).getEntity());
        assertEquals(1, list.path("Entries").size());
        assertEquals("stopped", list.path("Entries").get(0).path("State").asText());

        jsonHandler.handle("DeleteMetricStream", MAPPER.readTree("{\"Name\": \"json-stream\"}"), REGION);
        assertTrue(streamsService.listMetricStreams(REGION).isEmpty());
    }
}
