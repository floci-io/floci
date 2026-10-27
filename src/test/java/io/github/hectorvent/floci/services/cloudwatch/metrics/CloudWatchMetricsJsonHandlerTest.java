package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.github.hectorvent.floci.services.cloudwatch.metricstreams.CloudWatchMetricStreamsService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handler-level tests for CloudWatchMetricsJsonHandler.
 *
 * The AWS SDK v2 serialises Instant values via DateUtils.formatUnixTimestampInstant(),
 * which produces a plain decimal epoch-second number (e.g. 1750000000.123) written
 * via JsonGenerator.writeNumber(String). Jackson deserialises this as a numeric node,
 * which is what these tests replicate using BigDecimal to avoid double-precision artefacts.
 */
class CloudWatchMetricsJsonHandlerTest {

    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Fixed reference point — avoids wall-clock non-determinism.
    private static final Instant EPOCH_NOW = Instant.parse("2025-06-16T12:00:00Z");
    private static final Instant EPOCH_OLD = EPOCH_NOW.minusSeconds(86400);

    private CloudWatchMetricsJsonHandler handler;
    private CloudWatchMetricsService service;

    @BeforeEach
    void setUp() {
        service = new CloudWatchMetricsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000")
        );
        CloudWatchDashboardsService dashboardsService = new CloudWatchDashboardsService(
                new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000")
        );
        CloudWatchMetricStreamsService metricStreamsService = new CloudWatchMetricStreamsService(
                new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"));
        handler = new CloudWatchMetricsJsonHandler(service, dashboardsService, metricStreamsService, MAPPER);
    }

    /**
     * Mimics DateUtils.formatUnixTimestampInstant: epoch millis as a decimal BigDecimal
     * (epoch seconds with millisecond precision). Using BigDecimal avoids the scientific-
     * notation and precision issues that arise when casting through double.
     */
    private static BigDecimal sdkTimestamp(Instant instant) {
        return new BigDecimal(instant.toEpochMilli()).scaleByPowerOfTen(-3);
    }

    private Response putMetric(String namespace, String metricName,
                                String dimName, String dimValue,
                                double value, Instant timestamp) {
        return putMetric(namespace, metricName, dimName, dimValue, value, timestamp, null);
    }

