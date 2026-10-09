package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CloudWatchMetricMathAlarmTest {

    private static final String REGION = "us-east-1";
    private static final String ALARM_NAME = "metric-math-round-trip";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String XML_ALARM = "/DescribeAlarmsResponse/DescribeAlarmsResult/MetricAlarms/member";

    private CloudWatchMetricsJsonHandler jsonHandler;
    private CloudWatchMetricsQueryHandler queryHandler;

    @BeforeEach
    void setup() {
        initialize(new InMemoryStorage<>());
    }

    private void initialize(StorageBackend<String, MetricAlarm> alarmStore) {
        RegionResolver resolver = new RegionResolver(REGION, "000000000000");
        CloudWatchMetricsService service = new CloudWatchMetricsService(new InMemoryStorage<>(), alarmStore, resolver);
        CloudWatchDashboardsService dashboards = new CloudWatchDashboardsService(new InMemoryStorage<>(), resolver);
        jsonHandler = new CloudWatchMetricsJsonHandler(service, dashboards, null, MAPPER);
        queryHandler = new CloudWatchMetricsQueryHandler(service, dashboards, null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void metricMathQueriesRoundTripThroughBothProtocols(boolean queryProtocol) throws Exception {
        ObjectNode request = metricMathRequest();
        put(request, queryProtocol);
        assertMetricMathAlarm(request);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void metricMathQueriesSurvivePersistentStorageReload(boolean queryProtocol, @TempDir Path directory)
            throws Exception {
        Path storageFile = directory.resolve("alarms.json");
        TypeReference<Map<String, MetricAlarm>> type = new TypeReference<>() { };
        initialize(new PersistentStorage<>(storageFile, type));
        ObjectNode request = metricMathRequest();
        put(request, queryProtocol);

        PersistentStorage<String, MetricAlarm> reloaded = new PersistentStorage<>(storageFile, type);
        reloaded.load();
        assertEquals(1, reloaded.keys().size(), "the stored alarm must deserialize after a restart");
        initialize(reloaded);
        assertMetricMathAlarm(request);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void switchingBetweenSingleMetricAndMetricMathReplacesThePreviousDefinition(boolean queryProtocol)
            throws Exception {
        ObjectNode singleMetric = singleMetricRequest();
        put(singleMetric, queryProtocol);
        assertSingleMetricAlarm(300);

        ObjectNode math = metricMathRequest();
        put(math, !queryProtocol);
        assertMetricMathAlarm(math);

        singleMetric.put("Period", 120);
        put(singleMetric, queryProtocol);
        assertSingleMetricAlarm(120);
    }

    @Test
    void existingSingleMetricDefaultPeriodIsPreserved() throws Exception {
        ObjectNode singleMetric = singleMetricRequest();
        singleMetric.remove("Period");
        put(singleMetric, false);
        assertSingleMetricAlarm(60);
    }

    private void put(ObjectNode request, boolean queryProtocol) {
        if (queryProtocol) {
            assertEquals(200, queryHandler.handle("PutMetricAlarm", toQuery(request), REGION).getStatus());
        } else {
            assertEquals(200, jsonHandler.handle("PutMetricAlarm", request, REGION).getStatus());
        }
    }

    private JsonNode describeJson() {
        ObjectNode request = MAPPER.createObjectNode();
        request.putArray("AlarmNames").add(ALARM_NAME);
        JsonNode response = (JsonNode) jsonHandler.handle("DescribeAlarms", request, REGION).getEntity();
        assertEquals(1, response.path("MetricAlarms").size());
        return response.path("MetricAlarms").get(0);
    }

    private Document describeXml() throws Exception {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("AlarmNames.member.1", ALARM_NAME);
        String xml = (String) queryHandler.handle("DescribeAlarms", params, REGION).getEntity();
        return XmlParser.parseDocument(xml);
    }

    private void assertMetricMathAlarm(ObjectNode request) throws Exception {
        JsonNode alarm = describeJson();
        assertEquals(request.path("Metrics"), alarm.path("Metrics"));
        Document document = describeXml();
        XPath xpath = XPathFactory.newInstance().newXPath();
        for (String field : new String[]{"Period", "MetricName", "Namespace", "Statistic", "ExtendedStatistic",
                "Unit", "Dimensions"}) {
            assertFalse(alarm.has(field), "metric-math JSON must not contain " + field);
            assertEquals("0", xpath.evaluate("count(" + XML_ALARM + "/" + field + ")", document),
                    "metric-math XML must not contain " + field);
        }
        assertEquals("3", xpath.evaluate("count(" + XML_ALARM + "/Metrics/member)", document));
        assertEquals("5", xpath.evaluate(XML_ALARM + "/Threshold", document).replace(".0", ""));
        assertEquals("2", xpath.evaluate(XML_ALARM + "/EvaluationPeriods", document));
        assertEquals(5.0, alarm.path("Threshold").asDouble());
        assertEquals(2, alarm.path("EvaluationPeriods").asInt());
        assertXmlMatchesJson(request.path("Metrics"), XML_ALARM + "/Metrics", document, xpath);
    }

    private void assertSingleMetricAlarm(int period) throws Exception {
        JsonNode alarm = describeJson();
        assertEquals(period, alarm.path("Period").asInt());
        assertEquals("AWS/ApiGateway", alarm.path("Namespace").asText());
        assertEquals("5xx", alarm.path("MetricName").asText());
        assertEquals("Sum", alarm.path("Statistic").asText());
        assertFalse(alarm.has("Metrics"));
        Document document = describeXml();
        XPath xpath = XPathFactory.newInstance().newXPath();
        assertEquals(Integer.toString(period), xpath.evaluate(XML_ALARM + "/Period", document));
        assertEquals("0", xpath.evaluate("count(" + XML_ALARM + "/Metrics)", document));
    }

    private static void assertXmlMatchesJson(JsonNode value, String path, Document document, XPath xpath)
            throws Exception {
        if (value.isArray()) {
            assertEquals(Integer.toString(value.size()), xpath.evaluate("count(" + path + "/member)", document));
            for (int index = 0; index < value.size(); index++) {
                assertXmlMatchesJson(value.get(index), path + "/member[" + (index + 1) + "]", document, xpath);
            }
        } else if (value.isObject()) {
            for (Map.Entry<String, JsonNode> field : value.properties()) {
                assertXmlMatchesJson(field.getValue(), path + "/" + field.getKey(), document, xpath);
            }
        } else {
            assertEquals(value.asText(), xpath.evaluate(path, document), path);
        }
    }

    private static MultivaluedMap<String, String> toQuery(JsonNode value) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        flatten(value, "", params);
        return params;
    }

    private static void flatten(JsonNode value, String prefix, MultivaluedMap<String, String> params) {
        if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                flatten(value.get(index), prefix + ".member." + (index + 1), params);
            }
        } else if (value.isObject()) {
            for (Map.Entry<String, JsonNode> field : value.properties()) {
                flatten(field.getValue(), prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey(), params);
            }
        } else {
            params.putSingle(prefix, value.asText());
        }
    }

    private static ObjectNode metricMathRequest() throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {"AlarmName":"metric-math-round-trip","EvaluationPeriods":2,"Threshold":5,
                 "ComparisonOperator":"GreaterThanThreshold","Metrics":[
                  {"Id":"rate","Expression":"100 * errors / requests","ReturnData":true,
                   "Period":300,"Label":"Error <rate> & requests"},
                  {"Id":"errors","ReturnData":false,"AccountId":"123456789012","Label":"Errors",
                   "MetricStat":{"Metric":{"Namespace":"AWS/ApiGateway","MetricName":"5xx",
                    "Dimensions":[{"Name":"ApiName","Value":"api<&>"},{"Name":"Stage","Value":"test"}]},
                    "Period":300,"Stat":"Sum","Unit":"Count"}},
                  {"Id":"requests","ReturnData":false,"MetricStat":{
                   "Metric":{"Namespace":"AWS/ApiGateway","MetricName":"Count"},"Period":300,"Stat":"Sum"}}
                 ]}
                """);
    }

    private static ObjectNode singleMetricRequest() throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {"AlarmName":"metric-math-round-trip","Namespace":"AWS/ApiGateway","MetricName":"5xx",
                 "Statistic":"Sum","Period":300,"EvaluationPeriods":2,"Threshold":5,
                 "ComparisonOperator":"GreaterThanThreshold"}
                """);
    }
}
