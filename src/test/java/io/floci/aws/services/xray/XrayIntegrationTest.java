package io.floci.aws.services.xray;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class XrayIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String TRACE_ID = "1-5f3b8a0e-3c9d4d8e9b7a2c1d4e5f6a7b";
    /** A trace of its own, so the summaries test does not inherit another test's documents. */
    private static final String GRAPH_TRACE_ID = "1-0f0e0d0c-1a2b3c4d5e6f708192a3b4c5";
    /** Inside the retention window: a segment stamped in 1970 is treated as expired. */
    private static final double NOW = Instant.now().getEpochSecond();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One segment document, as an X-Ray SDK writes it. */
    private static String segment(String id, String name) {
        return String.format(Locale.ROOT,
                "{\"trace_id\":\"%s\",\"id\":\"%s\",\"name\":\"%s\","
                        + "\"start_time\":%.1f,\"end_time\":%.1f,\"type\":\"subsegment\"}",
                TRACE_ID, id, name, NOW, NOW + 1.5);
    }

    private static String bodyOf(Object value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }

    @Test
    void aTracePutThroughTheWireComesBackAsItWasSent() throws Exception {
        String document = segment("0f1e2d3c4b5a6978", "orders-api");

        xray().body(bodyOf(Map.of("TraceSegmentDocuments", List.of(document))))
        .when()
                .post("/TraceSegments")
        .then()
                .statusCode(200)
                .body("UnprocessedTraceSegments", hasSize(0));

        xray().body(bodyOf(Map.of("TraceIds", List.of(TRACE_ID))))
        .when()
                .post("/Traces")
        .then()
                .statusCode(200)
                .body("Traces", hasSize(1))
                .body("Traces[0].Id", equalTo(TRACE_ID))
                .body("Traces[0].Duration", equalTo(1.5f))
                .body("Traces[0].Segments", hasSize(1))
                .body("Traces[0].Segments[0].Id", equalTo("0f1e2d3c4b5a6978"))
                .body("Traces[0].Segments[0].Document", equalTo(document));
    }

    @Test
    void aRejectedSegmentIsReportedWithItsReason() throws Exception {
        String broken = "{\"trace_id\":\"nope\",\"id\":\"0f1e2d3c4b5a6978\",\"name\":\"orders-api\","
                + "\"start_time\":1.0,\"end_time\":2.0}";

        xray().body(bodyOf(Map.of("TraceSegmentDocuments", List.of(broken))))
        .when()
                .post("/TraceSegments")
        .then()
                .statusCode(200)
                .body("UnprocessedTraceSegments", hasSize(1))
                .body("UnprocessedTraceSegments[0].ErrorCode", equalTo("InvalidSegment"));
    }

    @Test
    void anEmptyDocumentListIsRefused() throws Exception {
        xray().body(bodyOf(Map.of("TraceSegmentDocuments", List.of())))
        .when()
                .post("/TraceSegments")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void aTraceIsScopedToTheRegionThatWroteIt() throws Exception {
        xray(REGION).body(bodyOf(Map.of("TraceSegmentDocuments",
                        List.of(segment("0f1e2d3c4b5a6978", "orders-api")))))
        .when()
                .post("/TraceSegments")
        .then()
                .statusCode(200);

        xray("eu-west-1").body(bodyOf(Map.of("TraceIds", List.of(TRACE_ID))))
        .when()
                .post("/Traces")
        .then()
                .statusCode(200)
                .body("Traces", hasSize(0));
    }

    @Test
    void aRequestForAnotherServiceIsNotClaimedByXray() throws Exception {
        given()
                .contentType("application/json")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT_ID
                        + "/20260101/" + REGION + "/s3/aws4_request")
                .body(bodyOf(Map.of("TraceSegmentDocuments",
                        List.of(segment("0f1e2d3c4b5a6978", "s3-claim")))))
        .when()
                .post("/TraceSegments");

        xray().body(bodyOf(Map.of("TraceIds", List.of(TRACE_ID))))
        .when()
                .post("/Traces")
        .then()
                .statusCode(200)
                .body("Traces", hasSize(0));
    }

    /** A root segment that called a second service, so the graph has an edge to report. */
    private static String segmentWithACall(String traceId) {
        return String.format(Locale.ROOT,
                "{\"trace_id\":\"%s\",\"id\":\"0f1e2d3c4b5a6978\",\"name\":\"orders-api\","
                        + "\"start_time\":%.1f,\"end_time\":%.1f,"
                        + "\"http\":{\"request\":{\"method\":\"GET\",\"url\":\"http://orders/\"},"
                        + "\"response\":{\"status\":200}},"
                        + "\"annotations\":{\"env\":\"local\",\"retries\":3,\"sampled\":true},"
                        + "\"subsegments\":[{\"id\":\"8a7b6c5d4e3f2019\",\"name\":\"dynamodb\","
                        + "\"start_time\":%.1f,\"end_time\":%.1f}]}",
                traceId, NOW, NOW + 1.5, NOW + 0.2, NOW + 0.9);
    }

    @Test
    void summariesAndTheServiceGraphComeBackThroughTheWire() throws Exception {
        xray().body(bodyOf(Map.of("TraceSegmentDocuments", List.of(segmentWithACall(GRAPH_TRACE_ID)))))
        .when()
                .post("/TraceSegments")
        .then()
                .statusCode(200);

        String window = bodyOf(Map.of("StartTime", NOW - 60, "EndTime", NOW + 60));
        String onlyTheGraphTrace = bodyOf(Map.of("StartTime", NOW - 60, "EndTime", NOW + 60,
                "FilterExpression", "service(\"dynamodb\")"));

        xray().body(onlyTheGraphTrace)
        .when()
                .post("/TraceSummaries")
        .then()
                .statusCode(200)
                .body("TraceSummaries", hasSize(1))
                .body("TraceSummaries[0].Id", equalTo(GRAPH_TRACE_ID))
                .body("TraceSummaries[0].Duration", equalTo(1.5f))
                .body("TraceSummaries[0].HasError", equalTo(false))
                .body("TraceSummaries[0].Http.HttpStatus", equalTo(200))
                .body("TraceSummaries[0].EntryPoint.Name", equalTo("orders-api"))
                .body("TraceSummaries[0].ServiceIds", hasSize(2))
                .body("TraceSummaries[0].Annotations.env[0].AnnotationValue.StringValue",
                        equalTo("local"))
                .body("TraceSummaries[0].Annotations.retries[0].AnnotationValue.NumberValue",
                        equalTo(3.0f))
                .body("TraceSummaries[0].Annotations.sampled[0].AnnotationValue.BooleanValue",
                        equalTo(true))
                .body("TracesProcessedCount", greaterThanOrEqualTo(1));

        xray().body(window)
        .when()
                .post("/ServiceGraph")
        .then()
                .statusCode(200)
                .body("Services", hasSize(2))
                .body("Services[0].Name", equalTo("orders-api"))
                .body("Services[0].Type", equalTo("client"))
                .body("Services[0].State", equalTo("ok"))
                .body("Services[0].Edges", hasSize(1))
                .body("Services[0].SummaryStatistics.OkCount", greaterThanOrEqualTo(1))
                .body("Services[0].SummaryStatistics.TotalCount", greaterThanOrEqualTo(1))
                .body("Services[0].DurationHistogram", hasSize(9));

        xray().body(bodyOf(Map.of("TraceIds", List.of(GRAPH_TRACE_ID))))
        .when()
                .post("/TraceGraph")
        .then()
                .statusCode(200)
                .body("Services", hasSize(2));
    }

    @Test
    void aThrottledCallIsReportedAsAThrottleAndCountedOnceInTheErrorTotal() throws Exception {
        String traceId = "1-9a8b7c6d-1e2f3a4b5c6d7e8f9a0b1c2d";
        String document = String.format(Locale.ROOT,
                "{\"trace_id\":\"%s\",\"id\":\"0f1e2d3c4b5a6978\",\"name\":\"orders-api\","
                        + "\"start_time\":%.1f,\"end_time\":%.1f,\"error\":true,\"throttle\":true}",
                traceId, NOW, NOW + 0.4);
        xray().body(bodyOf(Map.of("TraceSegmentDocuments", List.of(document))))
        .when()
                .post("/TraceSegments")
        .then()
                .statusCode(200);

        xray().body(bodyOf(Map.of("StartTime", NOW - 60, "EndTime", NOW + 60)))
        .when()
                .post("/ServiceGraph")
        .then()
                .statusCode(200)
                .body("Services.find { it.Name == 'orders-api' }.SummaryStatistics.ErrorStatistics.ThrottleCount",
                        equalTo(1))
                .body("Services.find { it.Name == 'orders-api' }.SummaryStatistics.ErrorStatistics.OtherCount",
                        equalTo(0))
                .body("Services.find { it.Name == 'orders-api' }.SummaryStatistics.ErrorStatistics.TotalCount",
                        equalTo(1))
                // The graph aggregates per service name across the window, and other cases here
                // post traces for the same service, so the total is at least this one call.
                .body("Services.find { it.Name == 'orders-api' }.SummaryStatistics.TotalCount",
                        greaterThanOrEqualTo(1));
    }

    @Test
    void anUnsupportedFilterIsRefusedThroughTheWire() throws Exception {
        String request = bodyOf(Map.of("StartTime", NOW - 60, "EndTime", NOW + 60,
                "FilterExpression", "edge(\"a\", \"b\")"));

        xray().body(request)
        .when()
                .post("/TraceSummaries")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void samplingRulesTargetsAndTelemetryWorkThroughTheWire() throws Exception {
        xray().body(bodyOf(Map.of("SamplingRule", Map.of(
                        "RuleName", "it-rule", "Priority", 5, "FixedRate", 0.5, "ReservoirSize", 4,
                        "ServiceName", "*", "ServiceType", "*", "Host", "*",
                        "HTTPMethod", "*", "URLPath", "*", "Version", 1))))
        .when()
                .post("/CreateSamplingRule")
        .then()
                .statusCode(200)
                .body("SamplingRuleRecord.SamplingRule.RuleName", equalTo("it-rule"))
                .body("SamplingRuleRecord.SamplingRule.RuleARN",
                        equalTo("arn:aws:xray:us-east-1:000000000000:sampling-rule/it-rule"));

        xray().body("{}")
        .when()
                .post("/GetSamplingRules")
        .then()
                .statusCode(200)
                .body("SamplingRuleRecords.SamplingRule.RuleName", hasItems("Default", "it-rule"));

        xray().body(bodyOf(Map.of("SamplingStatisticsDocuments", List.of(Map.of(
                        "RuleName", "it-rule", "ClientID", "daemon-1",
                        "Timestamp", NOW, "RequestCount", 10, "SampledCount", 1)))))
        .when()
                .post("/SamplingTargets")
        .then()
                .statusCode(200)
                .body("SamplingTargetDocuments", hasSize(1))
                .body("SamplingTargetDocuments[0].RuleName", equalTo("it-rule"))
                .body("SamplingTargetDocuments[0].ReservoirQuota", equalTo(4))
                .body("SamplingTargetDocuments[0].Interval", equalTo(10));

        xray().body(bodyOf(Map.of("TelemetryRecords", List.of(Map.of(
                        "Timestamp", NOW, "SegmentsReceivedCount", 2, "SegmentsSentCount", 2)),
                        "Hostname", "it-host")))
        .when()
                .post("/TelemetryRecords")
        .then()
                .statusCode(200);
    }

    private static RequestSpecification xray() {
        return xray(REGION);
    }

    private static RequestSpecification xray(String region) {
        String authorization = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT_ID
                + "/20260101/" + region + "/xray/aws4_request";
        return given()
                .contentType("application/json")
                .header("Authorization", authorization);
    }
}