    private Response putMetric(String namespace, String metricName,
                                String dimName, String dimValue,
                                double value, Instant timestamp, String unit) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Namespace", namespace);
        ObjectNode datum = req.putArray("MetricData").addObject();
        datum.put("MetricName", metricName);
        datum.put("Value", value);
        datum.put("Timestamp", sdkTimestamp(timestamp));
        if (unit != null) {
            datum.put("Unit", unit);
        }
        datum.putArray("Dimensions").addObject()
                .put("Name", dimName).put("Value", dimValue);
        return handler.handle("PutMetricData", req, REGION);
    }

    private ObjectNode getStats(String namespace, String metricName,
                                 String dimName, String dimValue,
                                 Instant startTime, Instant endTime, int period) {
        return getStatsWithUnit(namespace, metricName, dimName, dimValue,
                startTime, endTime, period, null, false);
    }

    private ObjectNode getStats(String namespace, String metricName,
                                 String dimName, String dimValue,
                                 Instant startTime, Instant endTime, int period, String unit) {
        return getStatsWithUnit(namespace, metricName, dimName, dimValue,
                startTime, endTime, period, unit, false);
    }

    private ObjectNode getStatsWithExplicitNullUnit(String namespace, String metricName,
                                                     String dimName, String dimValue,
                                                     Instant startTime, Instant endTime, int period) {
        return getStatsWithUnit(namespace, metricName, dimName, dimValue,
                startTime, endTime, period, null, true);
    }

    private ObjectNode getStatsWithUnit(String namespace, String metricName,
                                         String dimName, String dimValue,
                                         Instant startTime, Instant endTime, int period,
                                         String unit, boolean explicitNull) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Namespace", namespace);
        req.put("MetricName", metricName);
        req.put("Period", period);
        req.put("StartTime", sdkTimestamp(startTime));
        req.put("EndTime", sdkTimestamp(endTime));
        req.putArray("Dimensions").addObject()
                .put("Name", dimName).put("Value", dimValue);
        req.putArray("Statistics").add("Sum");
        if (explicitNull) {
            req.putNull("Unit");
        } else if (unit != null) {
            req.put("Unit", unit);
        }
        Response resp = handler.handle("GetMetricStatistics", req, REGION);
        assertEquals(200, resp.getStatus());
        return (ObjectNode) resp.getEntity();
    }

    @Test
    void putMetricData_decimalEpochTimestamp_storesCorrectTimestamp() {
        assertEquals(200, putMetric("NS", "M", "type", "old", 200.0, EPOCH_OLD).getStatus());

        // Wide window around the old timestamp — must find the datapoint
        ObjectNode wide = getStats("NS", "M", "type", "old",
                EPOCH_OLD.minusSeconds(60), EPOCH_OLD.plusSeconds(60), 3600);
        assertEquals(1, wide.get("Datapoints").size(),
                "metric stored with 24h-ago timestamp must be found when querying around that time");

        // Narrow window around now — must not find the datapoint
        ObjectNode narrow = getStats("NS", "M", "type", "old",
                EPOCH_NOW.minusSeconds(10), EPOCH_NOW.plusSeconds(10), 60);
        assertEquals(0, narrow.get("Datapoints").size(),
                "metric stored with 24h-ago timestamp must not appear in a 20-second window around now");
    }

    // The CloudWatch Agent sends an empty top-level MetricData and nests every datum under
    // EntityMetricData, floci-io/floci#6066.
    @Test
    void putMetricData_entityMetricDataOnly_storesNestedDatumsUnderTheRequestNamespace() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Namespace", "CWAgent");
        req.putArray("MetricData");
        ObjectNode entityMetricData = req.putArray("EntityMetricData").addObject();
        ObjectNode entity = entityMetricData.putObject("Entity");
        entity.putObject("KeyAttributes")
                .put("Type", "AWS::Resource")
                .put("ResourceType", "AWS::EC2::Instance")
                .put("Identifier", "i-0123456789abcdef0");
        entity.putObject("Attributes");
        ObjectNode datum = entityMetricData.putArray("MetricData").addObject();
        datum.put("MetricName", "mem_used_percent");
        datum.put("Value", 6.43);
        datum.put("Unit", "Percent");
        datum.put("StorageResolution", 60);
        datum.put("Timestamp", sdkTimestamp(EPOCH_NOW));
        datum.putArray("Dimensions").addObject().put("Name", "host").put("Value", "h1");
        req.put("StrictEntityValidation", false);

        assertEquals(200, handler.handle("PutMetricData", req, REGION).getStatus());

        ObjectNode stats = getStats("CWAgent", "mem_used_percent", "host", "h1",
                EPOCH_NOW.minusSeconds(60), EPOCH_NOW.plusSeconds(60), 60);
        assertEquals(1, stats.get("Datapoints").size());
        assertEquals(6.43, stats.get("Datapoints").get(0).get("Sum").asDouble());
    }

    @Test
    void putMetricData_topLevelAndEntityMetricData_storesBoth() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Namespace", "Mixed");
        req.putArray("MetricData").addObject()
                .put("MetricName", "TopLevel").put("Value", 1.0);
        ArrayNode entityMetricData = req.putArray("EntityMetricData");
        entityMetricData.addObject().putArray("MetricData").addObject()
                .put("MetricName", "FirstEntity").put("Value", 2.0);
        entityMetricData.addObject().putArray("MetricData").addObject()
                .put("MetricName", "SecondEntity").put("Value", 3.0);

        assertEquals(200, handler.handle("PutMetricData", req, REGION).getStatus());

        ObjectNode listReq = MAPPER.createObjectNode();
        listReq.put("Namespace", "Mixed");
        ObjectNode listed = (ObjectNode) handler.handle("ListMetrics", listReq, REGION).getEntity();
        List<String> names = new ArrayList<>();
        listed.get("Metrics").forEach(m -> names.add(m.get("MetricName").asText()));
        assertEquals(List.of("FirstEntity", "SecondEntity", "TopLevel"), names.stream().sorted().toList());
    }

    @Test
    void putMetricData_entityDatumWithoutMetricName_rejectsTheWholeRequest() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Namespace", "Invalid");
        req.putArray("MetricData").addObject()
                .put("MetricName", "TopLevel").put("Value", 1.0);
        ArrayNode nested = req.putArray("EntityMetricData").addObject().putArray("MetricData");
        nested.addObject().put("MetricName", "Valid").put("Value", 2.0);
        nested.addObject().put("Value", 3.0);

        AwsException e = assertThrows(AwsException.class,
                () -> handler.handle("PutMetricData", req, REGION));
        assertEquals("MissingParameter", e.getErrorCode());
        assertEquals("MissingRequiredParameterException", e.jsonType());
        assertEquals(400, e.getHttpStatus());
        assertEquals("The parameter EntityMetricData.member.1.MetricData.member.2.MetricName is required.",
                e.getMessage());
        assertEquals(0, listMetricNames("Invalid").size());
    }

    @Test
    void putMetricData_topLevelDatumWithEmptyMetricName_rejectsTheWholeRequest() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Namespace", "InvalidTop");
        ArrayNode data = req.putArray("MetricData");
        data.addObject().put("MetricName", "Valid").put("Value", 1.0);
        data.addObject().put("MetricName", "").put("Value", 2.0);

        AwsException e = assertThrows(AwsException.class,
                () -> handler.handle("PutMetricData", req, REGION));
        assertEquals("MissingParameter", e.getErrorCode());
        assertEquals("The parameter MetricData.member.2.MetricName is required.", e.getMessage());
        assertEquals(0, listMetricNames("InvalidTop").size());
    }

    // AWS constrains MetricName to 1 to 255 characters with no pattern, so whitespace is a name.
    @Test
    void putMetricData_whitespaceMetricName_isStored() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Namespace", "WhitespaceName");
        req.putArray("MetricData").addObject().put("MetricName", " ").put("Value", 1.0);

        assertEquals(200, handler.handle("PutMetricData", req, REGION).getStatus());
        assertEquals(List.of(" "), listMetricNames("WhitespaceName"));
    }

    private List<String> listMetricNames(String namespace) {
        ObjectNode listReq = MAPPER.createObjectNode();
        listReq.put("Namespace", namespace);
        ObjectNode listed = (ObjectNode) handler.handle("ListMetrics", listReq, REGION).getEntity();
        List<String> names = new ArrayList<>();
        listed.get("Metrics").forEach(m -> names.add(m.get("MetricName").asText()));
        return names;
    }

    @Test
    void setAlarmState_updatesFieldsCorrectly() {
        putMetric("NS", "M", "type", "current", 100.0, EPOCH_NOW);

        ObjectNode putAlarmReq = MAPPER.createObjectNode();
        putAlarmReq.put("AlarmName", "TestAlarm");
        putAlarmReq.put("MetricName", "M");
        putAlarmReq.put("Namespace", "NS");
        putAlarmReq.putArray("AlarmActions").add("alarm-action");
        putAlarmReq.putArray("OKActions").add("ok-action");
        putAlarmReq.putArray("InsufficientDataActions").add("insufficient-action");
        ArrayNode dimensions = putAlarmReq.putArray("Dimensions");
        dimensions.addObject().put("Name", "period").put("Value", "60");
        dimensions.addObject().put("Name", "count").put("Value", "2");
        Response putAlarmResp = handler.handle("PutMetricAlarm", putAlarmReq, REGION);
        assertEquals(200, putAlarmResp.getStatus());

        ObjectNode alarmReq = MAPPER.createObjectNode();
        alarmReq.put("AlarmName", "TestAlarm");
        alarmReq.put("StateValue", "ALARM");
        alarmReq.put("StateReason", "Test reason");
        alarmReq.put("StateReasonData", "{\"k\":\"v\"}");
        Response resp = handler.handle("SetAlarmState", alarmReq, REGION);
        assertEquals(200, resp.getStatus());

        // Verify that the alarm state is reflected in the metrics service
        ObjectNode getAlarmReq = MAPPER.createObjectNode();
        getAlarmReq.putArray("AlarmNames").add("TestAlarm");
        Response getResp = handler.handle("DescribeAlarms", getAlarmReq, REGION);
        assertEquals(200, getResp.getStatus());
        ObjectNode alarmData = (ObjectNode) ((ObjectNode) getResp.getEntity()).get("MetricAlarms").get(0);
        assertEquals("ALARM", alarmData.get("StateValue").asText());
        assertEquals("Test reason", alarmData.get("StateReason").asText());
        assertEquals("{\"k\":\"v\"}", alarmData.get("StateReasonData").asText());
        assertTrue(alarmData.path("AlarmActions").isArray());
        assertEquals("alarm-action", alarmData.path("AlarmActions").get(0).asText());
        assertTrue(alarmData.path("OKActions").isArray());
        assertEquals("ok-action", alarmData.path("OKActions").get(0).asText());
        assertTrue(alarmData.path("InsufficientDataActions").isArray());
        assertEquals("insufficient-action", alarmData.path("InsufficientDataActions").get(0).asText());
        assertTrue(alarmData.path("StateUpdatedTimestamp").asLong() > 0);
        assertTrue(alarmData.path("Dimensions").isArray());
        JsonNode dimensionPeriod = alarmData.path("Dimensions").get(0);
        assertEquals("period", dimensionPeriod.get("Name").asText());
        assertEquals("60", dimensionPeriod.get("Value").asText());
        JsonNode dimensionCount = alarmData.path("Dimensions").get(1);
        assertEquals("count", dimensionCount.get("Name").asText());
        assertEquals("2", dimensionCount.get("Value").asText());
    }

    private JsonNode describeFirstAlarm(ObjectNode putAlarmReq) {
        assertEquals(200, handler.handle("PutMetricAlarm", putAlarmReq, REGION).getStatus());
        ObjectNode describeReq = MAPPER.createObjectNode();
        describeReq.putArray("AlarmNames").add(putAlarmReq.get("AlarmName").asText());
        Response describeResp = handler.handle("DescribeAlarms", describeReq, REGION);
        assertEquals(200, describeResp.getStatus());
        return ((ObjectNode) describeResp.getEntity()).get("MetricAlarms").get(0);
    }

    private static ObjectNode alarmRequest(String name) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("AlarmName", name);
        req.put("MetricName", "M");
        req.put("Namespace", "NS");
        req.put("Period", 300);
        req.put("EvaluationPeriods", 3);
        req.put("Threshold", 1.0);
        req.put("ComparisonOperator", "GreaterThanThreshold");
        return req;
    }

    // The Query/XML handler has always returned DatapointsToAlarm, TreatMissingData and Unit.
    // The JSON/CBOR builder dropped all three, so a botocore client (which speaks CBOR to
    // CloudWatch by default) saw them missing from every DescribeAlarms response.
    @Test
    void describeAlarms_returnsTheFieldsTheQueryProtocolAlreadyReturns() {
        ObjectNode req = alarmRequest("FullAlarm");
        req.put("DatapointsToAlarm", 2);
        req.put("TreatMissingData", "breaching");
        req.put("Unit", "Count");

        JsonNode alarm = describeFirstAlarm(req);

        assertEquals(2, alarm.get("DatapointsToAlarm").asInt());
        assertEquals("breaching", alarm.get("TreatMissingData").asText());
        assertEquals("Count", alarm.get("Unit").asText());
    }

    @Test
    void describeAlarms_reportsStateTransitionedTimestampForBothAlarmKinds() {
        handler.handle("PutMetricAlarm", alarmRequest("TransitionedMetric"), REGION);
        ObjectNode composite = MAPPER.createObjectNode();
        composite.put("AlarmName", "TransitionedComposite");
        composite.put("AlarmRule", "ALARM(TransitionedMetric)");
        handler.handle("PutCompositeAlarm", composite, REGION);
        ObjectNode describe = MAPPER.createObjectNode();
        describe.putArray("AlarmTypes").add("MetricAlarm").add("CompositeAlarm");

        ObjectNode response = (ObjectNode) handler.handle("DescribeAlarms", describe, REGION).getEntity();

        for (String kind : List.of("MetricAlarms", "CompositeAlarms")) {
            JsonNode alarm = response.get(kind).get(0);
            assertTrue(alarm.has("StateTransitionedTimestamp"), kind + ": " + alarm);
            assertEquals(alarm.get("StateUpdatedTimestamp").asLong(), alarm.get("StateTransitionedTimestamp").asLong());
        }

        service.describeAlarms(List.of("TransitionedMetric"), null, REGION).getFirst().setStateTransitionedTimestamp(100);
        service.describeCompositeAlarms(List.of("TransitionedComposite"), null, REGION).getFirst()
                .setStateTransitionedTimestamp(100);
        response = (ObjectNode) handler.handle("DescribeAlarms", describe, REGION).getEntity();
        for (String kind : List.of("MetricAlarms", "CompositeAlarms")) {
            assertEquals(100, response.get(kind).get(0).get("StateTransitionedTimestamp").asLong(), kind);
        }
    }

    // AWS sends every alarm collection even when it is empty. The CLI's text output writes a
    // None line for each paginator result key missing from the page (floci-io/floci#6362).
    @Test
    void describeAlarms_returnsEmptyCompositeAndLogAlarmCollections() {
        handler.handle("PutMetricAlarm", alarmRequest("MetricOnly"), REGION);

        ObjectNode response = (ObjectNode) handler.handle("DescribeAlarms", MAPPER.createObjectNode(), REGION)
                .getEntity();

        assertEquals(1, response.get("MetricAlarms").size());
        assertTrue(response.get("CompositeAlarms").isArray());
        assertTrue(response.get("CompositeAlarms").isEmpty());
        assertTrue(response.get("LogAlarms").isArray());
        assertTrue(response.get("LogAlarms").isEmpty());
    }

    // An alarm the caller never gave an M for reports no DatapointsToAlarm at all. AWS omits the
    // member in that case, and echoing EvaluationPeriods instead reads as a change to any client
    // that re-plans against its own configuration, which is what floci-io/floci#3660 hit.
    @Test
    void describeAlarms_omittedDatapointsToAlarm_reportsNoDatapointsToAlarm() {
        JsonNode alarm = describeFirstAlarm(alarmRequest("DefaultedAlarm"));

        assertEquals(3, alarm.get("EvaluationPeriods").asInt());
        assertFalse(alarm.has("DatapointsToAlarm"),
                "an alarm with no M out of N must not report one");
    }

    @Test
    void getMetricStatistics_decimalEpochStartEndTime_filtersOutOfRangeDatapoints() {
        putMetric("NS", "M", "type", "current", 100.0, EPOCH_NOW);
        putMetric("NS", "M", "type", "old", 200.0, EPOCH_OLD);

        ObjectNode currentResult = getStats("NS", "M", "type", "current",
                EPOCH_NOW.minusSeconds(10), EPOCH_NOW.plusSeconds(10), 60);
        assertEquals(1, currentResult.get("Datapoints").size(),
                "current metric must be returned for a window around now");

        ObjectNode oldResult = getStats("NS", "M", "type", "old",
                EPOCH_NOW.minusSeconds(10), EPOCH_NOW.plusSeconds(10), 60);
        assertEquals(0, oldResult.get("Datapoints").size(),
                "metric from 24h ago must not be returned for a 20-second window around now");
    }

    @Test
    void getMetricStatistics_jsonHonorsUnitFilter() {
        putMetric("NS", "M", "type", "current", 10.0, EPOCH_NOW, "Count");
        putMetric("NS", "M", "type", "current", 20.0, EPOCH_NOW, "Bytes");

        ObjectNode count = getStats("NS", "M", "type", "current",
                EPOCH_NOW.minusSeconds(10), EPOCH_NOW.plusSeconds(10), 60, "Count");
        assertEquals(1, count.get("Datapoints").size());
        assertEquals(10.0, count.get("Datapoints").get(0).get("Sum").asDouble());
        assertEquals("Count", count.get("Datapoints").get(0).get("Unit").asText());

        ObjectNode bytes = getStats("NS", "M", "type", "current",
                EPOCH_NOW.minusSeconds(10), EPOCH_NOW.plusSeconds(10), 60, "Bytes");
        assertEquals(1, bytes.get("Datapoints").size());
        assertEquals(20.0, bytes.get("Datapoints").get(0).get("Sum").asDouble());
        assertEquals("Bytes", bytes.get("Datapoints").get(0).get("Unit").asText());

        ObjectNode noMatch = getStats("NS", "M", "type", "current",
                EPOCH_NOW.minusSeconds(10), EPOCH_NOW.plusSeconds(10), 60, "Percent");
        assertEquals(0, noMatch.get("Datapoints").size(),
                "a unit no datum carries must filter every datapoint out");
    }

    @Test
    void getMetricStatistics_jsonTreatsOmittedAndNullUnitAsNoFilter() {
        putMetric("NS", "M", "type", "current", 10.0, EPOCH_NOW, "Count");
        putMetric("NS", "M", "type", "current", 20.0, EPOCH_NOW, "Bytes");

        ObjectNode omitted = getStats("NS", "M", "type", "current",
                EPOCH_NOW.minusSeconds(10), EPOCH_NOW.plusSeconds(10), 60);
        ObjectNode explicitNull = getStatsWithExplicitNullUnit("NS", "M", "type", "current",
                EPOCH_NOW.minusSeconds(10), EPOCH_NOW.plusSeconds(10), 60);

        assertTrue(omitted.get("Datapoints").size() > 0,
                "an omitted Unit must not filter every datapoint out");
        assertEquals(omitted.get("Datapoints"), explicitNull.get("Datapoints"),
                "an explicit JSON null Unit must mean no filter, exactly like an omitted Unit");
    }

    @Test
    void getMetricData_jsonTreatsExplicitNullUnitAsNoFilter() {
        putMetric("NS", "M", "type", "current", 10.0, EPOCH_NOW, "Count");
        putMetric("NS", "M", "type", "current", 20.0, EPOCH_NOW, "Bytes");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StartTime", sdkTimestamp(EPOCH_NOW.minusSeconds(10)));
        req.put("EndTime", sdkTimestamp(EPOCH_NOW.plusSeconds(10)));
        ObjectNode query = req.putArray("MetricDataQueries").addObject();
        query.put("Id", "m0");
        ObjectNode metricStat = query.putObject("MetricStat");
        ObjectNode metric = metricStat.putObject("Metric");
        metric.put("Namespace", "NS");
        metric.put("MetricName", "M");
        metric.putArray("Dimensions").addObject()
                .put("Name", "type").put("Value", "current");
        metricStat.put("Period", 60);
        metricStat.put("Stat", "Sum");
        metricStat.putNull("Unit");

        Response resp = handler.handle("GetMetricData", req, REGION);
        assertEquals(200, resp.getStatus());
        ObjectNode body = (ObjectNode) resp.getEntity();
        assertTrue(body.get("MetricDataResults").get(0).get("Values").size() > 0,
                "an explicit JSON null Unit must not filter every datapoint out");
    }

    @Test
    void describeAlarmHistoryReturnsAwsShapedItemsAndPages() {
        handler.handle("PutMetricAlarm", MAPPER.createObjectNode().put("AlarmName", "json-history"), REGION);
        for (String state : List.of("OK", "ALARM", "OK")) {
            handler.handle("SetAlarmState", MAPPER.createObjectNode().put("AlarmName", "json-history")
                    .put("StateValue", state).put("StateReason", "manual"), REGION);
        }
        ObjectNode request = MAPPER.createObjectNode().put("AlarmName", "json-history")
                .put("HistoryItemType", "StateUpdate").put("MaxRecords", 2);
        request.putArray("AlarmTypes").add("MetricAlarm");

        Response response = handler.handle("DescribeAlarmHistory", request, REGION);
        assertEquals(200, response.getStatus());
        ObjectNode body = (ObjectNode) response.getEntity();
        JsonNode items = body.get("AlarmHistoryItems");
        assertEquals(2, items.size());
        JsonNode newest = items.get(0);
        List<String> fields = new ArrayList<>();
        newest.fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("AlarmName", "AlarmType", "HistoryData", "HistoryItemType", "HistorySummary", "Timestamp"),
                fields);
        assertEquals("json-history", newest.get("AlarmName").asText());
        assertEquals("MetricAlarm", newest.get("AlarmType").asText());
        assertEquals("StateUpdate", newest.get("HistoryItemType").asText());
        assertEquals("Alarm updated from ALARM to OK", newest.get("HistorySummary").asText());
        assertTrue(newest.get("HistoryData").asText().startsWith("{\"version\":\"1.0\",\"oldState\""));
        assertTrue(newest.get("Timestamp").isDouble());
        assertTrue(Math.abs(newest.get("Timestamp").asDouble() - Instant.now().getEpochSecond()) < 60);
        assertTrue(body.get("NextToken").isTextual());

        request.put("NextToken", body.get("NextToken").asText());
        ObjectNode last = (ObjectNode) handler.handle("DescribeAlarmHistory", request, REGION).getEntity();
        assertEquals("Alarm updated from INSUFFICIENT_DATA to OK",
                last.get("AlarmHistoryItems").get(0).get("HistorySummary").asText());
        assertFalse(last.has("NextToken"));

        ObjectNode future = MAPPER.createObjectNode().put("AlarmName", "json-history")
                .put("StartDate", sdkTimestamp(Instant.now().plusSeconds(3600)));
        assertEquals(0, ((ObjectNode) handler.handle("DescribeAlarmHistory", future, REGION).getEntity())
                .get("AlarmHistoryItems").size());

        ObjectNode distant = MAPPER.createObjectNode().put("AlarmName", "json-history")
                .put("HistoryItemType", "StateUpdate").put("EndDate", 253402300799L);
        assertEquals(3, ((ObjectNode) handler.handle("DescribeAlarmHistory", distant, REGION).getEntity())
                .get("AlarmHistoryItems").size(), "9999-12-31 is after every item");
    }

    @Test
    void describeAlarmHistoryRejectsAStartDateAfterTheEndDate() {
        ObjectNode request = MAPPER.createObjectNode()
                .put("StartDate", sdkTimestamp(Instant.parse("2026-10-26T16:23:10Z")))
                .put("EndDate", sdkTimestamp(Instant.parse("2026-10-26T16:22:10Z")));
        AwsException e = assertThrows(AwsException.class,
                () -> handler.handle("DescribeAlarmHistory", request, REGION));
        assertEquals("InvalidParameterValueException", e.jsonType());
        assertEquals("Start date Mon Oct 26 16:23:10 UTC 2026 must be before end date Mon Oct 26 16:22:10 UTC 2026",
                e.getMessage());
    }
}
