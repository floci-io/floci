package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The metric filter operations through the Logs handler: request members in the JSON 1.1 form the
 * API takes, responses in the shape the API reference lists, and the real filter count on
 * DescribeLogGroups.
 */
class CloudWatchLogsMetricFilterHandlerTest {

    private static final String REGION = "us-east-1";
    private static final String GROUP = "/app/api";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudWatchLogsHandler handler;

    @BeforeEach
    void setUp() {
        CloudWatchLogsService service = new CloudWatchLogsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), 10_000, new RegionResolver(REGION, "000000000000"));
        handler = new CloudWatchLogsHandler(service,
                new CloudWatchLogsCrossAccountService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                        new RegionResolver(REGION, "000000000000"), MAPPER),
                new CloudWatchLogsMetricFilterService(new InMemoryStorage<>(), service, mock(CloudWatchMetricsService.class)),
                MAPPER);
        service.createLogGroup(GROUP, null, null, REGION);
    }

    private JsonNode call(String action, String json) {
        try {
            Response response = handler.handle(action, MAPPER.readTree(json), REGION);
            assertEquals(200, response.getStatus());
            return MAPPER.valueToTree(response.getEntity());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final String PUT = """
            {"logGroupName": "/app/api", "filterName": "errors", "filterPattern": "[..., status_code, size]",
             "metricTransformations": [{"metricName": "Volume", "metricNamespace": "Web", "metricValue": "$size",
               "dimensions": {"Status": "$status_code"}, "unit": "Bytes"}],
             "applyOnTransformedLogs": false, "emitSystemFieldDimensions": ["@aws.region"]}
            """;

    @Test
    void putThenDescribeRoundTripsTheApiShape() {
        JsonNode putResponse = call("PutMetricFilter", PUT);
        assertTrue(putResponse.isObject() && putResponse.isEmpty(), "PutMetricFilter answers an empty object");

        JsonNode described = call("DescribeMetricFilters", "{\"logGroupName\": \"/app/api\"}");
        assertEquals(1, described.path("metricFilters").size());
        JsonNode filter = described.path("metricFilters").get(0);
        assertEquals("errors", filter.path("filterName").asText());
        assertEquals("/app/api", filter.path("logGroupName").asText());
        assertEquals("[..., status_code, size]", filter.path("filterPattern").asText());
        assertTrue(filter.path("creationTime").asLong() > 0);
        assertFalse(filter.path("applyOnTransformedLogs").asBoolean());
        assertEquals("@aws.region", filter.path("emitSystemFieldDimensions").get(0).asText());
        JsonNode t = filter.path("metricTransformations").get(0);
        assertEquals("Volume", t.path("metricName").asText());
        assertEquals("Web", t.path("metricNamespace").asText());
        assertEquals("$size", t.path("metricValue").asText());
        assertEquals("$status_code", t.path("dimensions").path("Status").asText());
        assertEquals("Bytes", t.path("unit").asText());
        assertFalse(t.has("defaultValue"), "an unset default value is left out");
        assertFalse(described.has("nextToken"));
    }

    @Test
    void defaultValueIsANumberOnTheWire() {
        call("PutMetricFilter", """
                {"logGroupName": "/app/api", "filterName": "errors", "filterPattern": "ERROR",
                 "metricTransformations": [{"metricName": "Errors", "metricNamespace": "App", "metricValue": "1",
                   "defaultValue": 0}]}
                """);

        JsonNode t = call("DescribeMetricFilters", "{\"logGroupName\": \"/app/api\"}")
                .path("metricFilters").get(0).path("metricTransformations").get(0);
        assertTrue(t.path("defaultValue").isNumber());
        assertEquals(0.0, t.path("defaultValue").asDouble());

        AwsException e = assertThrows(AwsException.class, () -> call("PutMetricFilter", """
                {"logGroupName": "/app/api", "filterName": "bad", "filterPattern": "ERROR",
                 "metricTransformations": [{"metricName": "Errors", "metricNamespace": "App", "metricValue": "1",
                   "defaultValue": "zero"}]}
                """));
        assertEquals("InvalidParameterException", e.getErrorCode());
    }

    @Test
    void describeHonoursLimitAndNextToken() {
        for (int i = 0; i < 3; i++) {
            call("PutMetricFilter", PUT.replace("\"errors\"", "\"f" + i + "\""));
        }

        JsonNode first = call("DescribeMetricFilters", "{\"logGroupName\": \"/app/api\", \"limit\": 2}");
        assertEquals(2, first.path("metricFilters").size());
        String token = first.path("nextToken").asText();
        JsonNode second = call("DescribeMetricFilters",
                "{\"logGroupName\": \"/app/api\", \"limit\": 2, \"nextToken\": \"" + token + "\"}");
        assertEquals(1, second.path("metricFilters").size());
        assertFalse(second.has("nextToken"));
    }

    @Test
    void describeLogGroupsCountsTheFilters() {
        assertEquals(0, call("DescribeLogGroups", "{}").path("logGroups").get(0).path("metricFilterCount").asInt());
        call("PutMetricFilter", PUT);
        call("PutMetricFilter", PUT.replace("\"errors\"", "\"more\""));

        assertEquals(2, call("DescribeLogGroups", "{}").path("logGroups").get(0).path("metricFilterCount").asInt());
    }

    @Test
    void deleteRemovesTheFilter() {
        call("PutMetricFilter", PUT);

        JsonNode response = call("DeleteMetricFilter", "{\"logGroupName\": \"/app/api\", \"filterName\": \"errors\"}");

        assertTrue(response.isObject() && response.isEmpty());
        assertEquals(0, call("DescribeMetricFilters", "{\"logGroupName\": \"/app/api\"}").path("metricFilters").size());
        AwsException e = assertThrows(AwsException.class, () -> call("DeleteMetricFilter",
                "{\"logGroupName\": \"/app/api\", \"filterName\": \"errors\"}"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void testMetricFilterAnswersTheReferenceShape() {
        JsonNode response = call("TestMetricFilter", """
                {"filterPattern": "[..., status_code=200, size]",
                 "logEventMessages": [
                   "127.0.0.1 - frank [10/Oct/2000:13:25:15 -0700] \\"GET /apache_pb.gif HTTP/1.0\\" 200 1534",
                   "127.0.0.1 - frank [10/Oct/2000:13:35:22 -0700] \\"GET /apache_pb.gif HTTP/1.0\\" 500 5324"]}
                """);

        assertEquals(1, response.path("matches").size());
        JsonNode match = response.path("matches").get(0);
        assertEquals(0, match.path("eventNumber").asLong());
        assertTrue(match.path("eventMessage").asText().endsWith("200 1534"));
        assertEquals("1534", match.path("extractedValues").path("$size").asText());
        assertEquals("200", match.path("extractedValues").path("$status_code").asText());
        assertEquals("127.0.0.1", match.path("extractedValues").path("$1").asText());

        JsonNode terms = call("TestMetricFilter",
                "{\"filterPattern\": \"\\\"[ERROR]\\\"\", \"logEventMessages\": [\"[INFO] up\", \"[ERROR] down\"]}");
        assertEquals(1, terms.path("matches").get(0).path("eventNumber").asLong());
        assertTrue(terms.path("matches").get(0).path("extractedValues").isObject());
        assertTrue(terms.path("matches").get(0).path("extractedValues").isEmpty());
    }
}
