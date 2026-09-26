package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.lambda.model.FunctionEventInvokeConfig;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsyncInvokeDestinationRouterTest {

    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:bank-pawnshop";
    private static final String BUS_ARN = "arn:aws:events:us-east-1:000000000000:event-bus/quotes-bus";
    private static final String QUEUE_ARN = "arn:aws:sqs:us-east-1:000000000000:quotes-queue";
    private static final String TOPIC_ARN = "arn:aws:sns:us-east-1:000000000000:quotes-topic";
    private static final String COLLECTOR_ARN = "arn:aws:lambda:us-east-1:000000000000:function:collector";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock LambdaService lambdaService;
    @Mock EventBridgeService eventBridgeService;
    @Mock SqsService sqsService;
    @Mock SnsService snsService;
    @Mock EmulatorConfig config;

    private AsyncInvokeDestinationRouter router;
    private LambdaFunction fn;

    @BeforeEach
    void setUp() {
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        when(eventBridgeService.putEvents(any(), any(), any()))
                .thenReturn(new EventBridgeService.PutEventsResult(0, List.of()));
        when(lambdaService.findEventInvokeConfig(any())).thenReturn(Optional.empty());

        router = new AsyncInvokeDestinationRouter(instanceOf(lambdaService), instanceOf(eventBridgeService),
                instanceOf(sqsService), instanceOf(snsService), MAPPER, config);

        fn = new LambdaFunction();
        fn.setFunctionName("bank-pawnshop");
        fn.setFunctionArn(FUNCTION_ARN);
        fn.setAccountId("000000000000");
    }

    @Test
    void record_timestampAlwaysCarriesExactlyThreeFractionalDigits() {
        // AWS always emits milliseconds (2019-11-14T18:16:05.568Z). Instant.toString() emits
        // micro/nanosecond precision on JDK 9+ and NO fractional part at all when the nanos are
        // zero, so a consumer parsing with a fixed .SSS pattern breaks about once in a billion
        // records -- the kind of defect that never shows up in a test run that happens to be lucky.
        configure(BUS_ARN, null);

        router.route(fn, request(), success("{}"), 0);

        String timestamp = detailOf(capturedEventEntry()).get("timestamp").asText();
        assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"),
                "timestamp must be ISO-8601 with exactly three fractional digits, was: " + timestamp);

        // The deciding case, pinned rather than left to chance. An exact second is the only input
        // that separates the formatter from truncatedTo(MILLIS).toString(), which drops the
        // fraction there; asserting only on now() passes against both ~999 runs in 1000.
        assertEquals("2019-11-14T18:16:05.000Z",
                AsyncInvokeDestinationRouter.formatRecordTimestamp(Instant.ofEpochSecond(1573755365L)),
                "an exact second must still carry .000");
    }

    @Test
    void eventBridgeDestination_carriesTheFunctionAndDestinationAsEventResources() {
        // AWS fills the event's resources with the invoked function and the destination. Without
        // them a rule matching on `resources` never fires: matchesPattern casts the ABSENT member
        // to an ArrayNode, the NPE is swallowed and logged as a pattern-parse failure, and the
        // record is dropped at the bus while the log blames the caller's pattern. Rules matching
        // on `detail` are unaffected, which is why this stayed invisible.
        configure(BUS_ARN, null);

        router.route(fn, request(), success("{\"bankId\":\"PawnShop\"}"), 0);

        JsonNode resources = capturedEventEntry().get("Resources");
        assertTrue(resources != null && resources.isArray() && resources.size() == 2,
                "the entry must carry both ARNs as Resources, was: " + resources);
        assertEquals(FUNCTION_ARN, resources.get(0).asText());
        assertEquals(BUS_ARN, resources.get(1).asText());
    }

    @Test
    void onSuccessEventBridgeDestination_putsTheRecordOnTheBusAsTheEventDetail() {
        configure(BUS_ARN, null);

        router.route(fn, request(), success("{\"bankId\":\"PawnShop\",\"rate\":3.5}"), 0);

        JsonNode entry = capturedEventEntry();
        assertEquals("lambda", entry.get("Source").asText());
        assertEquals("Lambda Function Invocation Result - Success", entry.get("DetailType").asText());
        assertEquals(BUS_ARN, entry.get("EventBusName").asText());

        JsonNode detail = detailOf(entry);
        assertEquals("1.0", detail.get("version").asText());
        assertTrue(detail.hasNonNull("timestamp"), "record should carry a timestamp");
        assertEquals("Success", detail.path("requestContext").path("condition").asText());
        assertEquals(FUNCTION_ARN + ":$LATEST", detail.path("requestContext").path("functionArn").asText());
        assertEquals("req-1", detail.path("requestContext").path("requestId").asText());
        assertEquals(1, detail.path("requestContext").path("approximateInvokeCount").asInt());
        assertEquals("BANK-PawnShop", detail.path("requestPayload").path("bankId").asText());
        assertEquals(200, detail.path("responseContext").path("statusCode").asInt());
        assertEquals("$LATEST", detail.path("responseContext").path("executedVersion").asText());
        assertTrue(detail.path("responseContext").path("functionError").isMissingNode(),
                "a success record carries no functionError");
        // The rule of a real CDK application matches on this path.
        assertEquals("PawnShop", detail.path("responsePayload").path("bankId").asText());
        assertEquals(3.5, detail.path("responsePayload").path("rate").asDouble());
    }

    @Test
    void onSuccessSqsDestination_sendsTheRecordToTheQueue() {
        configure(QUEUE_ARN, null);

        router.route(fn, request(), success("{\"rate\":3.5}"), 0);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sqsService).sendMessage(eq("http://localhost:4566/000000000000/quotes-queue"),
                body.capture(), eq(0), eq("us-east-1"));

        JsonNode record = read(body.getValue());
        assertEquals("Success", record.path("requestContext").path("condition").asText());
        assertEquals(3.5, record.path("responsePayload").path("rate").asDouble());
        verifyNoInteractions(eventBridgeService);
    }

    @Test
    void onSuccessSnsDestination_publishesTheRecordToTheTopic() {
        configure(TOPIC_ARN, null);

        router.route(fn, request(), success("{\"rate\":3.5}"), 0);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(snsService).publish(eq(TOPIC_ARN), eq(null), message.capture(), anyString(), eq("us-east-1"));
        assertEquals(3.5, read(message.getValue()).path("responsePayload").path("rate").asDouble());
    }

    @Test
    void onSuccessLambdaDestination_invokesTheFunctionAsynchronously() {
        configure(COLLECTOR_ARN, null);

        router.route(fn, request(), success("{\"rate\":3.5}"), 0);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invokeArnFromDestination(eq(COLLECTOR_ARN), payload.capture(), eq(1));
        assertEquals(3.5, read(new String(payload.getValue())).path("responsePayload").path("rate").asDouble());
    }

    @Test
    void lambdaDestinationPartWayAlongAChain_invokesWithTheNextHopCount() {
        configure(COLLECTOR_ARN, null);

        router.route(fn, request(), success("{\"rate\":3.5}"), 3);

        verify(lambdaService).invokeArnFromDestination(eq(COLLECTOR_ARN), any(), eq(4));
    }

    @Test
    void nonLambdaDestinationAtTheChainLimit_isStillDelivered() {
        // The bound exists to stop a chain re-entering Lambda; a queue is the end of one, and AWS
        // lets the message through and stops the invocation it would cause instead.
        configure(QUEUE_ARN, null);

        router.route(fn, request(), success("{\"rate\":3.5}"), 16);

        verify(sqsService).sendMessage(eq("http://localhost:4566/000000000000/quotes-queue"),
                anyString(), eq(0), eq("us-east-1"));
    }

    @Test
    void snsDestination_publishesOneHopFurtherAlongTheChain() {
        // SnsService invokes a Lambda subscriber on the publishing thread, so the depth it reads
        // there is what stops a topic that fans straight back into the function.
        configure(TOPIC_ARN, null);
        AtomicInteger depthWhilePublishing = new AtomicInteger(-1);
        doAnswer(invocation -> {
            depthWhilePublishing.set(LambdaInvocationChain.currentDepth());
            return null;
        }).when(snsService).publish(anyString(), any(), anyString(), anyString(), anyString());

        router.route(fn, request(), success("{\"rate\":3.5}"), 3);

        assertEquals(4, depthWhilePublishing.get());
        assertEquals(0, LambdaInvocationChain.currentDepth(), "the thread must be left as it was found");
    }

    @Test
    void eventBridgeDestination_putsTheEventOneHopFurtherAlongTheChain() {
        configure(BUS_ARN, null);
        AtomicInteger depthWhilePutting = new AtomicInteger(-1);
        when(eventBridgeService.putEvents(any(), any(), any())).thenAnswer(invocation -> {
            depthWhilePutting.set(LambdaInvocationChain.currentDepth());
            return new EventBridgeService.PutEventsResult(0, List.of());
        });

        router.route(fn, request(), success("{\"rate\":3.5}"), 3);

        assertEquals(4, depthWhilePutting.get());
        assertEquals(0, LambdaInvocationChain.currentDepth(), "the thread must be left as it was found");
    }

    @Test
    void snsDestinationFanningBackIntoTheFunction_stopsAtTheChainBound() {
        // The reported cycle: the function's OnSuccess is a topic the function itself subscribes
        // to. The stand-in below re-enters where SnsService would, on the publishing thread, and
        // refuses the invocation on the same rule LambdaExecutorService applies. Before the depth
        // travelled through SNS every hop started a fresh chain and this never came back.
        configure(TOPIC_ARN, null);
        AtomicInteger invocations = new AtomicInteger();
        doAnswer(invocation -> {
            int depth = LambdaInvocationChain.currentDepth();
            if (LambdaInvocationChain.exhausted(depth)) {
                return null;
            }
            invocations.incrementAndGet();
            router.route(fn, request(), success("{\"rate\":3.5}"), depth);
            return null;
        }).when(snsService).publish(anyString(), any(), anyString(), anyString(), anyString());

        router.route(fn, request(), success("{\"rate\":3.5}"), 0);

        assertEquals(LambdaInvocationChain.MAX_DEPTH - 1, invocations.get(),
                "the originating invocation plus these re-entrant ones is the bound");
        verify(snsService, times(LambdaInvocationChain.MAX_DEPTH))
                .publish(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void handlerError_routesToTheFailureDestinationWithTheErrorPayload() {
        configure(BUS_ARN, BUS_ARN);
        InvokeResult failure = new InvokeResult(200, "Unhandled",
                "{\"errorMessage\":\"boom\",\"errorType\":\"Error\"}".getBytes(), null, "req-1");

        router.route(fn, request(), failure, 0);

        JsonNode entry = capturedEventEntry();
        assertEquals("Lambda Function Invocation Result - Failure", entry.get("DetailType").asText());

        JsonNode detail = detailOf(entry);
        assertEquals("RetriesExhausted", detail.path("requestContext").path("condition").asText());
        assertEquals("Unhandled", detail.path("responseContext").path("functionError").asText());
        assertEquals("boom", detail.path("responsePayload").path("errorMessage").asText());
        assertEquals("Error", detail.path("responsePayload").path("errorType").asText());
    }

    @Test
    void handlerErrorWithNoFailureDestination_deliversNothing() {
        configure(BUS_ARN, null);
        InvokeResult failure = new InvokeResult(200, "Unhandled",
                "{\"errorMessage\":\"boom\"}".getBytes(), null, "req-1");

        router.route(fn, request(), failure, 0);

        verifyNoInteractions(eventBridgeService, sqsService, snsService);
    }

    @Test
    void noEventInvokeConfig_deliversNothing() {
        router.route(fn, request(), success("{\"rate\":3.5}"), 0);

        verifyNoInteractions(eventBridgeService, sqsService, snsService);
        verify(lambdaService).findEventInvokeConfig(fn);
    }

    @Test
    void eventInvokeConfigWithoutDestinations_deliversNothing() {
        FunctionEventInvokeConfig config = new FunctionEventInvokeConfig();
        config.setMaximumRetryAttempts(0);
        when(lambdaService.findEventInvokeConfig(fn)).thenReturn(Optional.of(config));

        router.route(fn, request(), success("{\"rate\":3.5}"), 0);

        verifyNoInteractions(eventBridgeService, sqsService, snsService);
    }

    @Test
    void unsupportedDestinationArn_deliversNothing() {
        configure("arn:aws:states:us-east-1:000000000000:stateMachine:quotes", null);

        router.route(fn, request(), success("{\"rate\":3.5}"), 0);

        verifyNoInteractions(eventBridgeService, sqsService, snsService);
    }

    @Test
    void destinationThatRejectsTheRecord_doesNotReachTheCaller() {
        configure(QUEUE_ARN, null);
        when(sqsService.sendMessage(anyString(), anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("The specified queue does not exist."));

        assertDoesNotThrow(() -> router.route(fn, request(), success("{\"rate\":3.5}"), 0));
    }

    @Test
    void publishedVersion_recordsTheExecutedVersion() {
        fn.setFunctionArn(FUNCTION_ARN + ":3");
        fn.setVersion("3");
        configure(BUS_ARN, null);

        router.route(fn, request(), success("{\"rate\":3.5}"), 0);

        JsonNode detail = detailOf(capturedEventEntry());
        assertEquals(FUNCTION_ARN + ":3", detail.path("requestContext").path("functionArn").asText());
        assertEquals("3", detail.path("responseContext").path("executedVersion").asText());
    }

    @Test
    void realAliasConfigDeliversToAliasBusAndFallsBackToVersionBus() {
        String versionBusArn = "arn:aws:events:us-east-1:000000000000:event-bus/version-bus";
        LambdaService realService = new LambdaService(
                new LambdaFunctionStore(new InMemoryStorage<>()),
                new WarmPool(),
                new CodeStore(Path.of("target/test-data/lambda-code")),
                new ZipExtractor(),
                new RegionResolver("us-east-1", "000000000000"));
        realService.createFunction("us-east-1", Map.of(
                "FunctionName", "bank-pawnshop",
                "PackageType", "Image",
                "Role", "arn:aws:iam::000000000000:role/test-role",
                "Code", Map.of("ImageUri", "public.ecr.aws/lambda/nodejs:20")));
        LambdaFunction version = realService.publishVersion("us-east-1", "bank-pawnshop", null);
        realService.putEventInvokeConfig("us-east-1", "bank-pawnshop", version.getVersion(), Map.of(
                "DestinationConfig", Map.of("OnSuccess", Map.of("Destination", versionBusArn))));
        realService.putEventInvokeConfig("us-east-1", "bank-pawnshop", "prod", Map.of(
                "DestinationConfig", Map.of("OnSuccess", Map.of("Destination", BUS_ARN))));
        AsyncInvokeDestinationRouter realRouter = new AsyncInvokeDestinationRouter(
                instanceOf(realService), instanceOf(eventBridgeService), instanceOf(sqsService),
                instanceOf(snsService), MAPPER, config);

        realRouter.route(version, request(), success("{}"), 0, "prod");
        realRouter.route(version, request(), success("{}"), 0, "other");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> entries = ArgumentCaptor.forClass(List.class);
        verify(eventBridgeService, times(2)).putEvents(entries.capture(), eq("us-east-1"), eq(null));
        JsonNode aliasEntry = MAPPER.valueToTree(entries.getAllValues().get(0).get(0));
        JsonNode versionEntry = MAPPER.valueToTree(entries.getAllValues().get(1).get(0));
        assertEquals(BUS_ARN, aliasEntry.path("EventBusName").asText());
        assertEquals(versionBusArn, versionEntry.path("EventBusName").asText());
        assertEquals(version.getFunctionArn(), detailOf(aliasEntry)
                .path("requestContext").path("functionArn").asText());
        assertEquals(version.getVersion(), detailOf(aliasEntry)
                .path("responseContext").path("executedVersion").asText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedAsyncInvocation_sendsTheOriginalEventToDeadLetterQueue() {
        fn.setDeadLetterTargetArn(QUEUE_ARN);

        router.route(fn, request(), failure("Unhandled", "{\"errorMessage\":\"always fails\"}"), 0);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, MessageAttributeValue>> attrs = ArgumentCaptor.forClass(Map.class);
        String queueUrl = "http://localhost:4566/000000000000/quotes-queue";
        verify(sqsService).sendMessage(eq(queueUrl), body.capture(), anyInt(), eq(null), eq(null), attrs.capture(), eq("us-east-1"));
        assertThat(body.getValue(), containsString("\"amount\":100000"));
        assertThat(attrs.getValue().get("RequestID").getStringValue(), equalTo("req-1"));
        assertThat(attrs.getValue().get("ErrorCode").getDataType(), equalTo("Number"));
        assertThat(attrs.getValue().get("ErrorCode").getStringValue(), equalTo("200"));
        assertThat(attrs.getValue().get("ErrorMessage").getStringValue(), equalTo("always fails"));
    }

    @Test
    void eventAgeExpiration_usesRetriesExhaustedConditionWhenAwsBehaviorIsUnverified() {
        configure(null, BUS_ARN);

        router.route(fn, request(), failure("Unhandled", "{\"errorMessage\":\"Event age exceeded\"}"), 3, 0);

        JsonNode detail = detailOf(capturedEventEntry());
        assertEquals("RetriesExhausted", detail.path("requestContext").path("condition").asText());
    }

    @Test
    void succeedingAsyncInvocation_sendsNothingToDeadLetterQueue() {
        fn.setDeadLetterTargetArn(QUEUE_ARN);

        router.route(fn, request(), success("{\"ok\":true}"), 0);

        verify(sqsService, never()).sendMessage(anyString(), anyString(), anyInt(), any(), any(), any(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deadLetterTargetMayBeAnSnsTopic() {
        fn.setDeadLetterTargetArn(TOPIC_ARN);

        router.route(fn, request(), failure("Unhandled", "{\"errorMessage\":\"always fails\"}"), 0);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, MessageAttributeValue>> attrs = ArgumentCaptor.forClass(Map.class);
        verify(snsService).publish(eq(TOPIC_ARN), eq(null), eq(null), body.capture(), eq("Lambda"), attrs.capture(), eq("us-east-1"));
        assertThat(body.getValue(), containsString("\"amount\":100000"));
        assertThat(attrs.getValue().get("RequestID").getStringValue(), equalTo("req-1"));
        assertThat(attrs.getValue().get("ErrorCode").getDataType(), equalTo("Number"));
        assertThat(attrs.getValue().get("ErrorCode").getStringValue(), equalTo("200"));
        assertThat(attrs.getValue().get("ErrorMessage").getStringValue(), equalTo("always fails"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deadLetterQueue_truncatesErrorMessageTo1Kb() {
        fn.setDeadLetterTargetArn(QUEUE_ARN);
        String longError = "x".repeat(2000);

        router.route(fn, request(), failure("Unhandled", "{\"errorMessage\":\"" + longError + "\"}"), 0);

        ArgumentCaptor<Map<String, MessageAttributeValue>> attrs = ArgumentCaptor.forClass(Map.class);
        String queueUrl = "http://localhost:4566/000000000000/quotes-queue";
        verify(sqsService).sendMessage(eq(queueUrl), anyString(), anyInt(), eq(null), eq(null), attrs.capture(), eq("us-east-1"));
        assertEquals(1024, attrs.getValue().get("ErrorMessage").getStringValue().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void whenBothDestinationAndDeadLetterConfigAreConfigured_destinationTakesPrecedence() {
        fn.setDeadLetterTargetArn(QUEUE_ARN);
        configure(null, TOPIC_ARN);

        router.route(fn, request(), failure("Unhandled", "{\"errorMessage\":\"always fails\"}"), 0);

        verify(snsService).publish(eq(TOPIC_ARN), eq(null), anyString(), eq("Lambda"), eq("us-east-1"));
        String queueUrl = "http://localhost:4566/000000000000/quotes-queue";
        verify(sqsService, never()).sendMessage(eq(queueUrl), anyString(), anyInt(), any(), any(), any(), anyString());
    }

    private void configure(String onSuccess, String onFailure) {
        FunctionEventInvokeConfig config = new FunctionEventInvokeConfig();
        FunctionEventInvokeConfig.DestinationConfig destinations =
                new FunctionEventInvokeConfig.DestinationConfig();
        if (onSuccess != null) {
            destinations.setOnSuccess(new FunctionEventInvokeConfig.Destination(onSuccess));
        }
        if (onFailure != null) {
            destinations.setOnFailure(new FunctionEventInvokeConfig.Destination(onFailure));
        }
        config.setDestinationConfig(destinations);
        when(lambdaService.findEventInvokeConfig(fn)).thenReturn(Optional.of(config));
    }

    private JsonNode capturedEventEntry() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> entries = ArgumentCaptor.forClass(List.class);
        verify(eventBridgeService).putEvents(entries.capture(), eq("us-east-1"), eq(null));
        assertEquals(1, entries.getValue().size(), "one entry per delivery");
        return MAPPER.valueToTree(entries.getValue().get(0));
    }

    private static JsonNode detailOf(JsonNode entry) {
        return read(entry.get("Detail").asText());
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("destination record is not JSON: " + json, e);
        }
    }

    private static byte[] request() {
        return "{\"bankId\":\"BANK-PawnShop\",\"amount\":100000}".getBytes();
    }

    private static InvokeResult success(String payload) {
        return new InvokeResult(200, null, payload.getBytes(), null, "req-1");
    }

    private static InvokeResult failure(String functionError, String payload) {
        return new InvokeResult(200, functionError, payload.getBytes(), null, "req-1");
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instanceOf(T bean) {
        Instance<T> instance = mock(Instance.class);
        when(instance.get()).thenReturn(bean);
        return instance;
    }
}
