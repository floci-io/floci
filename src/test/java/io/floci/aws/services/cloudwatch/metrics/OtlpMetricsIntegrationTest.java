package io.floci.aws.services.cloudwatch.metrics;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.Status;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogram;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Histogram;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.Sum;
import io.opentelemetry.proto.metrics.v1.Summary;
import io.opentelemetry.proto.metrics.v1.SummaryDataPoint;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class OtlpMetricsIntegrationTest {

    @Inject
    OtlpMetricsService service;

    @Test
    void ingestsJsonAndCompressedProtobuf() throws Exception {
        String name = "requests_" + UUID.randomUUID().toString().replace("-", "");
        long timestamp = Instant.now().toEpochMilli() * 1_000_000L;
        String json = """
                {"resourceMetrics":[{"resource":{"attributes":[{"key":"service.name",
                "value":{"stringValue":"checkout"}}]},"scopeMetrics":[{"scope":{"name":"app"},
                "metrics":[{"name":"%s","gauge":{"dataPoints":[{"timeUnixNano":"%d",
                "asDouble":1.5}]}}]}]}]}
                """.formatted(name, timestamp);
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        byte[] requestBytes = new OtlpMetricsDecoder().decodeJson(payload).toByteArray();
        given().contentType("application/json").body(payload).post("/v1/metrics").then().statusCode(200);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(requestBytes);
        }
        given().contentType("application/x-protobuf").header("Content-Encoding", "gzip")
                .body(compressed.toByteArray()).post("/v1/metrics").then().statusCode(200);
        long count = service.requests("us-east-1").stream().filter(b -> Arrays.equals(b, requestBytes)).count();
        assertTrue(count >= 2);
    }

    @Test
    void rejectsInvalidBodiesAndContentTypes() throws Exception {
        given().contentType("application/json").body("{").post("/v1/metrics").then().statusCode(400)
                .body("code", equalTo(3)).body("message", equalTo("Bad request."));
        byte[] errorBody = given().contentType("application/x-protobuf").body(new byte[0])
                .post("/v1/metrics").then().statusCode(400).extract().asByteArray();
        assertEquals(3, Status.parseFrom(errorBody).getCode());
        given().contentType("text/plain").body("{}").post("/v1/metrics").then().statusCode(415);
        given().contentType("application/json").header("Content-Encoding", "gzip").body("not gzip")
                .post("/v1/metrics").then().statusCode(400);
    }

    @Test
    void rejectsRequestsWithTooManyDataPoints() throws Exception {
        Gauge.Builder gauge = Gauge.newBuilder();
        for (int i = 0; i < 1001; i++) {
            gauge.addDataPoints(NumberDataPoint.newBuilder().setAsInt(i));
        }
        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder().addScopeMetrics(ScopeMetrics.newBuilder()
                        .addMetrics(Metric.newBuilder().setName("too_many").setGauge(gauge)))).build();
        byte[] errorBody = given().contentType("application/x-protobuf").body(request.toByteArray())
                .post("/v1/metrics").then().statusCode(400).extract().asByteArray();
        Status status = Status.parseFrom(errorBody);
        assertEquals(3, status.getCode());
        assertEquals("Maximum number of datapoints per request exceeded: 1000.", status.getMessage());
    }

    @Test
    void rejectsAnOldPointAndStoresTheValidPoint() {
        String name = "partial_" + UUID.randomUUID().toString().replace("-", "");
        long now = Instant.now().toEpochMilli() * 1_000_000L;
        String json = """
                {"resourceMetrics":[{"resource":{"attributes":[{"key":"service.name",
                "value":{"stringValue":"checkout"}}]},"scopeMetrics":[{"scope":{"name":"app"},
                "metrics":[{"name":"%s","gauge":{"dataPoints":[{"timeUnixNano":"%d","asDouble":1},
                {"timeUnixNano":"%d","asDouble":2}]}}]}]}]}
                """.formatted(name, now, now - 30L * 24 * 60 * 60 * 1_000_000_000);
        long old = now - 30L * 24 * 60 * 60 * 1_000_000_000;
        given().contentType("application/json").body(json).post("/v1/metrics").then().statusCode(200)
                .body("partialSuccess.rejectedDataPoints", equalTo("1"))
                .body("partialSuccess.errorMessage", equalTo("TimeUnixNano " + old
                        + " is too far in the past for metric '" + name
                        + "' for resource where service.name='checkout' at "
                        + "[ResourceMetrics.1.ScopeMetrics.1.Metrics.1.Datapoint.2]."));
        assertTrue(metricsStream(service.requests("us-east-1"))
                .filter(metric -> name.equals(metric.getName()))
                .anyMatch(metric -> metric.getGauge().getDataPointsCount() == 1));
    }

    @Test
    void rejectsAnOverlongResourceAttribute() {
        String json = """
                {"resourceMetrics":[{"resource":{"attributes":[{"key":"service.name",
                "value":{"stringValue":"%s"}}]},"scopeMetrics":[{"metrics":[{"name":"requests",
                "gauge":{"dataPoints":[{"timeUnixNano":"%d","asDouble":1}]}}]}]}]}
                """.formatted("x".repeat(1025), Instant.now().toEpochMilli() * 1_000_000L);
        given().contentType("application/json").body(json).post("/v1/metrics").then().statusCode(400)
                .body("code", equalTo(3))
                .body("message", equalTo("Attribute string value exceeds maximum length: 1024 "
                        + "[ResourceMetrics.1] (1 datapoint rejected)."));
    }

    @Test
    void reportsPartialSuccessForADataPointWithTooManyAttributes() throws Exception {
        String name = "labels_" + UUID.randomUUID().toString().replace("-", "");
        NumberDataPoint.Builder valid = NumberDataPoint.newBuilder()
                .setTimeUnixNano(Instant.now().toEpochMilli() * 1_000_000L).setAsDouble(1);
        NumberDataPoint.Builder invalid = valid.clone();
        for (int i = 0; i < 151; i++) {
            invalid.addAttributes(KeyValue.newBuilder().setKey("attr" + i)
                    .setValue(AnyValue.newBuilder().setStringValue("v")));
        }
        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder().addScopeMetrics(ScopeMetrics.newBuilder()
                        .addMetrics(Metric.newBuilder().setName(name)
                                .setGauge(Gauge.newBuilder().addDataPoints(valid).addDataPoints(invalid)))))
                .build();
        byte[] response = given().contentType("application/x-protobuf").body(request.toByteArray())
                .post("/v1/metrics").then().statusCode(200).extract().asByteArray();
        ExportMetricsServiceResponse decoded = ExportMetricsServiceResponse.parseFrom(response);
        assertEquals(1, decoded.getPartialSuccess().getRejectedDataPoints());
        assertEquals("Maximum number of attributes exceeded: 150 "
                        + "[ResourceMetrics.1.ScopeMetrics.1.Metrics.1.Datapoint.2].",
                decoded.getPartialSuccess().getErrorMessage());
        assertTrue(metricsStream(service.requests("us-east-1"))
                .anyMatch(metric -> name.equals(metric.getName()) && metric.getGauge().getDataPointsCount() == 1));
    }

    @Test
    void reportsEmptyMetricWithoutRejectingTheValidPoint() {
        String name = "nonempty_" + UUID.randomUUID().toString().replace("-", "");
        String body = """
                {"resourceMetrics":[{"scopeMetrics":[{"metrics":[{"name":"empty","gauge":{}},
                {"name":"%s","gauge":{"dataPoints":[{"timeUnixNano":"%d","asDouble":1}]}}]}]}]}
                """.formatted(name, Instant.now().toEpochMilli() * 1_000_000L);
        given().contentType("application/json").body(body).post("/v1/metrics").then().statusCode(200)
                .body("partialSuccess.rejectedDataPoints", nullValue())
                .body("partialSuccess.errorMessage",
                        equalTo("Invalid OTel metric type [ResourceMetrics.1.ScopeMetrics.1.Metrics.1]."));
        assertTrue(metricsStream(service.requests("us-east-1"))
                .anyMatch(metric -> name.equals(metric.getName())));
    }

    @Test
    void acceptsIdentityEncodingAndRejectsOtherEncodings() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        given().contentType("application/json").header("Content-Encoding", "identity")
                .body(body).post("/v1/metrics").then().statusCode(200);
        given().contentType("application/json").header("Content-Encoding", "br")
                .body(body).post("/v1/metrics").then().statusCode(415);
        given().contentType("application/json").header("Content-Encoding", "deflate")
                .body(body).post("/v1/metrics").then().statusCode(415);
    }

    @Test
    void ingestsAllMetricTypes() {
        long timestamp = Instant.now().toEpochMilli() * 1_000_000L;
        String sumName = "sum_" + UUID.randomUUID().toString().replace("-", "");
        String histName = "hist_" + UUID.randomUUID().toString().replace("-", "");
        String summaryName = "summary_" + UUID.randomUUID().toString().replace("-", "");
        String expHistName = "exphist_" + UUID.randomUUID().toString().replace("-", "");
        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder().addScopeMetrics(ScopeMetrics.newBuilder()
                        .addMetrics(Metric.newBuilder().setName(sumName).setSum(Sum.newBuilder()
                                .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE)
                                .setIsMonotonic(true)
                                .addDataPoints(NumberDataPoint.newBuilder().setTimeUnixNano(timestamp).setAsInt(100))))
                        .addMetrics(Metric.newBuilder().setName(histName).setHistogram(Histogram.newBuilder()
                                .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_DELTA)
                                .addDataPoints(HistogramDataPoint.newBuilder().setTimeUnixNano(timestamp).setCount(5)
                                        .setSum(42.0))))
                        .addMetrics(Metric.newBuilder().setName(summaryName).setSummary(
                                Summary.newBuilder()
                                .addDataPoints(SummaryDataPoint.newBuilder().setTimeUnixNano(timestamp).setCount(3)
                                        .setSum(9.0))))
                        .addMetrics(Metric.newBuilder().setName(expHistName).setExponentialHistogram(
                                ExponentialHistogram.newBuilder()
                                .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_DELTA)
                                .addDataPoints(ExponentialHistogramDataPoint.newBuilder()
                                        .setTimeUnixNano(timestamp).setCount(7).setSum(21.0))))))
                .build();
        given().contentType("application/x-protobuf").body(request.toByteArray())
                .post("/v1/metrics").then().statusCode(200);
        assertTrue(metricsStream(service.requests("us-east-1"))
                .anyMatch(metric -> sumName.equals(metric.getName())
                        && metric.getSum().getDataPoints(0).getAsInt() == 100));
        assertTrue(metricsStream(service.requests("us-east-1"))
                .anyMatch(metric -> histName.equals(metric.getName())
                        && metric.getHistogram().getDataPoints(0).getCount() == 5));
        assertTrue(metricsStream(service.requests("us-east-1"))
                .anyMatch(metric -> summaryName.equals(metric.getName())
                        && metric.getSummary().getDataPoints(0).getCount() == 3));
        assertTrue(metricsStream(service.requests("us-east-1"))
                .anyMatch(metric -> expHistName.equals(metric.getName())
                        && metric.getExponentialHistogram().getDataPoints(0).getCount() == 7));
    }

    private static Stream<Metric> metricsStream(List<byte[]> rawRequests) {
        List<Metric> all = new ArrayList<>();
        for (byte[] bytes : rawRequests) {
            try {
                ExportMetricsServiceRequest.parseFrom(bytes).getResourceMetricsList()
                        .forEach(r -> r.getScopeMetricsList()
                                .forEach(s -> all.addAll(s.getMetricsList())));
            } catch (InvalidProtocolBufferException e) {
                throw new AssertionError("Could not parse stored OTLP metrics", e);
            }
        }
        return all.stream();
    }
}
