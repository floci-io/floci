package io.floci.aws.services.cloudwatch.metrics;

import com.google.protobuf.ByteString;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.ArrayValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.common.v1.KeyValueList;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.resource.v1.Resource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtlpMetricsValidationTest {

    private final OtlpMetricsValidation validation = new OtlpMetricsValidation();

    @Test
    void countsAttributesAcrossResourceScopeAndDatapoint() {
        List<KeyValue> resource = attributes("r", 50);
        List<KeyValue> scope = attributes("s", 50);
        List<KeyValue> point = attributes("p", 50);
        assertFalse(validation.validate(toDynamic(request(resource, scope, point))).hasErrors());
        point.add(attribute("r0", "x"));
        OtlpMetricsValidation.Result result = validation.validate(toDynamic(request(resource, scope, point)));
        assertEquals(1, result.rejectedDataPoints());
        assertEquals("Maximum number of attributes exceeded: 150 "
                + "[ResourceMetrics.1.ScopeMetrics.1.Metrics.1.Datapoint.1].", result.errorMessage());
    }

    @Test
    void countsUtf8MetadataAndMetricScopeNamesAtTheObservedBoundary() {
        List<KeyValue> attributes = metadataAttributes(40_000 - 15);
        KeyValue first = attributes.getFirst();
        attributes.set(0, attribute(first.getKey(), "é".repeat(first.getValue().getStringValue().length() / 2)));
        assertFalse(validation.validate(toDynamic(request(List.of(), List.of(), attributes))).hasErrors());
        KeyValue last = attributes.getLast();
        attributes.set(attributes.size() - 1, attribute(last.getKey(), last.getValue().getStringValue() + "x"));
        OtlpMetricsValidation.Result result = validation.validate(toDynamic(request(List.of(), List.of(), attributes)));
        assertEquals("Maximum size of datapoint metadata exceeded: 40 KB "
                + "[ResourceMetrics.1.ScopeMetrics.1.Metrics.1.Datapoint.1].", result.errorMessage());
    }

    @Test
    void countsTypedAttributeValuesAtTheObservedBoundary() {
        List<KeyValue> attributes = metadataAttributes(39_961);
        attributes.add(KeyValue.newBuilder().setKey("num")
                .setValue(AnyValue.newBuilder().setIntValue(Long.MAX_VALUE)).build());
        attributes.add(KeyValue.newBuilder().setKey("flag")
                .setValue(AnyValue.newBuilder().setBoolValue(true)).build());
        attributes.add(KeyValue.newBuilder().setKey("blob")
                .setValue(AnyValue.newBuilder().setBytesValue(ByteString.copyFrom(new byte[] {1, 2, 3, 4}))).build());
        assertFalse(validation.validate(toDynamic(request(List.of(), List.of(), attributes))).hasErrors());
        KeyValue lastString = attributes.get(39);
        attributes.set(39, attribute(lastString.getKey(), lastString.getValue().getStringValue() + "x"));
        assertTrue(validation.validate(toDynamic(request(List.of(), List.of(), attributes))).errorMessage()
                .startsWith("Maximum size of datapoint metadata exceeded: 40 KB"));
    }

    @Test
    void rejectsOverlongScopeAndDatapointValuesAtTheirOwnPaths() {
        List<KeyValue> longValue = List.of(attribute("key", "x".repeat(1025)));
        OtlpMetricsValidation.Result scope = validation.validate(toDynamic(request(List.of(), longValue, List.of())));
        assertEquals("Attribute string value exceeds maximum length: 1024 "
                + "[ResourceMetrics.1.ScopeMetrics.1] (1 datapoint rejected).", scope.errorMessage());
        OtlpMetricsValidation.Result point = validation.validate(toDynamic(request(List.of(), List.of(), longValue)));
        assertEquals("Attribute string value exceeds maximum length: 1024 "
                + "[ResourceMetrics.1.ScopeMetrics.1.Metrics.1.Datapoint.1].", point.errorMessage());
        assertFalse(validation.validate(toDynamic(request(List.of(), List.of(),
                List.of(attribute("key", "é".repeat(1024)))))).hasErrors());
        assertFalse(validation.validate(toDynamic(request(List.of(), List.of(),
                List.of(attribute("key", "😀".repeat(512)))))).hasErrors());
        assertTrue(validation.validate(toDynamic(request(List.of(), List.of(),
                List.of(attribute("key", "😀".repeat(513)))))).hasErrors());
    }

    @Test
    void retainsValidPointWhenAnotherHasTooManyAttributes() throws InvalidProtocolBufferException {
        ExportMetricsServiceRequest original = request(List.of(), List.of(), List.of());
        ExportMetricsServiceRequest updated = withFirstGauge(original, gauge -> gauge.addDataPoints(
                gauge.getDataPoints(0).toBuilder().addAllAttributes(attributes("a", 151))));
        OtlpMetricsValidation.Result result = validation.validate(toDynamic(updated));
        assertEquals(1, result.rejectedDataPoints());
        ExportMetricsServiceRequest accepted = ExportMetricsServiceRequest.parseFrom(result.requestBytes());
        assertEquals(1, accepted.getResourceMetrics(0).getScopeMetrics(0).getMetrics(0)
                .getGauge().getDataPointsCount());
        assertTrue(result.errorMessage().endsWith("[ResourceMetrics.1.ScopeMetrics.1.Metrics.1.Datapoint.2]."));
    }

    @Test
    void identifiesMissingServiceNameInTimestampError() {
        ExportMetricsServiceRequest original = request(List.of(), List.of(), List.of());
        ExportMetricsServiceRequest updated = withFirstGauge(original,
                gauge -> gauge.setDataPoints(0, gauge.getDataPoints(0).toBuilder().setTimeUnixNano(0)));
        OtlpMetricsValidation.Result result = validation.validate(toDynamic(updated));
        assertEquals("TimeUnixNano 0 is too far in the past for metric 'metric' "
                + "for resource where service.name is not present at "
                + "[ResourceMetrics.1.ScopeMetrics.1.Metrics.1.Datapoint.1].", result.errorMessage());
    }

    @Test
    void reportsSharedAndDatapointErrorsButRejectsEachPointOnce() {
        ExportMetricsServiceRequest original = request(List.of(attribute("resource", "x".repeat(1025))),
                List.of(), List.of());
        ExportMetricsServiceRequest updated = withFirstGauge(original,
                gauge -> gauge.setDataPoints(0, gauge.getDataPoints(0).toBuilder().setTimeUnixNano(0)));
        OtlpMetricsValidation.Result result = validation.validate(toDynamic(updated));
        assertEquals(1, result.rejectedDataPoints());
        assertEquals("Attribute string value exceeds maximum length: 1024 "
                        + "[ResourceMetrics.1] (1 datapoint rejected).\n"
                        + "TimeUnixNano 0 is too far in the past for metric 'metric' "
                        + "for resource where service.name is not present at "
                        + "[ResourceMetrics.1.ScopeMetrics.1.Metrics.1.Datapoint.1].",
                result.errorMessage());
    }

    @Test
    void countsMetricNameLengthInUtf16CodeUnits() {
        ExportMetricsServiceRequest original = request(List.of(), List.of(), List.of());
        ResourceMetrics.Builder resource = original.getResourceMetrics(0).toBuilder();
        ScopeMetrics.Builder scope = resource.getScopeMetrics(0).toBuilder();
        Metric.Builder metric = scope.getMetrics(0).toBuilder().setName("m" + "😀".repeat(127));
        scope.setMetrics(0, metric);
        resource.setScopeMetrics(0, scope);
        assertFalse(validation.validate(toDynamic(original.toBuilder().setResourceMetrics(0, resource).build()))
                .hasErrors());
        metric.setName("m" + "😀".repeat(128));
        scope.setMetrics(0, metric);
        resource.setScopeMetrics(0, scope);
        assertEquals("Metric name exceeds maximum length: 256 "
                        + "[ResourceMetrics.1.ScopeMetrics.1.Metrics.1] (1 datapoint rejected).",
                validation.validate(toDynamic(original.toBuilder().setResourceMetrics(0, resource).build()))
                        .errorMessage());
    }

    @Test
    void ignoresInvalidAttributesOnEmptyEnvelopes() {
        KeyValue longValue = attribute("resource", "x".repeat(1025));
        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder().setResource(Resource.newBuilder().addAttributes(longValue))
                        .addScopeMetrics(ScopeMetrics.newBuilder().setScope(InstrumentationScope.newBuilder()
                                .addAttributes(longValue))))
                .build();
        OtlpMetricsValidation.Result result = validation.validate(toDynamic(request));
        assertFalse(result.hasErrors());
        assertEquals(0, result.rejectedDataPoints());
    }

    @Test
    void checksNestedStringsInResourceScopeAndDatapointAttributes() {
        AnyValue nested = AnyValue.newBuilder().setArrayValue(ArrayValue.newBuilder().addValues(
                AnyValue.newBuilder().setKvlistValue(KeyValueList.newBuilder()
                        .addValues(attribute("inner", "x".repeat(1025)))))).build();
        KeyValue attribute = KeyValue.newBuilder().setKey("outer").setValue(nested).build();
        assertTrue(validation.validate(toDynamic(request(List.of(attribute), List.of(), List.of()))).errorMessage()
                .contains("[ResourceMetrics.1]"));
        assertTrue(validation.validate(toDynamic(request(List.of(), List.of(attribute), List.of()))).errorMessage()
                .contains("[ResourceMetrics.1.ScopeMetrics.1]"));
        assertTrue(validation.validate(toDynamic(request(List.of(), List.of(), List.of(attribute)))).errorMessage()
                .contains("[ResourceMetrics.1.ScopeMetrics.1.Metrics.1.Datapoint.1]"));
    }

    @Test
    void countsNestedKeysAndValuesTowardsMetadataLimit() {
        List<KeyValue> attributes = metadataAttributes(39_985 - 11);
        AnyValue nested = AnyValue.newBuilder().setArrayValue(ArrayValue.newBuilder().addValues(
                AnyValue.newBuilder().setKvlistValue(KeyValueList.newBuilder()
                        .addValues(attribute("inner", "value"))))).build();
        attributes.add(KeyValue.newBuilder().setKey("o").setValue(nested).build());
        assertFalse(validation.validate(toDynamic(request(List.of(), List.of(), attributes))).hasErrors());
        attributes.add(attribute("x", ""));
        assertTrue(validation.validate(toDynamic(request(List.of(), List.of(), attributes))).errorMessage()
                .startsWith("Maximum size of datapoint metadata exceeded: 40 KB"));
    }

    private DynamicMessage toDynamic(ExportMetricsServiceRequest req) {
        try {
            return DynamicMessage.parseFrom(OtlpDescriptors.EXPORT_REQUEST, req.toByteArray());
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError("Could not convert to DynamicMessage", e);
        }
    }

    private ExportMetricsServiceRequest withFirstGauge(ExportMetricsServiceRequest request,
                                                       UnaryOperator<Gauge.Builder> edit) {
        ResourceMetrics.Builder resource = request.getResourceMetrics(0).toBuilder();
        ScopeMetrics.Builder scope = resource.getScopeMetrics(0).toBuilder();
        Metric.Builder metric = scope.getMetrics(0).toBuilder();
        metric.setGauge(edit.apply(metric.getGauge().toBuilder()));
        scope.setMetrics(0, metric);
        resource.setScopeMetrics(0, scope);
        return request.toBuilder().setResourceMetrics(0, resource).build();
    }

    private ExportMetricsServiceRequest request(List<KeyValue> resource, List<KeyValue> scope, List<KeyValue> point) {
        NumberDataPoint datum = NumberDataPoint.newBuilder().setTimeUnixNano(Instant.now().toEpochMilli() * 1_000_000L)
                .setAsDouble(1).addAllAttributes(point).build();
        Metric metric = Metric.newBuilder().setName("metric").setUnit("ms")
                .setGauge(Gauge.newBuilder().addDataPoints(datum)).build();
        ScopeMetrics scopeMetrics = ScopeMetrics.newBuilder()
                .setScope(InstrumentationScope.newBuilder().setName("scope").setVersion("v1").addAllAttributes(scope))
                .addMetrics(metric).build();
        return ExportMetricsServiceRequest.newBuilder().addResourceMetrics(ResourceMetrics.newBuilder()
                .setResource(Resource.newBuilder().addAllAttributes(resource)).addScopeMetrics(scopeMetrics)).build();
    }

    private List<KeyValue> attributes(String prefix, int count) {
        List<KeyValue> attrs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            attrs.add(attribute(prefix + i, "x"));
        }
        return attrs;
    }

    private List<KeyValue> metadataAttributes(int totalBytes) {
        List<KeyValue> attrs = attributes("k", 40);
        int remaining = totalBytes - attrs.stream().mapToInt(attribute -> attribute.getKey().length()).sum();
        for (int i = 0; i < attrs.size(); i++) {
            int size = Math.min(1024, remaining);
            attrs.set(i, attribute(attrs.get(i).getKey(), "x".repeat(size)));
            remaining -= size;
        }
        assertEquals(0, remaining);
        return attrs;
    }

    private KeyValue attribute(String key, String value) {
        return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build();
    }
}
