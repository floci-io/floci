package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.pipes.PipesFilterMatcher;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * Regression coverage for issue #2076: a DynamoDB Streams ESM must not resume from a shard
 * checkpoint persisted by a previous run. The stream and its sequence numbers are volatile
 * (in-memory only), so after a restart the sequence numbers restart from 1 and a stale checkpoint
 * would silently skip every new record.
 */
class DynamoDbStreamsEventSourcePollerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ACCOUNT_ID = "000000000000";
    private static final String STREAM_ARN =
            "arn:aws:dynamodb:us-east-1:000000000000:table/t/stream/2026-08-01T00:00:00.000";
    private static final String STALE_CHECKPOINT = "000000000000000000634";

    private DynamoDbStreamsEventSourcePoller poller;
    private DynamoDbStreamService streamService;
    private LambdaExecutorService executorService;
    private LambdaFunctionStore functionStore;
    private EsmStore esmStore;
    private EmulatorConfig config;
    private PipesFilterMatcher filterMatcher;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambdaConfig = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambdaConfig);
        when(lambdaConfig.pollIntervalMs()).thenReturn(1000L);

        streamService = mock(DynamoDbStreamService.class);
        executorService = mock(LambdaExecutorService.class);
        functionStore = mock(LambdaFunctionStore.class);
        esmStore = new EsmStore(new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT_ID));
        filterMatcher = new PipesFilterMatcher(OBJECT_MAPPER);

        // A mocked Vertx makes setPeriodic a no-op, so startPolling registers no live timer and
        // the tests drive pollAndInvoke deterministically.
        poller = new DynamoDbStreamsEventSourcePoller(
                mock(Vertx.class), streamService, executorService, functionStore,
                esmStore, OBJECT_MAPPER, config, filterMatcher);
    }

    private EventSourceMapping persistedStreamsEsmWithStaleCheckpoint() {
        EventSourceMapping esm = new EventSourceMapping();
        esm.setUuid("esm-1");
        esm.setAccountId(ACCOUNT_ID);
        esm.setRegion("us-east-1");
        esm.setFunctionName("fn");
        esm.setEventSourceArn(STREAM_ARN);
        esm.setBatchSize(10);
        esm.setEnabled(true);
        esm.getShardSequenceNumbers().put(DynamoDbStreamService.SHARD_ID, STALE_CHECKPOINT);
        esmStore.saveForAccount(ACCOUNT_ID, esm);
        return esm;
    }

    @Test
    void startPersistedPollersDiscardsStaleShardCheckpoints() {
        persistedStreamsEsmWithStaleCheckpoint();

        poller.startPersistedPollers();

        EventSourceMapping reloaded = esmStore.getForAccount(ACCOUNT_ID, "esm-1").orElseThrow();
        assertTrue(reloaded.getShardSequenceNumbers().isEmpty(),
                "a checkpoint persisted before restart must be discarded at startup, since the "
                        + "stream's sequence numbers reset to 1 — otherwise every new record is skipped");
    }

    @Test
    void pollerDeliversPostRestartRecordAfterStartupCheckpointReset() {
        persistedStreamsEsmWithStaleCheckpoint();

        // The new stream epoch has a single record at sequence 1.
        DynamoDbStreamRecord record = new DynamoDbStreamRecord();
        record.setEventName("INSERT");
        record.setEventSource("aws:dynamodb");
        record.setAwsRegion("us-east-1");
        record.setSequenceNumber("000000000000000000001");

        // Resuming from the stale checkpoint (AFTER_SEQUENCE_NUMBER 634) sees nothing — the bug: the
        // record's sequence (1) is far below the stale checkpoint, so it is silently skipped.
        when(streamService.getShardIterator(STREAM_ARN, DynamoDbStreamService.SHARD_ID,
                "AFTER_SEQUENCE_NUMBER", STALE_CHECKPOINT)).thenReturn("iterator-stale");
        when(streamService.getRecords("iterator-stale", 10))
                .thenReturn(new DynamoDbStreamService.GetRecordsResult(List.of(), "iterator-stale"));
        // Resuming from TRIM_HORIZON — where the fix makes the poller restart — delivers the record.
        when(streamService.getShardIterator(STREAM_ARN, DynamoDbStreamService.SHARD_ID,
                "TRIM_HORIZON", null)).thenReturn("iterator-trim");
        when(streamService.getRecords("iterator-trim", 10))
                .thenReturn(new DynamoDbStreamService.GetRecordsResult(List.of(record), "iterator-trim"));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("fn");
        when(functionStore.getForAccount(ACCOUNT_ID, "us-east-1", "fn")).thenReturn(Optional.of(fn));
        when(executorService.invoke(eq(fn), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult()); // success — no functionError

        // Startup invalidates the stale checkpoint...
        poller.startPersistedPollers();
        // ...so the poll resumes from TRIM_HORIZON and delivers the record instead of dropping it.
        // (Were the checkpoint not cleared, the poll would take the AFTER_SEQUENCE_NUMBER branch,
        // find nothing, and invoke would never be called — this verify would then fail.)
        poller.pollAndInvoke(esmStore.getForAccount(ACCOUNT_ID, "esm-1").orElseThrow());

        verify(executorService, timeout(2000))
                .invoke(eq(fn), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    // ──────────────────────────── FilterCriteria ────────────────────────────

    private DynamoDbStreamsEventSourcePoller pollerWith(EsmStore store) {
        return new DynamoDbStreamsEventSourcePoller(
                mock(Vertx.class), streamService, executorService, functionStore,
                store, OBJECT_MAPPER, config, filterMatcher);
    }

    /**
     * Hard happens-after barrier: re-kick until a SECOND fetch is observed. {@code activePolls} serializes
     * polls per ESM, so fetch #2 cannot begin until poll #1's finally-block ran, so poll #1 is fully complete.
     */
    private void awaitPollCompletedViaSecondFetch(DynamoDbStreamsEventSourcePoller p, EventSourceMapping esm) {
        long deadline = System.currentTimeMillis() + 5000;
        while (true) {
            p.pollAndInvoke(esm);
            try {
                verify(streamService, atLeast(2)).getRecords("it", 10);
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

    private EventSourceMapping filterEsm(String... patterns) {
        EventSourceMapping esm = new EventSourceMapping();
        esm.setUuid("esm-f");
        esm.setAccountId(ACCOUNT_ID);
        esm.setRegion("us-east-1");
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

    private DynamoDbStreamRecord ddbRecord(String seq, String eventName, String newImageJson) {
        DynamoDbStreamRecord rec = new DynamoDbStreamRecord();
        rec.setSequenceNumber(seq);
        rec.setEventName(eventName);
        rec.setEventSource("aws:dynamodb");
        rec.setAwsRegion("us-east-1");
        rec.setStreamViewType("NEW_AND_OLD_IMAGES");
        try {
            rec.setNewImage(OBJECT_MAPPER.readTree(newImageJson));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return rec;
    }

    private void stubTrimHorizon(List<DynamoDbStreamRecord> records) {
        // Match any iterator type/seq so a re-kicked poll after a checkpoint advance (AFTER_SEQUENCE_NUMBER)
        // still resolves an iterator: the second-fetch barrier depends on poll N+1 fetching.
        when(streamService.getShardIterator(eq(STREAM_ARN), eq(DynamoDbStreamService.SHARD_ID), anyString(), any()))
                .thenReturn("it");
        when(streamService.getRecords("it", 10))
                .thenReturn(new DynamoDbStreamService.GetRecordsResult(records, "it"));
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("fn");
        when(functionStore.getForAccount(ACCOUNT_ID, "us-east-1", "fn")).thenReturn(Optional.of(fn));
    }

    private static final String PATTERN =
            "{\"eventName\":[\"INSERT\"],\"dynamodb\":{\"NewImage\":{\"status\":{\"S\":[\"active\"]}}}}";

    @Test
    void filterDeliversMatchingRecordsAndCheckpointsNewestFetched() {
        stubTrimHorizon(List.of(
                ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}"),
                ddbRecord("s2", "INSERT", "{\"status\":{\"S\":\"inactive\"}}")));
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult());
        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm(PATTERN);

        pollerWith(store).pollAndInvoke(esm);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(executorService, timeout(2000)).invoke(any(), payload.capture(), eq(InvocationType.RequestResponse));
        JsonNode records = readRecords(payload.getValue());
        assertEquals(1, records.size());
        assertEquals("s1", records.get(0).path("dynamodb").path("SequenceNumber").asText());
        verify(store, timeout(2000)).saveForAccount(eq(ACCOUNT_ID), any());
        assertEquals("s2", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));
    }

    @Test
    void fullyFilteredBatchAdvancesCheckpointWithoutInvoking() {
        stubTrimHorizon(List.of(
                ddbRecord("s1", "MODIFY", "{\"status\":{\"S\":\"active\"}}"),
                ddbRecord("s2", "INSERT", "{\"status\":{\"S\":\"inactive\"}}")));
        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm(PATTERN);
        DynamoDbStreamsEventSourcePoller p = pollerWith(store);

        p.pollAndInvoke(esm);

        verify(store, timeout(2000)).saveForAccount(eq(ACCOUNT_ID), any());
        awaitPollCompletedViaSecondFetch(p, esm);
        assertEquals("s2", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));
        verify(executorService, never()).invoke(any(), any(byte[].class), any());
    }

    @Test
    void invokeErrorDoesNotAdvanceCheckpointAndRetriesSameWindow() {
        stubTrimHorizon(List.of(ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}")));
        InvokeResult err = new InvokeResult();
        err.setFunctionError("Unhandled");
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(err);
        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm(PATTERN);
        DynamoDbStreamsEventSourcePoller p = pollerWith(store);

        p.pollAndInvoke(esm);
        verify(executorService, timeout(2000)).invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse));
        awaitPollCompletedViaSecondFetch(p, esm);

        verify(store, never()).saveForAccount(anyString(), any());
        assertNull(esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));
        // retry-from-old-checkpoint: both polls re-derive TRIM_HORIZON and deliver the same window.
        ArgumentCaptor<byte[]> cap = ArgumentCaptor.forClass(byte[].class);
        verify(executorService, timeout(2000).atLeast(2)).invoke(any(), cap.capture(), eq(InvocationType.RequestResponse));
        List<byte[]> payloads = cap.getAllValues();
        assertEquals(new String(payloads.get(0)), new String(payloads.get(payloads.size() - 1)));
    }

    @Test
    void throttleDoesNotAdvanceCheckpoint() {
        stubTrimHorizon(List.of(
                ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}"),
                ddbRecord("s2", "MODIFY", "{\"status\":{\"S\":\"inactive\"}}")));
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenThrow(new AwsException("TooManyRequestsException", "throttled", 429));
        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm(PATTERN); // s1 matches → invoke attempted → throttled
        DynamoDbStreamsEventSourcePoller p = pollerWith(store);

        p.pollAndInvoke(esm);
        verify(executorService, timeout(2000)).invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse));
        awaitPollCompletedViaSecondFetch(p, esm);
        verify(store, never()).saveForAccount(anyString(), any());
        assertNull(esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));
    }

    @Test
    void noFilterCriteriaDeliversAllRecords() {
        stubTrimHorizon(List.of(
                ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}"),
                ddbRecord("s2", "MODIFY", "{\"status\":{\"S\":\"inactive\"}}")));
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult());
        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm(); // no FilterCriteria

        pollerWith(store).pollAndInvoke(esm);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(executorService, timeout(2000)).invoke(any(), payload.capture(), eq(InvocationType.RequestResponse));
        assertEquals(2, readRecords(payload.getValue()).size());
        verify(store, timeout(2000)).saveForAccount(eq(ACCOUNT_ID), any());
        assertEquals("s2", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));
    }

    private JsonNode readRecords(byte[] payload) {
        try {
            return OBJECT_MAPPER.readTree(payload).path("Records");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
