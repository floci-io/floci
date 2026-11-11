package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.xray.XRayClient;
import software.amazon.awssdk.services.xray.model.BatchGetTracesResponse;
import software.amazon.awssdk.services.xray.model.GetSamplingTargetsResponse;
import software.amazon.awssdk.services.xray.model.GetServiceGraphResponse;
import software.amazon.awssdk.services.xray.model.GetTraceSummariesResponse;
import software.amazon.awssdk.services.xray.model.SamplingRuleRecord;
import software.amazon.awssdk.services.xray.model.SamplingStatisticsDocument;
import software.amazon.awssdk.services.xray.model.Service;
import software.amazon.awssdk.services.xray.model.ServiceId;
import software.amazon.awssdk.services.xray.model.TraceSummary;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class XrayTest {

    private static final String TRACE_ID = "1-0a1b2c3d-4e5f60718293a4b5c6d7e8f9";
    private static final Instant NOW = Instant.now();
    private static XRayClient xray;

    @BeforeAll
    static void setup() {
        xray = TestFixtures.xrayClient();
    }

    @AfterAll
    static void cleanup() {
        if (xray != null) {
            xray.close();
        }
    }

    /** A root segment that called a second service: one trace, one edge. */
    private static String segment() {
        double base = NOW.getEpochSecond();
        return String.format(Locale.ROOT,
                "{\"trace_id\":\"%s\",\"id\":\"0f1e2d3c4b5a6978\",\"name\":\"sdk-orders\","
                        + "\"start_time\":%.1f,\"end_time\":%.1f,"
                        + "\"http\":{\"request\":{\"method\":\"GET\",\"url\":\"http://sdk/\"},"
                        + "\"response\":{\"status\":200}},"
                        + "\"annotations\":{\"env\":\"sdk\"},"
                        + "\"subsegments\":[{\"id\":\"8a7b6c5d4e3f2019\",\"name\":\"sdk-payments\","
                        + "\"start_time\":%.1f,\"end_time\":%.1f}]}",
                TRACE_ID, base, base + 1.5, base + 0.2, base + 0.9);
    }

    @Test
    @DisplayName("A segment put through the SDK is read back byte for byte")
    void putAndBatchGet() {
        String document = segment();

        xray.putTraceSegments(r -> r.traceSegmentDocuments(document));

        BatchGetTracesResponse batch = xray.batchGetTraces(r -> r.traceIds(TRACE_ID));
        assertThat(batch.traces()).hasSize(1);
        assertThat(batch.traces().get(0).id()).isEqualTo(TRACE_ID);
        assertThat(batch.traces().get(0).duration()).isEqualTo(1.5);
        assertThat(batch.traces().get(0).segments()).hasSize(1);
        assertThat(batch.traces().get(0).segments().get(0).document()).isEqualTo(document);
    }

    @Test
    @DisplayName("GetTraceSummaries reports the trace with its services and annotations")
    void traceSummaries() {
        xray.putTraceSegments(r -> r.traceSegmentDocuments(segment()));

        GetTraceSummariesResponse response = xray.getTraceSummaries(r -> r
                .startTime(NOW.minusSeconds(60))
                .endTime(NOW.plus(60, ChronoUnit.SECONDS)));

        assertThat(response.traceSummaries()).isNotEmpty();
        Optional<TraceSummary> found = response.traceSummaries().stream()
                .filter(summary -> TRACE_ID.equals(summary.id()))
                .findFirst();
        assertThat(found).isPresent();
        TraceSummary summary = found.get();
        assertThat(summary.duration()).isCloseTo(1.5, within(0.001));
        assertThat(summary.hasError()).isFalse();
        assertThat(summary.hasFault()).isFalse();
        assertThat(summary.http().httpStatus()).isEqualTo(200);
        assertThat(summary.entryPoint().name()).isEqualTo("sdk-orders");
        assertThat(summary.serviceIds()).extracting(ServiceId::name)
                .contains("sdk-orders", "sdk-payments");
        assertThat(summary.annotations().get("env").get(0).annotationValue().stringValue())
                .isEqualTo("sdk");
        assertThat(response.tracesProcessedCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("GetSamplingRules answers the Default rule AWS serves in every Region")
    void samplingRules() {
        List<SamplingRuleRecord> records = xray.getSamplingRules(r -> { }).samplingRuleRecords();

        SamplingRuleRecord defaults = records.stream()
                .filter(record -> "Default".equals(record.samplingRule().ruleName()))
                .findFirst()
                .orElseThrow();
        assertThat(defaults.samplingRule().priority()).isEqualTo(10000);
        assertThat(defaults.samplingRule().fixedRate()).isEqualTo(0.05);
        assertThat(defaults.samplingRule().reservoirSize()).isEqualTo(1);
        assertThat(defaults.samplingRule().resourceARN())
                .startsWith("arn:aws:xray:us-east-1:")
                .endsWith(":sampling-rule/Default");
    }

    @Test
    @DisplayName("GetSamplingTargets answers a rate and a reservoir quota for the rule")
    void samplingTargets() {
        SamplingStatisticsDocument statistics = SamplingStatisticsDocument.builder()
                .ruleName("Default")
                .clientID("sdk-client")
                .timestamp(Instant.now())
                .requestCount(10)
                .sampledCount(1)
                .build();

        GetSamplingTargetsResponse response = xray.getSamplingTargets(
                r -> r.samplingStatisticsDocuments(statistics));

        assertThat(response.samplingTargetDocuments()).hasSize(1);
        assertThat(response.samplingTargetDocuments().get(0).ruleName()).isEqualTo("Default");
        assertThat(response.samplingTargetDocuments().get(0).fixedRate()).isEqualTo(0.05);
        assertThat(response.samplingTargetDocuments().get(0).interval()).isEqualTo(10);
        assertThat(response.unprocessedStatistics()).isEmpty();
    }

    @Test
    @DisplayName("GetServiceGraph answers one node per service and the call between them")
    void serviceGraph() {
        xray.putTraceSegments(r -> r.traceSegmentDocuments(segment()));

        GetServiceGraphResponse response = xray.getServiceGraph(r -> r
                .startTime(NOW.minusSeconds(60))
                .endTime(NOW.plus(60, ChronoUnit.SECONDS)));

        Service orders = response.services().stream()
                .filter(service -> "sdk-orders".equals(service.name()))
                .findFirst()
                .orElseThrow();
        assertThat(orders.type()).isEqualTo("client");
        assertThat(orders.state()).isEqualTo("ok");
        assertThat(orders.summaryStatistics().okCount()).isGreaterThanOrEqualTo(1);
        assertThat(orders.edges()).hasSize(1);
        assertThat(orders.durationHistogram()).isNotEmpty();

        Service payments = response.services().stream()
                .filter(service -> "sdk-payments".equals(service.name()))
                .findFirst()
                .orElseThrow();
        assertThat(orders.edges().get(0).referenceId()).isEqualTo(payments.referenceId());
    }

    @Test
    @DisplayName("GetTraceGraph answers the same services for the named trace")
    void traceGraph() {
        xray.putTraceSegments(r -> r.traceSegmentDocuments(segment()));

        List<Service> services = xray.getTraceGraph(r -> r.traceIds(TRACE_ID)).services();

        assertThat(services).extracting(Service::name).contains("sdk-orders", "sdk-payments");
    }
}
