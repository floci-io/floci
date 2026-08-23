package io.github.hectorvent.floci.services.cloudwatch.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    @BeforeEach
    void setUp() {
        CloudWatchMetricsService service = new CloudWatchMetricsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"),
                MAPPER
        );
        handler = new CloudWatchMetricsJsonHandler(service, MAPPER);
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
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Namespace", namespace);
        var datum = req.putArray("MetricData").addObject();
        datum.put("MetricName", metricName);
        datum.put("Value", value);
        datum.put("Timestamp", sdkTimestamp(timestamp));
        datum.putArray("Dimensions").addObject()
                .put("Name", dimName).put("Value", dimValue);
        return handler.handle("PutMetricData", req, REGION);
    }

    private ObjectNode getStats(String namespace, String metricName,
                                 String dimName, String dimValue,
                                 Instant startTime, Instant endTime, int period) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Namespace", namespace);
        req.put("MetricName", metricName);
        req.put("Period", period);
        req.put("StartTime", sdkTimestamp(startTime));
        req.put("EndTime", sdkTimestamp(endTime));
        req.putArray("Dimensions").addObject()
                .put("Name", dimName).put("Value", dimValue);
        req.putArray("Statistics").add("Sum");
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

    private ObjectNode putAlarmReq(String alarmName, int evaluationPeriods, Integer datapointsToAlarm) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("AlarmName", alarmName);
        req.put("MetricName", "M");
        req.put("Namespace", "NS");
        req.put("ComparisonOperator", "GreaterThanThreshold");
        req.put("Period", 60);
        req.put("Statistic", "Sum");
        req.put("Threshold", 100.0);
        req.put("EvaluationPeriods", evaluationPeriods);
        if (datapointsToAlarm != null) {
            req.put("DatapointsToAlarm", datapointsToAlarm);
        }
        return req;
    }

    private ObjectNode describeAlarm(String alarmName) {
        ObjectNode req = MAPPER.createObjectNode();
        req.putArray("AlarmNames").add(alarmName);
        Response resp = handler.handle("DescribeAlarms", req, REGION);
        assertEquals(200, resp.getStatus());
        return (ObjectNode) ((ObjectNode) resp.getEntity()).get("MetricAlarms").get(0);
    }

    /**
     * lex00/floci#93, CORRECTED: creating an alarm with no explicit DatapointsToAlarm must
     * leave the field entirely absent from DescribeAlarms, not default it to
     * EvaluationPeriods.
     *
     * #93 (and the fix that grew out of it, #94) assumed the opposite - that real AWS
     * echoes DatapointsToAlarm = EvaluationPeriods on a fresh alarm - and closed the issue
     * as "the actual cause is client-side, in hashicorp/terraform-provider-aws itself"
     * because a non-Computed Optional schema attribute's planned value always comes from
     * config, so no emulator response could ever suppress the diff. That diagnosis of the
     * SDKv2 mechanics is correct, but the premise under it was never checked against real
     * AWS - it was inferred. Checked directly today (no Terraform, no floci - a bare
     * `aws cloudwatch put-metric-alarm` / `describe-alarms` round trip against real AWS,
     * us-east-1, an alarm with EvaluationPeriods=1 and DatapointsToAlarm never set):
     * DescribeAlarms' response carries no DatapointsToAlarm field at all. So the premise
     * was backwards, and so was the conclusion: the field being genuinely absent from both
     * the refreshed prior AND the config-derived plan is exactly what makes the two agree
     * and the diff vanish - which is a floci-side fix after all, unlike #93's amended
     * closing note.
     *
     * Surfaced again, independently, by live/e2e/corpus-autoscaling-complete's
     * module.auto_rollback and module.step_scaling_alarm alarms (neither sets
     * datapoints_to_alarm), whose stateless replans proposed
     * `- datapoints_to_alarm = 1 -> null` on every run.
     */
    @Test
    void describeAlarms_noExplicitDatapointsToAlarm_omitsTheFieldEntirely() {
        Response putResp = handler.handle("PutMetricAlarm", putAlarmReq("NoDatapointsAlarm", 3, null), REGION);
        assertEquals(200, putResp.getStatus());

        ObjectNode alarm = describeAlarm("NoDatapointsAlarm");
        assertTrue(!alarm.has("DatapointsToAlarm"),
                "DescribeAlarms must omit DatapointsToAlarm when it was never explicitly set - real AWS does, confirmed directly");
        assertEquals(3, alarm.get("EvaluationPeriods").asInt());
    }

    @Test
    void describeAlarms_explicitDatapointsToAlarm_roundTrips() {
        Response putResp = handler.handle("PutMetricAlarm", putAlarmReq("ExplicitDatapointsAlarm", 5, 2), REGION);
        assertEquals(200, putResp.getStatus());

        ObjectNode alarm = describeAlarm("ExplicitDatapointsAlarm");
        assertEquals(2, alarm.get("DatapointsToAlarm").asInt());
        assertEquals(5, alarm.get("EvaluationPeriods").asInt());
    }

    /**
     * TreatMissingData was parsed and stored correctly by handlePutMetricAlarm, but
     * handleDescribeAlarms' JSON response builder never wrote it into the response node
     * at all - explicitly set or not. Surfaced by
     * live/e2e/corpus-autoscaling-complete's module.auto_rollback alarm, which sets
     * treat_missing_data = "notBreaching" explicitly: every stateless replan proposed
     * `treat_missing_data = "missing" -> "notBreaching"` forever, because the read side
     * could never see what was actually configured.
     */
    @Test
    void describeAlarms_explicitTreatMissingData_roundTrips() {
        ObjectNode req = putAlarmReq("TreatMissingDataAlarm", 1, null);
        req.put("TreatMissingData", "notBreaching");
        Response putResp = handler.handle("PutMetricAlarm", req, REGION);
        assertEquals(200, putResp.getStatus());

        ObjectNode alarm = describeAlarm("TreatMissingDataAlarm");
        assertEquals("notBreaching", alarm.get("TreatMissingData").asText());
    }

    @Test
    void describeAlarms_noExplicitTreatMissingData_omitsTheField() {
        Response putResp = handler.handle("PutMetricAlarm", putAlarmReq("NoTreatMissingDataAlarm", 1, null), REGION);
        assertEquals(200, putResp.getStatus());

        ObjectNode alarm = describeAlarm("NoTreatMissingDataAlarm");
        assertTrue(!alarm.has("TreatMissingData"),
                "DescribeAlarms must omit TreatMissingData when it was never explicitly set, the same way DatapointsToAlarm must - "
                        + "the AWS provider supplies its own client-side default of \"missing\" in that case");
    }
}
