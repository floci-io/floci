package io.floci.aws.services.cloudwatch.metrics;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.metrics.v1.Metric;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OtlpMetricsDecoderTest {

    private final OtlpMetricsDecoder decoder = new OtlpMetricsDecoder();

    @Test
    void jsonAndProtobufPreserveMetricData() throws Exception {
        String json = """
                {"resourceMetrics":[{"resource":{"attributes":[{"key":"service.name",
                "value":{"stringValue":"checkout"}}]},"scopeMetrics":[{"scope":{"name":"app"},
                "metrics":[{"name":"requests","sum":{"aggregationTemporality":2,"isMonotonic":true,
                "dataPoints":[{"timeUnixNano":"1700000000123456789","asInt":"42"}]}}]}]}]}
                """;
        DynamicMessage decoded = decoder.decodeJson(json.getBytes(StandardCharsets.UTF_8));
        DynamicMessage roundTrip = decoder.decodeProtobuf(decoded.toByteArray());
        assertEquals(decoded, roundTrip);
        ExportMetricsServiceRequest parsed = ExportMetricsServiceRequest.parseFrom(roundTrip.toByteArray());
        assertEquals("checkout", parsed.getResourceMetrics(0).getResource().getAttributes(0)
                .getValue().getStringValue());
        assertEquals("app", parsed.getResourceMetrics(0).getScopeMetrics(0).getScope().getName());
        Metric metric = parsed.getResourceMetrics(0).getScopeMetrics(0).getMetrics(0);
        assertEquals("requests", metric.getName());
        assertEquals(1700000000123456789L, metric.getSum().getDataPoints(0).getTimeUnixNano());
        assertEquals(42L, metric.getSum().getDataPoints(0).getAsInt());
    }

    @Test
    void decodesHexEncodedExemplarIdentifiers() throws Exception {
        String json = """
                {"resourceMetrics":[{"scopeMetrics":[{"metrics":[{"name":"latency","histogram":{
                "dataPoints":[{"exemplars":[{"traceId":"5B8EFFF798038103D269B633813FC60C",
                "spanId":"0123456789abcdef","asDouble":2}]}]}}]}]}]}
                """;
        DynamicMessage decoded = decoder.decodeJson(json.getBytes(StandardCharsets.UTF_8));
        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.parseFrom(decoded.toByteArray());
        Metric metric = request.getResourceMetrics(0).getScopeMetrics(0).getMetrics(0);
        assertEquals("5b8efff798038103d269b633813fc60c", HexFormat.of().formatHex(
                metric.getHistogram().getDataPoints(0).getExemplars(0).getTraceId().toByteArray()));
        assertEquals("0123456789abcdef", HexFormat.of().formatHex(
                metric.getHistogram().getDataPoints(0).getExemplars(0).getSpanId().toByteArray()));
        assertEquals(decoded, decoder.decodeProtobuf(decoded.toByteArray()));
        String invalid = json.replace("0123456789abcdef", "not-hex");
        assertThrows(InvalidProtocolBufferException.class,
                () -> decoder.decodeJson(invalid.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsMalformedAndEmptyBodies() {
        assertThrows(InvalidProtocolBufferException.class, () -> decoder.decodeJson(new byte[0]));
        assertThrows(InvalidProtocolBufferException.class, () -> decoder.decodeJson("{".getBytes(StandardCharsets.UTF_8)));
        assertThrows(InvalidProtocolBufferException.class, () -> decoder.decodeProtobuf(new byte[0]));
        assertThrows(InvalidProtocolBufferException.class, () -> decoder.decodeProtobuf(new byte[] {(byte) 0xff}));
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsEmptyJsonRequest() throws Exception {
        DynamicMessage result = decoder.decodeJson("{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, ((List<?>) result.getField(OtlpDescriptors.RESOURCE_METRICS_LIST)).size());
    }
}
