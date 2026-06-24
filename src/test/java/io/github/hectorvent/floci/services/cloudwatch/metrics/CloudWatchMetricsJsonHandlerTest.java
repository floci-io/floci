package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                new RegionResolver(REGION, "000000000000")
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
}
