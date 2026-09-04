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
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.pipes.PipesFilterMatcher;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
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
 */
class KinesisEventSourcePollerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String STREAM_ARN = "arn:aws:kinesis:us-east-1:000000000000:stream/s";
    private static final String SHARD = "shardId-000000000000";

    private KinesisEventSourcePoller poller;
    private KinesisService kinesisService;
    private LambdaExecutorService executorService;
    private LambdaFunctionStore functionStore;
    private EsmStore esmStore;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambdaConfig = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambdaConfig);
        when(lambdaConfig.pollIntervalMs()).thenReturn(1000L);

        kinesisService = mock(KinesisService.class);
        executorService = mock(LambdaExecutorService.class);
        functionStore = mock(LambdaFunctionStore.class);
        esmStore = mock(EsmStore.class);

        poller = new KinesisEventSourcePoller(mock(Vertx.class), kinesisService, executorService,
                functionStore, esmStore, config, MAPPER, new PipesFilterMatcher(MAPPER));
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
        when(kinesisService.describeStream(anyString(), eq(REGION))).thenReturn(stream);
        when(kinesisService.getShardIterator(anyString(), eq(SHARD), anyString(), any(), eq(REGION)))
                .thenReturn("iter-0");
        when(kinesisService.getRecords(eq("iter-0"), anyInt(), eq(REGION)))
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
                verify(kinesisService, atLeast(2)).getRecords(eq("iter-0"), anyInt(), eq(REGION));
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
    void invokeErrorDoesNotAdvanceCheckpointAndRetriesSameWindow() {
        stubFunction();
        stubStreamWith(List.of(record("s1", "p1", "{\"type\":\"order\"}")));
        InvokeResult err = new InvokeResult();
        err.setFunctionError("Unhandled");
        when(executorService.invoke(any(), any(), eq(InvocationType.RequestResponse))).thenReturn(err);
        EventSourceMapping esm = esm("{\"data\":{\"type\":[\"order\"]}}");

        poller.pollAndInvoke(esm);
        verify(executorService, timeout(2000)).invoke(any(), any(), eq(InvocationType.RequestResponse));
        // Barrier: a 2nd poll ran (so poll 1 finished). Since the checkpoint never advanced, both polls
        // re-derive TRIM_HORIZON and fetch the same window.
        awaitPollCompletedViaSecondFetch(esm);

        verify(esmStore, never()).saveForAccount(eq(ACCOUNT), any());
        assertNull(esm.getShardSequenceNumbers().get(SHARD));
        // retry-from-old-checkpoint: both invokes carried the same fetched window.
        ArgumentCaptor<byte[]> cap = ArgumentCaptor.forClass(byte[].class);
        verify(executorService, timeout(2000).atLeast(2)).invoke(any(), cap.capture(), eq(InvocationType.RequestResponse));
        List<byte[]> payloads = cap.getAllValues();
        assertEquals(new String(payloads.get(0), StandardCharsets.UTF_8),
                new String(payloads.get(payloads.size() - 1), StandardCharsets.UTF_8));
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
}
