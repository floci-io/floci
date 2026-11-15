package io.floci.aws.services.xray;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.xray.model.SamplingRule;
import io.floci.aws.services.xray.model.Trace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XrayServiceTest {

    private static final String REGION = "us-east-1";
    private static final String TRACE_ID = "1-5f3b8a0e-3c9d4d8e9b7a2c1d4e5f6a7b";
    private static final String OTHER_TRACE_ID = "1-1a2b3c4d-5e6f7a8b9c0d1e2f3a4b5c6d";
    /** Inside the retention window: a segment stamped in 1970 is treated as expired. */
    private static final double NOW = Instant.now().getEpochSecond();
    /** A segment id is a 64 bit hex identifier, so the fixture cannot invent a readable one. */
    private static final String SEGMENT_ID = "0f1e2d3c4b5a6978";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private XrayService service;

    @BeforeEach
    void setUp() {
        service = new XrayService(AccountAwareStorageBackend.inMemory("000000000000"),
                AccountAwareStorageBackend.inMemory("000000000000"));
    }

    private static String segment(String traceId, String id, String name, double start, double end) {
        return "{\"trace_id\":\"" + traceId + "\",\"id\":\"" + id + "\",\"name\":\"" + name
                + "\",\"start_time\":" + start + ",\"end_time\":" + end + ",\"type\":\"subsegment\"}";
    }

    @Test
    void segmentsOfOneTraceAreKeptTogetherAndReadBackVerbatim() {
        String root = segment(TRACE_ID, "0f1e2d3c4b5a6978", "orders-api", NOW, NOW + 1.5);
        String child = segment(TRACE_ID, "8a7b6c5d4e3f2019", "dynamodb", NOW + 0.2, NOW + 0.9);

        List<XrayService.UnprocessedSegment> rejected = service.putTraceSegments(List.of(root, child), REGION);

        assertTrue(rejected.isEmpty());
        XrayService.BatchGetResult result = service.batchGetTraces(List.of(TRACE_ID), REGION);
        assertEquals(1, result.traces().size());
        Trace trace = result.traces().get(0);
        assertEquals(List.of(root, child), trace.getDocuments());
        assertEquals(1.5, trace.getEndTime() - trace.getStartTime(), 0.0001);
        assertTrue(result.unprocessedIds().isEmpty());
    }

    @Test
    void aMalformedDocumentIsRejectedAloneAndTheRestIsStored() {
        String broken = "{\"trace_id\":\"not-a-trace\",\"id\":\"0f1e2d3c4b5a6978\",\"name\":\"x\","
                + "\"start_time\":1.0,\"end_time\":2.0}";
        String good = segment(TRACE_ID, "0f1e2d3c4b5a6978", "orders-api", NOW, NOW + 1.0);

        List<XrayService.UnprocessedSegment> rejected = service.putTraceSegments(List.of(broken, good), REGION);

        assertEquals(1, rejected.size());
        assertEquals("InvalidSegment", rejected.get(0).errorCode());
        assertEquals(1, service.batchGetTraces(List.of(TRACE_ID), REGION).traces().size());
    }

    @Test
    void aDocumentThatIsNotJsonIsRejected() {
        List<XrayService.UnprocessedSegment> rejected =
                service.putTraceSegments(List.of("not json at all"), REGION);

        assertEquals(1, rejected.size());
        assertEquals("InvalidSegment", rejected.get(0).errorCode());
    }

    @Test
    void anEndBeforeTheStartIsRejected() {
        String backwards = segment(TRACE_ID, "0f1e2d3c4b5a6978", "orders-api", 1001.0, 1000.0);

        List<XrayService.UnprocessedSegment> rejected = service.putTraceSegments(List.of(backwards), REGION);

        assertEquals(1, rejected.size());
        assertTrue(rejected.get(0).message().contains("end_time is before start_time"));
    }

    @Test
    void aCallCarriesAtMostOneHundredSegments() {
        List<String> documents = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            documents.add(segment(TRACE_ID, "0f1e2d3c4b5a69" + String.format("%02d", i), "orders-api", NOW, NOW + 1.0));
        }

        AwsException thrown = assertThrows(AwsException.class,
                () -> service.putTraceSegments(documents, REGION));

        assertEquals("InvalidRequestException", thrown.getErrorCode());
        assertEquals(400, thrown.getHttpStatus());
    }

    @Test
    void aBatchAsksForAtMostFiveTraces() {
        AwsException thrown = assertThrows(AwsException.class,
                () -> service.batchGetTraces(List.of("1", "2", "3", "4", "5", "6"), REGION));

        assertEquals("InvalidRequestException", thrown.getErrorCode());
    }

    @Test
    void anUnknownTraceIdIsReportedUnprocessed() {
        service.putTraceSegments(List.of(segment(TRACE_ID, "0f1e2d3c4b5a6978", "orders-api", NOW, NOW + 1.0)), REGION);

        XrayService.BatchGetResult result = service.batchGetTraces(List.of(TRACE_ID, OTHER_TRACE_ID), REGION);

        assertEquals(1, result.traces().size());
        assertEquals(List.of(OTHER_TRACE_ID), result.unprocessedIds());
    }

    @Test
    void tracesAreScopedToTheirRegion() {
        service.putTraceSegments(List.of(segment(TRACE_ID, "0f1e2d3c4b5a6978", "orders-api", NOW, NOW + 1.0)), REGION);

        assertTrue(service.batchGetTraces(List.of(TRACE_ID), "eu-west-1").traces().isEmpty());
    }

    @Test
    void clearForgetsEveryTrace() {
        service.putTraceSegments(List.of(segment(TRACE_ID, "0f1e2d3c4b5a6978", "orders-api", NOW, NOW + 1.0)), REGION);

        service.clear();

        assertTrue(service.traces(REGION).isEmpty());
    }

    /** One trace whose root calls a different service, so the graph has an edge to find. */
    private static String traceWithACall(String traceId, double start, double duration,
                                         boolean error, int status) {
        return "{\"trace_id\":\"" + traceId + "\",\"id\":\"0f1e2d3c4b5a6978\","
                + "\"name\":\"orders-api\",\"start_time\":" + start
                + ",\"end_time\":" + (start + duration) + (error ? ",\"error\":true" : "")
                + ",\"http\":{\"request\":{\"method\":\"GET\",\"url\":\"http://orders/\"},"
                + "\"response\":{\"status\":" + status + "}},"
                + "\"annotations\":{\"env\":\"local\"},"
                + "\"subsegments\":[{\"id\":\"8a7b6c5d4e3f2019\",\"name\":\"dynamodb\","
                + "\"start_time\":" + (start + 0.2) + ",\"end_time\":" + (start + 0.9) + "}]}";
    }

    @Test
    void summariesReportTheTracesInsideTheWindow() {
        service.putTraceSegments(List.of(traceWithACall(TRACE_ID, NOW, 1.5, false, 200)), REGION);
        service.putTraceSegments(List.of(traceWithACall(OTHER_TRACE_ID, NOW + 500, 0.5, true, 500)),
                REGION);

        XrayService.SummaryPage page = service.traceSummaries(NOW - 10, NOW + 10, null, null, null,
                REGION);

        assertEquals(1, page.processedCount(), "only the trace inside the window is processed");
        assertEquals(1, page.items().size());
        XrayService.TraceSummary summary = page.items().get(0);
        assertEquals(TRACE_ID, summary.id());
        assertEquals(1.5, summary.duration(), 0.0001);
        assertEquals(Integer.valueOf(200), summary.httpStatus());
        assertEquals(List.of("orders-api", "dynamodb"), summary.services());
        assertEquals("orders-api", summary.entryPoint());
        assertEquals("local", summary.annotations().get("env"));
    }

    @Test
    void aFilterNarrowsTheSummariesAndARefusedOneIsAnError() {
        service.putTraceSegments(List.of(traceWithACall(TRACE_ID, NOW, 1.5, false, 200)), REGION);
        service.putTraceSegments(List.of(traceWithACall(OTHER_TRACE_ID, NOW + 1, 0.5, true, 500)),
                REGION);

        assertEquals(2, service.traceSummaries(NOW - 10, NOW + 30, null, null, null, REGION)
                .items().size());
        assertEquals(1, service.traceSummaries(NOW - 10, NOW + 30, "error", null, null, REGION)
                .items().size());
        assertEquals(1, service.traceSummaries(NOW - 10, NOW + 30, "service(\"dynamodb\") AND http.status = 200",
                null, null, REGION).items().size());

        AwsException refused = assertThrows(AwsException.class, () -> service.traceSummaries(
                NOW - 10, NOW + 30, "sampling.stratum = 1", null, null, REGION));
        assertEquals("InvalidRequestException", refused.getErrorCode());
    }

    @Test
    void aWindowThatEndsBeforeItStartsIsRefused() {
        AwsException thrown = assertThrows(AwsException.class,
                () -> service.traceSummaries(NOW, NOW - 1, null, null, null, REGION));

        assertEquals("InvalidRequestException", thrown.getErrorCode());
    }

    @Test
    void theServiceGraphHasANodePerServiceAndAnEdgePerCall() {
        service.putTraceSegments(List.of(traceWithACall(TRACE_ID, NOW, 1.5, false, 200)), REGION);
        service.putTraceSegments(List.of(traceWithACall(OTHER_TRACE_ID, NOW + 1, 0.5, true, 500)),
                REGION);

        List<XrayGraph.Node> nodes = service.serviceGraph(NOW - 10, NOW + 10, REGION);

        assertEquals(2, nodes.size());
        XrayGraph.Node orders = nodes.get(0);
        assertEquals("orders-api", orders.name());
        assertEquals("client", orders.type());
        assertEquals(2, orders.totalCount());
        assertEquals(1, orders.edges().size());
        XrayGraph.Node dynamodb = nodes.get(1);
        assertEquals("dynamodb", dynamodb.name());
        assertEquals(orders.edges().get(0).referenceId(), dynamodb.referenceId());
        assertEquals(2, dynamodb.totalCount());
        assertTrue(orders.durationHistogram().size() > 0);
    }

    @Test
    void theTraceGraphAnswersTheNamedTracesOnly() {
        service.putTraceSegments(List.of(traceWithACall(TRACE_ID, NOW, 1.5, false, 200)), REGION);

        List<XrayGraph.Node> nodes = service.traceGraph(List.of(TRACE_ID), REGION);

        assertEquals(2, nodes.size());
        assertTrue(service.traceGraph(List.of(OTHER_TRACE_ID), REGION).isEmpty());
    }



    private static String rule(String name, int priority, double rate) {
        return "{\"SamplingRule\":{\"RuleName\":\"" + name + "\",\"Priority\":" + priority
                + ",\"FixedRate\":" + rate + ",\"ReservoirSize\":5,\"ServiceName\":\"orders-api\","
                + "\"ServiceType\":\"*\",\"Host\":\"*\",\"HTTPMethod\":\"*\","
                + "\"URLPath\":\"*\",\"Version\":1}}";
    }

    @Test
    void theDefaultRuleIsAlwaysServedFirstAndCannotBeDeleted() throws Exception {
        List<SamplingRule> rules = service.samplingRules(REGION);

        assertEquals(1, rules.size());
        SamplingRule rule = rules.get(0);
        assertEquals("Default", rule.getRuleName());
        assertEquals(10000, rule.getPriority());
        assertEquals(0.05, rule.getFixedRate(), 0.0001);
        assertEquals(1, rule.getReservoirSize());
        assertEquals("arn:aws:xray:us-east-1:000000000000:sampling-rule/Default", rule.getRuleArn());

        AwsException refused = assertThrows(AwsException.class, () -> service.deleteSamplingRule(
                objectMapper.readTree("{\"RuleName\":\"Default\"}"), REGION));
        assertEquals("InvalidRequestException", refused.getErrorCode());
    }

    @Test
    void aRuleIsCreatedUpdatedListedAndDeleted() throws Exception {
        SamplingRule created = service.createSamplingRule(objectMapper.readTree(rule("orders", 1, 0.5)),
                REGION);

        assertEquals("orders", created.getRuleName());
        assertEquals("arn:aws:xray:us-east-1:000000000000:sampling-rule/orders", created.getRuleArn());
        assertEquals(2, service.samplingRules(REGION).size());
        assertEquals("orders", service.samplingRules(REGION).get(0).getRuleName(), "priority orders the list");

        SamplingRule updated = service.updateSamplingRule(objectMapper.readTree(
                "{\"SamplingRuleUpdate\":{\"RuleName\":\"orders\",\"FixedRate\":0.25,"
                        + "\"ReservoirSize\":9}}"), REGION);
        assertEquals(0.25, updated.getFixedRate(), 0.0001);
        assertEquals(9, updated.getReservoirSize());
        assertEquals(1, updated.getPriority(), "an update keeps the members it was not given");

        SamplingRule deleted = service.deleteSamplingRule(
                objectMapper.readTree("{\"RuleARN\":\"" + created.getRuleArn() + "\"}"), REGION);
        assertEquals("orders", deleted.getRuleName());
        assertEquals(1, service.samplingRules(REGION).size());

        AwsException missing = assertThrows(AwsException.class, () -> service.deleteSamplingRule(
                objectMapper.readTree("{\"RuleName\":\"orders\"}"), REGION));
        assertEquals("InvalidRequestException", missing.getErrorCode());
    }

    @Test
    void aRegionTakesAtMostTwentyFiveRulesAndRejectsADuplicate() throws Exception {
        for (int i = 1; i <= 25; i++) {
            service.createSamplingRule(objectMapper.readTree(rule("rule-" + i, i, 0.1)), REGION);
        }

        AwsException tooMany = assertThrows(AwsException.class,
                () -> service.createSamplingRule(objectMapper.readTree(rule("rule-26", 26, 0.1)), REGION));
        assertEquals("RuleLimitExceededException", tooMany.getErrorCode());

        AwsException duplicate = assertThrows(AwsException.class,
                () -> service.createSamplingRule(objectMapper.readTree(rule("rule-1", 1, 0.1)), REGION));
        assertEquals("InvalidRequestException", duplicate.getErrorCode());
    }

    @Test
    void targetsAnswerTheRuleTheDaemonAskedForAndReportUnknownOnes() throws Exception {
        service.createSamplingRule(objectMapper.readTree(rule("orders", 1, 0.5)), REGION);

        XrayService.Targets targets = service.samplingTargets(objectMapper.readTree(
                "{\"SamplingStatisticsDocuments\":["
                        + "{\"RuleName\":\"orders\",\"ClientID\":\"daemon-1\",\"Timestamp\":1.0,"
                        + "\"RequestCount\":10,\"SampledCount\":2},"
                        + "{\"RuleName\":\"ghost\",\"ClientID\":\"daemon-1\",\"Timestamp\":1.0,"
                        + "\"RequestCount\":1,\"SampledCount\":0}]}"), REGION);

        assertEquals(1, targets.documents().size());
        XrayService.Target target = targets.documents().get(0);
        assertEquals("orders", target.ruleName());
        assertEquals(0.5, target.fixedRate(), 0.0001);
        assertEquals(Integer.valueOf(5), target.reservoirQuota());
        assertEquals(10, target.interval());
        assertEquals(1, targets.unprocessed().size());
        assertEquals("RuleNotFound", targets.unprocessed().get(0).errorCode());
    }

    @Test
    void aRejectedUpdateLeavesTheRuleUntouched() throws Exception {
        service.createSamplingRule(objectMapper.readTree(rule("orders", 1, 0.5)), REGION);

        // ReservoirSize is valid and FixedRate is not, so nothing may be applied.
        AwsException refused = assertThrows(AwsException.class, () -> service.updateSamplingRule(
                objectMapper.readTree("{\"SamplingRuleUpdate\":{\"RuleName\":\"orders\","
                        + "\"ReservoirSize\":9,\"FixedRate\":4.5}}"), REGION));
        assertEquals("InvalidRequestException", refused.getErrorCode());

        SamplingRule unchanged = service.samplingRules(REGION).get(0);
        assertEquals("orders", unchanged.getRuleName());
        assertEquals(5, unchanged.getReservoirSize());
        assertEquals(0.5, unchanged.getFixedRate(), 0.0001);
    }

    @Test
    void theDefaultRuleCannotBeUpdatedAndIsNeverStoredTwice() throws Exception {
        AwsException refused = assertThrows(AwsException.class, () -> service.updateSamplingRule(
                objectMapper.readTree("{\"SamplingRuleUpdate\":{\"RuleName\":\"Default\","
                        + "\"FixedRate\":0.9}}"), REGION));
        assertEquals("InvalidRequestException", refused.getErrorCode());

        List<SamplingRule> rules = service.samplingRules(REGION);
        assertEquals(1, rules.size());
        assertEquals(0.05, rules.get(0).getFixedRate(), 0.0001);
    }

    @Test
    void aSegmentStillInFlightIsAccepted() throws Exception {
        String open = "{\"trace_id\":\"" + TRACE_ID + "\",\"id\":\"0f1e2d3c4b5a6978\","
                + "\"name\":\"orders-api\",\"start_time\":" + NOW + "}";

        List<XrayService.UnprocessedSegment> rejected = service.putTraceSegments(List.of(open), REGION);

        assertTrue(rejected.isEmpty());
        assertEquals(1, service.batchGetTraces(List.of(TRACE_ID), REGION).traces().size());
    }

    @Test
    void aRetriedSegmentReplacesTheStoredOne() {
        String document = segment(TRACE_ID, "0f1e2d3c4b5a6978", "orders-api", NOW, NOW + 1.0);

        service.putTraceSegments(List.of(document, document), REGION);

        Trace trace = service.batchGetTraces(List.of(TRACE_ID), REGION).traces().get(0);
        assertEquals(1, trace.getDocuments().size());
    }

    @Test
    void aThrottledCallIsCountedAsThrottledInTheGraph() {
        String throttled = "{\"trace_id\":\"" + TRACE_ID + "\",\"id\":\"0f1e2d3c4b5a6978\","
                + "\"name\":\"orders-api\",\"start_time\":" + NOW + ",\"end_time\":" + (NOW + 1)
                + ",\"throttle\":true}";
        service.putTraceSegments(List.of(throttled), REGION);

        List<XrayGraph.Node> nodes = service.serviceGraph(NOW - 10, NOW + 10, REGION);

        assertEquals(1, nodes.size());
        assertEquals("throttle", nodes.get(0).state());
        assertEquals(0, nodes.get(0).okCount());
        assertEquals(1, nodes.get(0).throttleCount());
    }

    @Test
    void storingASegmentSwapsTheDocumentListInsteadOfEditingTheOneReadersHold() {
        service.putTraceSegments(List.of(segment(TRACE_ID, SEGMENT_ID, "orders-api", NOW, NOW + 0.2)), REGION);
        List<String> held = service.batchGetTraces(List.of(TRACE_ID), REGION).traces().get(0).getDocuments();
        assertEquals(1, held.size());
        // A summary or a BatchGetTraces call walks this list while the daemon keeps posting
        // segments; if the writer edited it in place that walk would throw and the held view
        // would change under it.
        assertDoesNotThrow(() -> held.forEach(document -> document.length()));

        service.putTraceSegments(List.of(segment(TRACE_ID, SEGMENT_ID, "orders-api", NOW, NOW + 2.4)), REGION);

        assertEquals(1, held.size(), "the list already handed out keeps what it had");
        List<String> current = service.batchGetTraces(List.of(TRACE_ID), REGION).traces().get(0).getDocuments();
        assertEquals(1, current.size());
        assertNotSame(held, current, "a write publishes a new list, it does not edit the old one");
        assertEquals(current, service.batchGetTraces(List.of(TRACE_ID), REGION).traces().get(0).getDocuments());
    }

    @Test
    void racingCreatesOfOneRuleLeaveExactlyOne() throws Exception {
        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < racers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.createSamplingRule(objectMapper.readTree(rule("race", 1, 0.1)), REGION);
                        return true;
                    } catch (AwsException expected) {
                        return false;
                    }
                }));
            }
            start.countDown();
            long created = 0;
            for (Future<Boolean> result : results) {
                created += result.get(10, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertEquals(1, created);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, service.samplingRules(REGION).size(), "the rule and the Default one");
    }

    @Test
    void telemetryRecordsAreAccepted() throws Exception {
        int accepted = service.putTelemetryRecords(objectMapper.readTree(
                "{\"TelemetryRecords\":[{\"Timestamp\":1.0,\"SegmentsReceivedCount\":3,"
                        + "\"SegmentsSentCount\":3}],\"Hostname\":\"local\"}"), REGION);

        assertEquals(1, accepted);
    }


}
