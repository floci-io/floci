package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.kinesis.model.KinesisRecord;
import io.github.hectorvent.floci.services.kinesis.model.KinesisShard;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.pipes.PipesFilterMatcher;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Fills the poller-test gap for Kinesis and covers FilterCriteria enforcement: the checkpoint must advance
 * past filtered-out records (never wedge the shard), delivery stays base64, and the Kinesis filter dialect is
 * top-level decoded {@code data}, not the {@code {"kinesis":{"data":...}}} envelope.
 *
 * <p>Async note: {@code pollAndInvoke} submits to a background executor. Assertions wait on a positive terminal
 * signal via Mockito {@code timeout(...)}: {@code esmStore.saveForAccount} for a checkpoint advance, or the
 * invoke itself. {@code never()} assertions target actions the exercised code path never performs on any thread
 * (invoke in a fully-filtered path; save in an invoke-error path), so they are timing-independent.
 * Tests that drive several polls in a row wait for each one with {@link #pollOnce}, and move the injected
 * clock past the retry backoff themselves.
 */
class KinesisEventSourcePollerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String STREAM_ARN = "arn:aws:kinesis:us-east-1:000000000000:stream/s";
    private static final String SHARD = "shardId-000000000000";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:fn";
    private static final String QUEUE_ARN = "arn:aws:sqs:us-east-1:000000000000:k-dlq";
    private static final String QUEUE_URL = "http://localhost:4566/000000000000/k-dlq";
    private static final String TOPIC_ARN = "arn:aws:sns:us-east-1:000000000000:k-failures";
    private static final String BUCKET_ARN = "arn:aws:s3:::k-failures";
    /** The first record of {@link #stubShard}; each later record arrives one second after the one before. */
    private static final Instant FIRST_ARRIVAL = Instant.parse("2026-10-20T12:07:26.012Z");

    private KinesisEventSourcePoller poller;
    private KinesisService kinesisService;
    private LambdaExecutorService executorService;
    private LambdaFunctionStore functionStore;
    private LambdaAliasStore aliasStore;
    private EsmStore esmStore;
    private SqsService sqsService;
    private SnsService snsService;
    private S3Service s3Service;
    private final AtomicLong clock = new AtomicLong();
    /** What the store holds for the mapping while a poll runs; a test changes it to delete or disable it. */
    private final AtomicReference<Optional<EventSourceMapping>> storedMapping = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        clock.set(FIRST_ARRIVAL.plusSeconds(30).toEpochMilli());
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambdaConfig = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambdaConfig);
        when(lambdaConfig.pollIntervalMs()).thenReturn(1000L);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        kinesisService = mock(KinesisService.class);
        executorService = mock(LambdaExecutorService.class);
        functionStore = mock(LambdaFunctionStore.class);
        aliasStore = mock(LambdaAliasStore.class);
        esmStore = mock(EsmStore.class);
        storedMapping.set(Optional.of(storedAs(true)));
        when(esmStore.getForAccount(anyString(), anyString())).thenAnswer(inv -> storedMapping.get());
        sqsService = mock(SqsService.class);
        snsService = mock(SnsService.class);
        s3Service = mock(S3Service.class);

        poller = new KinesisEventSourcePoller(mock(Vertx.class), kinesisService, executorService,
                new LambdaTargetResolver(functionStore, aliasStore), esmStore, config, MAPPER,
                new PipesFilterMatcher(MAPPER), new StreamBatchFailures(sqsService, snsService, s3Service, MAPPER, config),
                clock::get);
    }

    private EventSourceMapping esm(String... patterns) {
        EventSourceMapping esm = new EventSourceMapping();
        esm.setUuid("k-esm");
        esm.setAccountId(ACCOUNT);
        esm.setRegion(REGION);
        esm.setFunctionName("fn");
        esm.setEventSourceArn(STREAM_ARN);
        esm.setBatchSize(10);
        esm.setEnabled(true);
        if (patterns.length > 0) {
            EventSourceMapping.FilterCriteria fc = new EventSourceMapping.FilterCriteria();
            List<EventSourceMapping.Filter> filters = new ArrayList<>();
            for (String p : patterns) {
                EventSourceMapping.Filter f = new EventSourceMapping.Filter();
                f.setPattern(p);
                filters.add(f);
            }
            fc.setFilters(filters);
            esm.setFilterCriteria(fc);
        }
        return esm;
    }

    private KinesisRecord record(String seq, String partitionKey, String data) {
        return new KinesisRecord(data.getBytes(StandardCharsets.UTF_8), partitionKey, seq,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private void stubStreamWith(List<KinesisRecord> records) {
        KinesisStream stream = new KinesisStream("s", STREAM_ARN);
        stream.setShards(List.of(new KinesisShard(SHARD, "0", "1", "0")));
        when(kinesisService.describeStreamForAccount(eq(ACCOUNT), anyString(), eq(REGION))).thenReturn(stream);
        when(kinesisService.getShardIteratorForAccount(eq(ACCOUNT), anyString(), eq(SHARD), anyString(), any(), eq(REGION)))
                .thenReturn("iter-0");
        when(kinesisService.getRecordsForAccount(eq(ACCOUNT), eq("iter-0"), anyInt(), eq(REGION)))
                .thenReturn(Map.of("Records", records));
    }

    private void stubFunction() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("fn");
        when(functionStore.getForAccount(ACCOUNT, REGION, "fn")).thenReturn(Optional.of(fn));
    }

    private JsonNode deliveredRecords(byte[] payload) {
        try {
            return MAPPER.readTree(payload).path("Records");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Hard happens-after barrier: re-kick the poll until a SECOND fetch is observed. Because {@code activePolls}
     * serializes polls per ESM, fetch #2 cannot begin until poll #1's finally-block ran, so poll #1, including its
     * invoke/checkpoint decision, is fully complete. Negative assertions after this are race-free rather than
     * relying on structural unreachability alone. (A kick while poll #1 is still in flight is a no-op, so we retry.)
     */
    private void awaitPollCompletedViaSecondFetch(EventSourceMapping esm) {
        long deadline = System.currentTimeMillis() + 5000;
        while (true) {
            poller.pollAndInvoke(esm);
            try {
                verify(kinesisService, atLeast(2)).getRecordsForAccount(eq(ACCOUNT), eq("iter-0"), anyInt(), eq(REGION));
                return;
            } catch (AssertionError retry) {
                if (System.currentTimeMillis() > deadline) {
                    throw retry;
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw retry;
                }
            }
        }
    }

    @Test
    void versionQualifiedMappingInvokesThatVersion() {
        stubFunction();
        LambdaFunction version1 = stubVersion("1");
        stubStreamWith(List.of(record("s1", "p1", "{}")));
        when(executorService.invoke(any(), any(), eq(InvocationType.RequestResponse), eq(0), eq("1"))).thenReturn(new InvokeResult());
        EventSourceMapping esm = esm();
        esm.setFunctionArn("arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:fn:1");

        poller.pollAndInvoke(esm);

        verify(executorService, timeout(2000)).invoke(eq(version1), any(), eq(InvocationType.RequestResponse), eq(0), eq("1"));
    }

    @Test
    void aliasQualifiedMappingInvokesTheAliasVersion() {
        stubFunction();
        LambdaFunction version2 = stubVersion("2");
        LambdaAlias alias = new LambdaAlias();
        alias.setName("live");
        alias.setFunctionName("fn");
        alias.setFunctionVersion("2");
        when(aliasStore.getForAccount(ACCOUNT, REGION, "fn", "live")).thenReturn(Optional.of(alias));
        stubStreamWith(List.of(record("s1", "p1", "{}")));
        when(executorService.invoke(any(), any(), eq(InvocationType.RequestResponse), eq(0), eq("live"))).thenReturn(new InvokeResult());
        EventSourceMapping esm = esm();
        esm.setFunctionArn("arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:fn:live");

        poller.pollAndInvoke(esm);

        verify(executorService, timeout(2000)).invoke(eq(version2), any(), eq(InvocationType.RequestResponse), eq(0), eq("live"));
    }

    private LambdaFunction stubVersion(String version) {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("fn");
        fn.setVersion(version);
        when(functionStore.getForAccount(ACCOUNT, REGION, "fn", version)).thenReturn(Optional.of(fn));
        return fn;
    }

    @Test
    void noFilterInvokesAllRecordsAndCheckpointsNewest() {
        stubFunction();
        stubStreamWith(List.of(record("s1", "p1", "{\"type\":\"order\"}"),
                record("s2", "p2", "{\"type\":\"refund\"}")));
        when(executorService.invoke(any(), any(), eq(InvocationType.RequestResponse))).thenReturn(new InvokeResult());
        EventSourceMapping esm = esm();

        poller.pollAndInvoke(esm);

        verify(esmStore, timeout(2000)).saveForAccount(eq(ACCOUNT), any());
        assertEquals("s2", esm.getShardSequenceNumbers().get(SHARD));
        verify(executorService, timeout(2000)).invoke(any(), any(), eq(InvocationType.RequestResponse));
    }

    @Test
    void filterDeliversOnlyMatchingRecordsKeepsDataBase64AndCheckpointsNewestFetched() {
        stubFunction();
        // order (matches) then refund (does not): refund is the newest FETCHED record.
        stubStreamWith(List.of(record("s1", "p1", "{\"type\":\"order\"}"),
                record("s2", "p2", "{\"type\":\"refund\"}")));
        when(executorService.invoke(any(), any(), eq(InvocationType.RequestResponse))).thenReturn(new InvokeResult());
        EventSourceMapping esm = esm("{\"data\":{\"type\":[\"order\"]}}");

        poller.pollAndInvoke(esm);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(executorService, timeout(2000)).invoke(any(), payload.capture(), eq(InvocationType.RequestResponse));
        JsonNode records = deliveredRecords(payload.getValue());
        assertEquals(1, records.size());
        assertEquals("s1", records.get(0).path("kinesis").path("sequenceNumber").asText());
        // Delivery keeps data base64-encoded even though filtering ran on the decoded copy.
        String base64 = records.get(0).path("kinesis").path("data").asText();
        assertEquals("{\"type\":\"order\"}",
                new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8));
        // Checkpoint advances to the newest FETCHED seq (s2), consuming the trailing filtered-out record.
        verify(esmStore, timeout(2000)).saveForAccount(eq(ACCOUNT), any());
        assertEquals("s2", esm.getShardSequenceNumbers().get(SHARD));
    }

    @Test
    void fullyFilteredBatchAdvancesCheckpointWithoutInvoking() {
        stubFunction();
        stubStreamWith(List.of(record("s1", "p1", "{\"type\":\"refund\"}"),
                record("s2", "p2", "{\"type\":\"chargeback\"}")));
        EventSourceMapping esm = esm("{\"data\":{\"type\":[\"order\"]}}");

        poller.pollAndInvoke(esm);

        verify(esmStore, timeout(2000)).saveForAccount(eq(ACCOUNT), any());
        awaitPollCompletedViaSecondFetch(esm);
        assertEquals("s2", esm.getShardSequenceNumbers().get(SHARD));
        verify(executorService, never()).invoke(any(), any(), any());
    }

    @Test
    void nonJsonDataDroppedByObjectPattern() {
        stubFunction();
        stubStreamWith(List.of(record("s1", "p1", "not json at all"),
                record("s2", "p2", "{\"type\":\"order\"}")));
        when(executorService.invoke(any(), any(), eq(InvocationType.RequestResponse))).thenReturn(new InvokeResult());
        EventSourceMapping esm = esm("{\"data\":{\"type\":[\"order\"]}}");

        poller.pollAndInvoke(esm);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(executorService, timeout(2000)).invoke(any(), payload.capture(), eq(InvocationType.RequestResponse));
        JsonNode records = deliveredRecords(payload.getValue());
        assertEquals(1, records.size());
        assertEquals("s2", records.get(0).path("kinesis").path("sequenceNumber").asText());
    }

    @Test
    void partitionKeyMetadataPatternMatches() {
        stubFunction();
        stubStreamWith(List.of(record("s1", "pk-1", "{}"), record("s2", "pk-2", "{}")));
        when(executorService.invoke(any(), any(), eq(InvocationType.RequestResponse))).thenReturn(new InvokeResult());
        EventSourceMapping esm = esm("{\"partitionKey\":[\"pk-2\"]}");

        poller.pollAndInvoke(esm);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(executorService, timeout(2000)).invoke(any(), payload.capture(), eq(InvocationType.RequestResponse));
        JsonNode records = deliveredRecords(payload.getValue());
        assertEquals(1, records.size());
        assertEquals("s2", records.get(0).path("kinesis").path("sequenceNumber").asText());
    }

    @Test
    void nestedKinesisDataPatternMatchesNothingDialectPin() {
        stubFunction();
        stubStreamWith(List.of(record("s1", "p1", "{\"type\":\"order\"}")));
        // The invocation-envelope dialect {"kinesis":{"data":...}} must NOT match: AWS filters top-level data.
        EventSourceMapping esm = esm("{\"kinesis\":{\"data\":{\"type\":[\"order\"]}}}");

        poller.pollAndInvoke(esm);

        verify(esmStore, timeout(2000)).saveForAccount(eq(ACCOUNT), any());
        awaitPollCompletedViaSecondFetch(esm);
        assertEquals("s1", esm.getShardSequenceNumbers().get(SHARD));
        verify(executorService, never()).invoke(any(), any(), any());
    }

    @Test
    void invokeErrorDoesNotAdvanceCheckpointAndRetriesSameWindow() throws Exception {
        stubFunction();
        stubStreamWith(List.of(record("s1", "p1", "{\"type\":\"order\"}")));
        InvokeResult err = new InvokeResult();
        err.setFunctionError("Unhandled");
        when(executorService.invoke(any(), any(), eq(InvocationType.RequestResponse))).thenReturn(err);
        EventSourceMapping esm = esm("{\"data\":{\"type\":[\"order\"]}}");

        pollOnce(esm);
        clock.addAndGet(poller.retryBackoffMs(1));
        pollOnce(esm);

        verify(esmStore, never()).saveForAccount(eq(ACCOUNT), any());
        assertNull(esm.getShardSequenceNumbers().get(SHARD));
        // retry-from-old-checkpoint: both invokes carried the same fetched window.
        ArgumentCaptor<byte[]> cap = ArgumentCaptor.forClass(byte[].class);
        verify(executorService, times(2)).invoke(any(), cap.capture(), eq(InvocationType.RequestResponse));
        List<byte[]> payloads = cap.getAllValues();
        assertEquals(new String(payloads.get(0), StandardCharsets.UTF_8),
                new String(payloads.get(1), StandardCharsets.UTF_8));
    }

    @Test
    void throttleDoesNotAdvanceCheckpointAndRetriesSameWindow() {
        stubFunction();
        stubStreamWith(List.of(record("s1", "p1", "{\"type\":\"order\"}"),
                record("s2", "p2", "{\"type\":\"refund\"}")));
        // partial match (order) → invoke attempted → throttled → checkpoint must not advance
        when(executorService.invoke(any(), any(), eq(InvocationType.RequestResponse)))
                .thenThrow(new AwsException("TooManyRequestsException", "throttled", 429));
        EventSourceMapping esm = esm("{\"data\":{\"type\":[\"order\"]}}");

        poller.pollAndInvoke(esm);
        verify(executorService, timeout(2000)).invoke(any(), any(), eq(InvocationType.RequestResponse));
        awaitPollCompletedViaSecondFetch(esm);

        verify(esmStore, never()).saveForAccount(eq(ACCOUNT), any());
        assertNull(esm.getShardSequenceNumbers().get(SHARD));
    }

    private static InvokeResult batchItemFailures(String json) {
        InvokeResult result = new InvokeResult();
        result.setPayload(json.getBytes(StandardCharsets.UTF_8));
        return result;
    }

    private EventSourceMapping reportingEsm() {
        EventSourceMapping esm = esm();
        esm.setFunctionResponseTypes(List.of("ReportBatchItemFailures"));
        return esm;
    }

    @Test
    void reportedFailureCheckpointsBeforeTheLowestFailedRecord() {
        stubFunction();
        stubStreamWith(List.of(record("s1", "p1", "{}"), record("s2", "p2", "{}"), record("s3", "p3", "{}")));
        when(executorService.invoke(any(), any(), eq(InvocationType.RequestResponse))).thenReturn(
                batchItemFailures("{\"batchItemFailures\":[{\"itemIdentifier\":\"s3\"},{\"itemIdentifier\":\"s2\"}]}"));
        EventSourceMapping esm = reportingEsm();

        poller.pollAndInvoke(esm);

        verify(esmStore, timeout(2000)).saveForAccount(eq(ACCOUNT), any());
        assertEquals("s1", esm.getShardSequenceNumbers().get(SHARD));
    }

    @Test
    void reportedFailureOnTheFirstRecordRetriesTheWholeBatch() throws Exception {
        stubShard("s1", "s2");
        List<byte[]> events = recordInvocations(seqs -> batchItemFailures(
                "{\"batchItemFailures\":[{\"itemIdentifier\":\"s1\"}]}"));
        EventSourceMapping esm = reportingEsm();

        pollOnce(esm);
        clock.addAndGet(poller.retryBackoffMs(1));
        pollOnce(esm);

        assertEquals(List.of(List.of("s1", "s2"), List.of("s1", "s2")), invokedSequences(events));
        verify(esmStore, never()).saveForAccount(eq(ACCOUNT), any());
        assertNull(esm.getShardSequenceNumbers().get(SHARD));
    }

    @Test
    void unknownItemIdentifierRetriesTheWholeBatch() throws Exception {
        stubShard("s1", "s2");
        List<byte[]> events = recordInvocations(seqs -> batchItemFailures(
                "{\"batchItemFailures\":[{\"itemIdentifier\":\"nope\"}]}"));
        EventSourceMapping esm = reportingEsm();

        pollOnce(esm);
        clock.addAndGet(poller.retryBackoffMs(1));
        pollOnce(esm);

        assertEquals(List.of(List.of("s1", "s2"), List.of("s1", "s2")), invokedSequences(events));
        verify(esmStore, never()).saveForAccount(eq(ACCOUNT), any());
        assertNull(esm.getShardSequenceNumbers().get(SHARD));
    }

    @Test
    void emptyBatchItemFailuresCheckpointsTheWholeBatch() {
        stubFunction();
        stubStreamWith(List.of(record("s1", "p1", "{}"), record("s2", "p2", "{}")));
        when(executorService.invoke(any(), any(), eq(InvocationType.RequestResponse)))
                .thenReturn(batchItemFailures("{\"batchItemFailures\":[]}"));
        EventSourceMapping esm = reportingEsm();

        poller.pollAndInvoke(esm);

        verify(esmStore, timeout(2000)).saveForAccount(eq(ACCOUNT), any());
        assertEquals("s2", esm.getShardSequenceNumbers().get(SHARD));
    }

    @Test
    void batchItemFailuresAreIgnoredWithoutReportBatchItemFailures() {
        stubFunction();
        stubStreamWith(List.of(record("s1", "p1", "{}"), record("s2", "p2", "{}")));
        when(executorService.invoke(any(), any(), eq(InvocationType.RequestResponse))).thenReturn(
                batchItemFailures("{\"batchItemFailures\":[{\"itemIdentifier\":\"s1\"}]}"));
        EventSourceMapping esm = esm();

        poller.pollAndInvoke(esm);

        verify(esmStore, timeout(2000)).saveForAccount(eq(ACCOUNT), any());
        assertEquals("s2", esm.getShardSequenceNumbers().get(SHARD));
    }

    /**
     * Regression for the account leak: a non-default ESM must read its stream through the
     * account-aware Kinesis overloads. The old request-context calls resolved against the ambient
     * request account, which defaults outside a request, so a same-named stream in the default
     * account could be read instead.
     */
    @Test
    void readsTheStreamInTheEventSourceMappingsAccountNotTheRequestAccount() {
        String esmAccount = "111111111111";
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("fn");
        when(functionStore.getForAccount(esmAccount, REGION, "fn")).thenReturn(Optional.of(fn));

        KinesisStream stream = new KinesisStream("s", STREAM_ARN);
        stream.setShards(List.of(new KinesisShard(SHARD, "0", "1", "0")));
        when(kinesisService.describeStreamForAccount(eq(esmAccount), anyString(), eq(REGION))).thenReturn(stream);
        when(kinesisService.getShardIteratorForAccount(eq(esmAccount), anyString(), eq(SHARD), anyString(), any(), eq(REGION)))
                .thenReturn("iter-0");
        when(kinesisService.getRecordsForAccount(eq(esmAccount), eq("iter-0"), anyInt(), eq(REGION)))
                .thenReturn(Map.of("Records", List.of(record("s1", "p1", "{\"type\":\"order\"}"))));
        when(executorService.invoke(any(), any(), eq(InvocationType.RequestResponse))).thenReturn(new InvokeResult());

        EventSourceMapping esm = esm();
        esm.setAccountId(esmAccount);

        poller.pollAndInvoke(esm);

        verify(executorService, timeout(2000)).invoke(any(), any(), eq(InvocationType.RequestResponse));
        verify(esmStore, timeout(2000)).saveForAccount(eq(esmAccount), any());
        verify(kinesisService, never()).describeStream(anyString(), anyString());
        verify(kinesisService, never()).getShardIterator(anyString(), anyString(), anyString(), any(), anyString());
        verify(kinesisService, never()).getRecords(anyString(), anyInt(), anyString());
    }

    @Test
    void streamNameComesFromAStreamOrAConsumerArn() {
        assertEquals("orders", KinesisEventSourcePoller.streamNameFromArn(
                "arn:aws:kinesis:us-east-1:000000000000:stream/orders"));
        assertEquals("orders", KinesisEventSourcePoller.streamNameFromArn(
                "arn:aws:kinesis:us-east-1:000000000000:stream/orders/consumer/reader:1700000000"));
        assertEquals("orders/not-a-consumer", KinesisEventSourcePoller.streamNameFromArn(
                "arn:aws:kinesis:us-east-1:000000000000:stream/orders/not-a-consumer"));
    }

    // ─────────── MaximumRetryAttempts, MaximumRecordAgeInSeconds and DestinationConfig.OnFailure ───────────

    @Test
    void maximumRetryAttemptsAllowsExactlyNPlusOneInvocationsThenDiscardsOnceAndMovesOn() throws Exception {
        List<KinesisRecord> shard = stubShard("s1");
        List<byte[]> events = failInvocationsContaining("s1");
        EventSourceMapping esm = esmWithOnFailure(10, QUEUE_ARN);

        for (int invocation = 1; invocation <= 10; invocation++) {
            pollOnce(esm);
            assertEquals(invocation, events.size(), "invocations after poll " + invocation);
            verify(sqsService, never()).sendMessage(anyString(), anyString(), anyInt(), anyString());
            assertNull(checkpoint(esm), "a batch with retries left must not be checkpointed");
            clock.addAndGet(StreamBatchFailures.MAX_RETRY_BACKOFF_MS);
        }
        pollOnce(esm);

        assertEquals(11, events.size());
        verify(sqsService, times(1)).sendMessage(eq(QUEUE_URL), anyString(), eq(0), eq(REGION));
        assertEquals("s1", checkpoint(esm), "the discarded batch must be checkpointed");

        shard.add(record("s2", FIRST_ARRIVAL.plusSeconds(1)));
        // No clock move: the discarded batch's backoff must not hold back the next records.
        pollOnce(esm);

        assertEquals(12, events.size());
        assertEquals(List.of("s2"), sequences(events.get(11)));
        assertEquals("s2", checkpoint(esm));
        verify(sqsService, times(1)).sendMessage(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void zeroMaximumRetryAttemptsDiscardsAfterOneInvocationAndSendsTheAwsRecordToSqs() throws Exception {
        stubShard("s1");
        List<byte[]> events = failInvocationsContaining("s1");

        EventSourceMapping esm = esmWithOnFailure(0, QUEUE_ARN);
        pollOnce(esm);

        assertEquals(1, events.size());
        assertEquals("s1", checkpoint(esm));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sqsService, times(1)).sendMessage(eq(QUEUE_URL), body.capture(), eq(0), eq(REGION));
        JsonNode record = MAPPER.readTree(body.getValue());
        assertEquals(List.of("requestContext", "responseContext", "version", "timestamp", "KinesisBatchInfo"),
                fieldNames(record), "SQS gets the metadata only, no payload: " + record);
        assertEquals("req-1", record.at("/requestContext/requestId").asText());
        assertEquals(FUNCTION_ARN, record.at("/requestContext/functionArn").asText());
        assertEquals("RetryAttemptsExhausted", record.at("/requestContext/condition").asText());
        assertEquals(1, record.at("/requestContext/approximateInvokeCount").asInt());
        assertEquals(200, record.at("/responseContext/statusCode").asInt());
        assertEquals("$LATEST", record.at("/responseContext/executedVersion").asText());
        assertEquals("Unhandled", record.at("/responseContext/functionError").asText());
        assertEquals("1.0", record.path("version").asText());
        assertTrue(record.path("timestamp").asText().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"),
                "timestamp must be ISO-8601 with milliseconds: " + record.path("timestamp"));
        assertBatchInfo(record, "s1", "s1", "2026-10-20T12:07:26.012Z", "2026-10-20T12:07:26.012Z", 1);
    }

    @Test
    void snsOnFailureDestinationGetsTheRecordWithoutPayload() throws Exception {
        stubShard("s1");
        failInvocationsContaining("s1");

        pollOnce(esmWithOnFailure(0, TOPIC_ARN));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(snsService, times(1)).publish(eq(TOPIC_ARN), isNull(), message.capture(), eq("ESM OnFailure"), eq(REGION));
        JsonNode record = MAPPER.readTree(message.getValue());
        assertFalse(record.has("payload"), "SNS gets the metadata only: " + record);
        assertEquals("RetryAttemptsExhausted", record.at("/requestContext/condition").asText());
        assertBatchInfo(record, "s1", "s1", "2026-10-20T12:07:26.012Z", "2026-10-20T12:07:26.012Z", 1);
        verifyNoInteractions(sqsService, s3Service);
    }

    @Test
    void enhancedFanOutMappingReportsTheStreamArnNotTheConsumerArn() throws Exception {
        stubShard("s1");
        failInvocationsContaining("s1");
        EventSourceMapping esm = esmWithOnFailure(0, QUEUE_ARN);
        esm.setEventSourceArn(STREAM_ARN + "/consumer/reader:1700000000");

        pollOnce(esm);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sqsService, times(1)).sendMessage(eq(QUEUE_URL), body.capture(), eq(0), eq(REGION));
        assertEquals(STREAM_ARN, MAPPER.readTree(body.getValue()).at("/KinesisBatchInfo/streamArn").asText());
    }

    @Test
    void s3OnFailureDestinationGetsTheExactEventAsAPayloadString() throws Exception {
        stubShard("s1", "s2");
        List<byte[]> events = failInvocationsContaining("s1");
        EventSourceMapping esm = esmWithOnFailure(1, BUCKET_ARN);

        pollOnce(esm);
        clock.addAndGet(StreamBatchFailures.MAX_RETRY_BACKOFF_MS);
        pollOnce(esm);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service, times(1)).putObject(eq("k-failures"), key.capture(), body.capture(),
                eq("application/json"), eq(Map.of()));
        assertTrue(key.getValue().matches("aws/lambda/k-esm/shardId-000000000000/\\d{4}/\\d{2}/\\d{2}/"
                        + "\\d{4}-\\d{2}-\\d{2}T\\d{2}\\.\\d{2}\\.\\d{2}-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"),
                "unexpected S3 key " + key.getValue());
        JsonNode record = MAPPER.readTree(body.getValue());
        assertEquals(List.of("requestContext", "responseContext", "version", "payload", "timestamp", "KinesisBatchInfo"),
                fieldNames(record));
        assertEquals(2, record.at("/requestContext/approximateInvokeCount").asInt());
        assertTrue(record.path("payload").isTextual(), "payload must be a JSON string, not an object: " + record);
        assertEquals(MAPPER.readTree(events.get(1)), MAPPER.readTree(record.path("payload").asText()));
        assertBatchInfo(record, "s1", "s2", "2026-10-20T12:07:26.012Z", "2026-10-20T12:07:27.012Z", 2);
    }

    @Test
    void reportedBatchItemFailuresUseUpTheRetryAttempts() throws Exception {
        stubShard("s1", "s2", "s3");
        List<byte[]> events = recordInvocations(seqs -> batchItemFailures(
                "{\"batchItemFailures\":[{\"itemIdentifier\":\"s2\"}]}"));
        EventSourceMapping esm = esmWithOnFailure(1, QUEUE_ARN);
        esm.setFunctionResponseTypes(List.of("ReportBatchItemFailures"));

        pollOnce(esm);
        assertEquals("s1", checkpoint(esm), "the records before the lowest failure are done");
        verify(sqsService, never()).sendMessage(anyString(), anyString(), anyInt(), anyString());

        clock.addAndGet(StreamBatchFailures.MAX_RETRY_BACKOFF_MS);
        pollOnce(esm);

        assertEquals(List.of(List.of("s1", "s2", "s3"), List.of("s2", "s3")), invokedSequences(events));
        assertEquals("s3", checkpoint(esm));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sqsService, times(1)).sendMessage(eq(QUEUE_URL), body.capture(), eq(0), eq(REGION));
        JsonNode record = MAPPER.readTree(body.getValue());
        assertEquals("RetryAttemptsExhausted", record.at("/requestContext/condition").asText());
        assertEquals(2, record.at("/requestContext/approximateInvokeCount").asInt());
        assertEquals(200, record.at("/responseContext/statusCode").asInt());
        assertFalse(record.path("responseContext").has("functionError"),
                "a partial batch response is not a function error: " + record);
        assertBatchInfo(record, "s2", "s3", "2026-10-20T12:07:27.012Z", "2026-10-20T12:07:28.012Z", 2);
    }

    @Test
    void reportedBatchItemFailureWithZeroRetryAttemptsDiscardsTheRestOfTheBatchAtOnce() throws Exception {
        stubShard("s1", "s2", "s3");
        List<byte[]> events = recordInvocations(seqs -> batchItemFailures(
                "{\"batchItemFailures\":[{\"itemIdentifier\":\"s2\"}]}"));
        EventSourceMapping esm = esmWithOnFailure(0, QUEUE_ARN);
        esm.setFunctionResponseTypes(List.of("ReportBatchItemFailures"));

        pollOnce(esm);

        assertEquals(1, events.size());
        assertEquals("s3", checkpoint(esm));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sqsService, times(1)).sendMessage(eq(QUEUE_URL), body.capture(), eq(0), eq(REGION));
        JsonNode record = MAPPER.readTree(body.getValue());
        assertEquals(1, record.at("/requestContext/approximateInvokeCount").asInt());
        assertBatchInfo(record, "s2", "s3", "2026-10-20T12:07:27.012Z", "2026-10-20T12:07:28.012Z", 2);
    }

    @Test
    void batchOlderThanMaximumRecordAgeIsDiscardedWithTheAgeCondition() throws Exception {
        stubShard("s1");
        List<byte[]> events = failInvocationsContaining("s1");
        EventSourceMapping esm = esmWithOnFailure(null, QUEUE_ARN);
        esm.setMaximumRecordAgeInSeconds(60);

        clock.set(FIRST_ARRIVAL.plusSeconds(58).toEpochMilli());
        pollOnce(esm);
        assertNull(checkpoint(esm), "a 58 s old batch is within the 60 s maximum age");
        verify(sqsService, never()).sendMessage(anyString(), anyString(), anyInt(), anyString());

        clock.set(FIRST_ARRIVAL.plusSeconds(61).toEpochMilli());
        pollOnce(esm);

        assertEquals(2, events.size());
        assertEquals("s1", checkpoint(esm));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sqsService, times(1)).sendMessage(eq(QUEUE_URL), body.capture(), eq(0), eq(REGION));
        JsonNode record = MAPPER.readTree(body.getValue());
        assertEquals("MaximumRecordAgeExceeded", record.at("/requestContext/condition").asText());
        assertEquals(2, record.at("/requestContext/approximateInvokeCount").asInt());
    }

    /**
     * "BisectBatchOnFunctionError, which splits a failed batch into two smaller batches, isolating bad records
     * [...] Splitting batches doesn't consume the retry quota." With no retries left, only the bad record is
     * discarded; the records around it are delivered.
     */
    @Test
    void bisectingSplitsAFailedBatchUntilOnlyTheBadRecordIsDiscarded() throws Exception {
        stubShard("s1", "s2", "s3", "s4");
        List<byte[]> events = failInvocationsContaining("s3");
        EventSourceMapping esm = esmWithOnFailure(0, QUEUE_ARN);
        esm.setBisectBatchOnFunctionError(true);

        for (int poll = 0; poll < 5; poll++) {
            pollOnce(esm);
        }

        assertEquals(List.of(List.of("s1", "s2", "s3", "s4"), List.of("s1", "s2"), List.of("s3", "s4"),
                List.of("s3"), List.of("s4")), invokedSequences(events));
        assertEquals("s4", checkpoint(esm));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sqsService, times(1)).sendMessage(eq(QUEUE_URL), body.capture(), eq(0), eq(REGION));
        JsonNode record = MAPPER.readTree(body.getValue());
        assertEquals("RetryAttemptsExhausted", record.at("/requestContext/condition").asText());
        assertEquals(1, record.at("/requestContext/approximateInvokeCount").asInt());
        assertBatchInfo(record, "s3", "s3", "2026-10-20T12:07:28.012Z", "2026-10-20T12:07:28.012Z", 1);
    }

    @Test
    void aFailedBatchIsNotSplitWithoutBisectBatchOnFunctionError() throws Exception {
        stubShard("s1", "s2", "s3", "s4");
        List<byte[]> events = failInvocationsContaining("s3");
        EventSourceMapping esm = esmWithOnFailure(0, QUEUE_ARN);

        pollOnce(esm);

        assertEquals(List.of(List.of("s1", "s2", "s3", "s4")), invokedSequences(events));
        assertEquals("s4", checkpoint(esm), "the whole batch is discarded");
    }

    @Test
    void aBatchOlderThanMaximumRecordAgeIsDiscardedRatherThanSplit() throws Exception {
        stubShard("s1", "s2");
        List<byte[]> events = failInvocationsContaining("s2");
        EventSourceMapping esm = esmWithOnFailure(null, QUEUE_ARN);
        esm.setBisectBatchOnFunctionError(true);
        esm.setMaximumRecordAgeInSeconds(60);
        clock.set(FIRST_ARRIVAL.plusSeconds(61).toEpochMilli());

        pollOnce(esm);

        assertEquals(1, events.size());
        assertEquals("s2", checkpoint(esm));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sqsService, times(1)).sendMessage(eq(QUEUE_URL), body.capture(), eq(0), eq(REGION));
        assertEquals("MaximumRecordAgeExceeded",
                MAPPER.readTree(body.getValue()).at("/requestContext/condition").asText());
    }

    @Test
    void failedBatchIsNotRetriedBeforeItsDoublingBackoffElapses() throws Exception {
        stubShard("s1");
        List<byte[]> events = failInvocationsContaining("s1");
        EventSourceMapping esm = esmWithOnFailure(null, null);

        pollOnce(esm);
        clock.addAndGet(1_999);
        pollOnce(esm);
        assertEquals(1, events.size(), "retried before the first backoff (2 x the 1 s poll interval) elapsed");
        verify(kinesisService, times(1)).getRecordsForAccount(eq(ACCOUNT), anyString(), anyInt(), eq(REGION));
        clock.addAndGet(1);
        pollOnce(esm);
        assertEquals(2, events.size(), "not retried once the first backoff elapsed");

        clock.addAndGet(3_999);
        pollOnce(esm);
        assertEquals(2, events.size(), "retried before the second backoff (4 s) elapsed");
        clock.addAndGet(1);
        pollOnce(esm);
        assertEquals(3, events.size(), "not retried once the second backoff elapsed");
    }

    @Test
    void backoffStartsWhenTheFailedInvocationEnds() throws Exception {
        stubShard("s1");
        List<byte[]> events = recordInvocations(seqs -> {
            clock.addAndGet(10_000);
            return new InvokeResult(200, "Unhandled", new byte[0], null, "req-1");
        });
        EventSourceMapping esm = esmWithOnFailure(null, null);

        pollOnce(esm);
        clock.addAndGet(1_999);
        pollOnce(esm);
        assertEquals(1, events.size(), "a function that took 10 s to fail was retried before the 2 s backoff");
        clock.addAndGet(1);
        pollOnce(esm);
        assertEquals(2, events.size());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = -1)
    void unlimitedRetryAttemptsAndRecordAgeNeverDiscardAFailingBatch(Integer unlimited) throws Exception {
        stubShard("s1");
        List<byte[]> events = failInvocationsContaining("s1");
        EventSourceMapping esm = esmWithOnFailure(unlimited, QUEUE_ARN);
        esm.setMaximumRecordAgeInSeconds(unlimited);
        clock.set(FIRST_ARRIVAL.plus(Duration.ofDays(8)).toEpochMilli());

        for (int poll = 0; poll < 20; poll++) {
            pollOnce(esm);
            clock.addAndGet(StreamBatchFailures.MAX_RETRY_BACKOFF_MS);
        }

        assertEquals(20, events.size());
        assertNull(checkpoint(esm));
        verify(esmStore, never()).saveForAccount(anyString(), any());
        verifyNoInteractions(sqsService);
    }

    @Test
    void exhaustedBatchWithoutOnFailureDestinationIsStillDiscarded() throws Exception {
        List<KinesisRecord> shard = stubShard("s1");
        List<byte[]> events = failInvocationsContaining("s1");
        EventSourceMapping esm = esmWithOnFailure(0, null);

        pollOnce(esm);
        assertEquals("s1", checkpoint(esm));
        shard.add(record("s2", FIRST_ARRIVAL.plusSeconds(1)));
        pollOnce(esm);

        assertEquals(List.of(List.of("s1"), List.of("s2")), invokedSequences(events));
        assertEquals("s2", checkpoint(esm));
        verifyNoInteractions(sqsService, snsService, s3Service);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mappingDeletedOrDisabledDuringAFailedInvokeSendsNothingAndSavesNothing(boolean deleted) throws Exception {
        stubShard("s1");
        List<byte[]> events = recordInvocations(seqs -> {
            storedMapping.set(deleted ? Optional.empty() : Optional.of(storedAs(false)));
            return new InvokeResult(200, "Unhandled", new byte[0], null, "req-1");
        });
        EventSourceMapping esm = esmWithOnFailure(0, QUEUE_ARN);

        pollOnce(esm);

        assertEquals(1, events.size());
        verifyNoInteractions(sqsService);
        verify(esmStore, never()).saveForAccount(anyString(), any());
        assertNull(checkpoint(esm));
    }

    @Test
    void mappingDisabledDuringAFailedInvokeKeepsNoAttemptOrBackoffForItsReEnable() throws Exception {
        stubShard("s1");
        EventSourceMapping esm = esmWithOnFailure(1, QUEUE_ARN);
        AtomicBoolean disableDuringInvoke = new AtomicBoolean(true);
        List<byte[]> events = recordInvocations(seqs -> {
            if (disableDuringInvoke.getAndSet(false)) {
                storedMapping.set(Optional.of(storedAs(false)));
                poller.stopPolling(esm.getUuid());
            }
            return new InvokeResult(200, "Unhandled", new byte[0], null, "req-1");
        });

        pollOnce(esm);
        storedMapping.set(Optional.of(storedAs(true)));
        pollOnce(esm);

        assertEquals(2, events.size(), "the re-enabled mapping must not wait out a backoff of the disabled one");
        verifyNoInteractions(sqsService);
        assertNull(checkpoint(esm), "the re-enabled mapping must not carry the disabled one's attempt");
    }

    @Test
    void shutdownDuringAFailedInvokeKeepsTheBatchForTheNextStart() throws Exception {
        stubShard("s1");
        recordInvocations(seqs -> {
            poller.shutdown();
            return new InvokeResult(200, "Unhandled", new byte[0], null, "req-1");
        });

        pollOnce(esmWithOnFailure(0, QUEUE_ARN));

        verifyNoInteractions(sqsService);
        verify(esmStore, never()).saveForAccount(anyString(), any());
    }

    @Test
    void resetDuringAFailedInvokeDropsItsResultAndPollingResumesAfterIt() throws Exception {
        stubShard("s1");
        AtomicBoolean resetDuringInvoke = new AtomicBoolean(true);
        List<byte[]> events = recordInvocations(seqs -> {
            if (resetDuringInvoke.getAndSet(false)) {
                poller.beforeReset();
            }
            return new InvokeResult(200, "Unhandled", new byte[0], null, "req-1");
        });
        EventSourceMapping esm = esmWithOnFailure(0, QUEUE_ARN);

        pollOnce(esm);
        verifyNoInteractions(sqsService);
        verify(esmStore, never()).saveForAccount(anyString(), any());

        poller.afterReset();
        pollOnce(esm);

        assertEquals(2, events.size());
        verify(sqsService, times(1)).sendMessage(eq(QUEUE_URL), anyString(), eq(0), eq(REGION));
        assertEquals("s1", checkpoint(esm));
    }

    @Test
    void throttledBatchOlderThanMaximumRecordAgeIsDiscardedWithTheAgeCondition() throws Exception {
        stubShard("s1");
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenThrow(new AwsException("TooManyRequestsException", "Rate exceeded", 429));
        EventSourceMapping esm = esmWithOnFailure(null, QUEUE_ARN);
        esm.setMaximumRecordAgeInSeconds(60);

        clock.set(FIRST_ARRIVAL.plusSeconds(58).toEpochMilli());
        pollOnce(esm);
        assertNull(checkpoint(esm), "a throttled batch within the 60 s maximum age is kept");
        verify(sqsService, never()).sendMessage(anyString(), anyString(), anyInt(), anyString());

        clock.set(FIRST_ARRIVAL.plusSeconds(61).toEpochMilli());
        pollOnce(esm);

        assertEquals("s1", checkpoint(esm), "a throttled batch past the maximum age must not block the shard");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sqsService, times(1)).sendMessage(eq(QUEUE_URL), body.capture(), eq(0), eq(REGION));
        JsonNode record = MAPPER.readTree(body.getValue());
        assertEquals("MaximumRecordAgeExceeded", record.at("/requestContext/condition").asText(), record.toString());
        assertEquals(0, record.at("/requestContext/approximateInvokeCount").asInt(), "the function never ran");
        assertEquals(429, record.at("/responseContext/statusCode").asInt());
        assertFalse(record.path("responseContext").has("functionError"), record.toString());
        assertBatchInfo(record, "s1", "s1", "2026-10-20T12:07:26.012Z", "2026-10-20T12:07:26.012Z", 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "shutdown"})
    void recordsTheFunctionHandledStayCheckpointedWhenTheMappingStopsDuringAPartialResponse(String stop)
            throws Exception {
        stubShard("s1", "s2", "s3");
        recordInvocations(seqs -> {
            if ("disabled".equals(stop)) {
                storedMapping.set(Optional.of(storedAs(false)));
            } else {
                poller.shutdown();
            }
            return batchItemFailures("{\"batchItemFailures\":[{\"itemIdentifier\":\"s2\"}]}");
        });
        EventSourceMapping esm = esmWithOnFailure(0, QUEUE_ARN);
        esm.setFunctionResponseTypes(List.of("ReportBatchItemFailures"));

        pollOnce(esm);

        assertEquals("s1", checkpoint(esm), "s1 was handled by the function and must not be sent to it again");
        verifyNoInteractions(sqsService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted", "reset"})
    void mappingStoppedDuringTheOnFailureSendIsNotWrittenBack(String stop) throws Exception {
        stubShard("s1");
        failInvocationsContaining("s1");
        when(sqsService.sendMessage(anyString(), anyString(), anyInt(), anyString())).thenAnswer(inv -> {
            if ("deleted".equals(stop)) {
                storedMapping.set(Optional.empty());
            } else {
                poller.beforeReset();
            }
            return null;
        });

        pollOnce(esmWithOnFailure(0, QUEUE_ARN));

        verify(sqsService, times(1)).sendMessage(eq(QUEUE_URL), anyString(), eq(0), eq(REGION));
        verify(esmStore, never()).saveForAccount(anyString(), any());
    }

    @Test
    void stopPollingDuringAFailedInvokeKeepsNoAttemptBeforeTheStoreDeleteRuns() throws Exception {
        stubShard("s1");
        EventSourceMapping esm = esmWithOnFailure(1, QUEUE_ARN);
        AtomicBoolean stopDuringInvoke = new AtomicBoolean(true);
        List<byte[]> events = recordInvocations(seqs -> {
            if (stopDuringInvoke.getAndSet(false)) {
                // DeleteEventSourceMapping stops polling first; the store still holds the mapping.
                poller.stopPolling(esm.getUuid());
            }
            return new InvokeResult(200, "Unhandled", new byte[0], null, "req-1");
        });

        pollOnce(esm);
        poller.startPolling(esm);
        pollOnce(esm);

        assertEquals(2, events.size(), "a mapping stopped during its invoke must not keep a backoff");
        verifyNoInteractions(sqsService);
    }

    @Test
    void mappingDeletedReleasesItsStopTombstone() {
        poller.stopPolling("k-esm-gone");

        poller.mappingDeleted("k-esm-gone");

        assertTrue(poller.stopped.isEmpty());
    }

    @Test
    void stopPollingForgetsTheAttemptsAndBackoffOfItsMapping() throws Exception {
        stubShard("s1");
        List<byte[]> events = failInvocationsContaining("s1");
        EventSourceMapping esm = esmWithOnFailure(1, QUEUE_ARN);

        pollOnce(esm);
        poller.stopPolling(esm.getUuid());
        pollOnce(esm);

        assertEquals(2, events.size(), "a stopped mapping must not keep its backoff");
        assertNull(checkpoint(esm), "a stopped mapping must not keep its attempt count");
        verifyNoInteractions(sqsService);
    }

    private void pollOnce(EventSourceMapping esm) throws InterruptedException {
        poller.pollAndInvoke(esm);
        long deadline = System.currentTimeMillis() + 3_000;
        while (!poller.activePolls.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(poller.activePolls.isEmpty(), "poll did not complete before the test timeout");
    }

    private static KinesisRecord record(String seq, Instant arrival) {
        return new KinesisRecord("{}".getBytes(StandardCharsets.UTF_8), "pk-" + seq, seq, arrival);
    }

    /**
     * Serves one shard as Kinesis does: TRIM_HORIZON reads from the first record, AFTER_SEQUENCE_NUMBER
     * after the checkpoint, at most the batch size. Adding to the returned list writes to the shard.
     */
    private List<KinesisRecord> stubShard(String... sequences) {
        List<KinesisRecord> shard = new CopyOnWriteArrayList<>();
        for (int i = 0; i < sequences.length; i++) {
            shard.add(record(sequences[i], FIRST_ARRIVAL.plusSeconds(i)));
        }
        KinesisStream stream = new KinesisStream("s", STREAM_ARN);
        stream.setShards(List.of(new KinesisShard(SHARD, "0", "1", "0")));
        when(kinesisService.describeStreamForAccount(eq(ACCOUNT), anyString(), eq(REGION))).thenReturn(stream);
        when(kinesisService.getShardIteratorForAccount(eq(ACCOUNT), anyString(), eq(SHARD), anyString(), any(), eq(REGION)))
                .thenAnswer(inv -> "after:" + (inv.getArgument(4) == null ? "" : inv.getArgument(4)));
        when(kinesisService.getRecordsForAccount(eq(ACCOUNT), anyString(), anyInt(), eq(REGION))).thenAnswer(inv -> {
            int from = indexAfter(shard, inv.<String>getArgument(1).substring("after:".length()));
            int to = Math.min(shard.size(), from + inv.<Integer>getArgument(2));
            return Map.of("Records", new ArrayList<>(shard.subList(from, to)));
        });
        stubFunction();
        return shard;
    }

    /** The index of the record after {@code checkpoint}, or 0 (the trim horizon) when no record has it. */
    private static int indexAfter(List<KinesisRecord> shard, String checkpoint) {
        for (int i = 0; i < shard.size(); i++) {
            if (shard.get(i).getSequenceNumber().equals(checkpoint)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** Answers every invoke with {@code respond}, given the delivered sequences, and keeps each event. */
    private List<byte[]> recordInvocations(Function<List<String>, InvokeResult> respond) {
        List<byte[]> events = new CopyOnWriteArrayList<>();
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse))).thenAnswer(inv -> {
            byte[] event = inv.getArgument(1);
            events.add(event);
            return respond.apply(sequences(event));
        });
        return events;
    }

    /** The function raises, as a handler that throws does: status 200 with an Unhandled function error. */
    private List<byte[]> failInvocationsContaining(String poison) {
        return recordInvocations(seqs -> {
            if (!seqs.contains(poison)) {
                return new InvokeResult();
            }
            InvokeResult error = new InvokeResult(200, "Unhandled",
                    "{\"errorMessage\":\"boom\"}".getBytes(StandardCharsets.UTF_8), null, "req-1");
            error.setExecutedVersion("$LATEST");
            return error;
        });
    }

    private List<String> sequences(byte[] event) {
        List<String> sequences = new ArrayList<>();
        deliveredRecords(event).forEach(r -> sequences.add(r.path("kinesis").path("sequenceNumber").asText()));
        return sequences;
    }

    private List<List<String>> invokedSequences(List<byte[]> events) {
        return events.stream().map(this::sequences).toList();
    }

    private EventSourceMapping esmWithOnFailure(Integer maximumRetryAttempts, String destinationArn) {
        EventSourceMapping esm = esm();
        esm.setFunctionArn(FUNCTION_ARN);
        esm.setMaximumRetryAttempts(maximumRetryAttempts);
        if (destinationArn != null) {
            EventSourceMapping.OnFailure onFailure = new EventSourceMapping.OnFailure();
            onFailure.setDestination(destinationArn);
            EventSourceMapping.DestinationConfig destinationConfig = new EventSourceMapping.DestinationConfig();
            destinationConfig.setOnFailure(onFailure);
            esm.setDestinationConfig(destinationConfig);
        }
        return esm;
    }

    private static EventSourceMapping storedAs(boolean enabled) {
        EventSourceMapping stored = new EventSourceMapping();
        stored.setEnabled(enabled);
        return stored;
    }

    private static String checkpoint(EventSourceMapping esm) {
        return esm.getShardSequenceNumbers().get(SHARD);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void assertBatchInfo(JsonNode record, String startSequence, String endSequence,
                                        String firstArrival, String lastArrival, int batchSize) {
        JsonNode batchInfo = record.path("KinesisBatchInfo");
        assertEquals(List.of("shardId", "startSequenceNumber", "endSequenceNumber", "approximateArrivalOfFirstRecord",
                "approximateArrivalOfLastRecord", "batchSize", "streamArn"), fieldNames(batchInfo), record.toString());
        assertEquals(SHARD, batchInfo.path("shardId").asText());
        assertEquals(startSequence, batchInfo.path("startSequenceNumber").asText());
        assertEquals(endSequence, batchInfo.path("endSequenceNumber").asText());
        assertEquals(firstArrival, batchInfo.path("approximateArrivalOfFirstRecord").asText());
        assertEquals(lastArrival, batchInfo.path("approximateArrivalOfLastRecord").asText());
        assertTrue(batchInfo.path("batchSize").isInt(), "batchSize must be a number: " + batchInfo);
        assertEquals(batchSize, batchInfo.path("batchSize").asInt());
        assertEquals(STREAM_ARN, batchInfo.path("streamArn").asText());
    }
}
