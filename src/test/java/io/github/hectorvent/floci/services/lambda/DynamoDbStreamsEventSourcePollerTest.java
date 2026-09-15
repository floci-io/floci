package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.pipes.PipesFilterMatcher;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.ObjectAnnotation;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.s3.model.S3ObjectUpdatedEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for issue #2076: a DynamoDB Streams ESM must not resume from a shard
 * checkpoint persisted by a previous run. The stream and its sequence numbers are volatile
 * (in-memory only), so after a restart the sequence numbers restart from 1 and a stale checkpoint
 * would silently skip every new record.
 */
@QuarkusTest
class DynamoDbStreamsEventSourcePollerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ACCOUNT_ID = "000000000000";
    private static final String AMBIENT_ACCOUNT_ID = "222233334444";
    private static final String BUCKET_OWNER_ACCOUNT_ID = "111122223333";
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
    private io.github.hectorvent.floci.services.sqs.SqsService sqsService;
    private io.github.hectorvent.floci.services.sns.SnsService snsService;
    private io.github.hectorvent.floci.services.s3.S3Service s3Service;

    @Inject
    Instance<RequestContext> requestContextInstance;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambdaConfig = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambdaConfig);
        when(lambdaConfig.pollIntervalMs()).thenReturn(1000L);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        streamService = mock(DynamoDbStreamService.class);
        executorService = mock(LambdaExecutorService.class);
        functionStore = mock(LambdaFunctionStore.class);
        esmStore = new EsmStore(new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT_ID));
        filterMatcher = new PipesFilterMatcher(OBJECT_MAPPER);
        sqsService = mock(io.github.hectorvent.floci.services.sqs.SqsService.class);
        snsService = mock(io.github.hectorvent.floci.services.sns.SnsService.class);
        s3Service = mock(io.github.hectorvent.floci.services.s3.S3Service.class);

        // A mocked Vertx makes setPeriodic a no-op, so startPolling registers no live timer and
        // the tests drive pollAndInvoke deterministically.
        poller = new DynamoDbStreamsEventSourcePoller(
                mock(Vertx.class), streamService, executorService, functionStore,
                esmStore, OBJECT_MAPPER, config, filterMatcher, sqsService, snsService, s3Service);
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
                store, OBJECT_MAPPER, config, filterMatcher, sqsService, snsService, s3Service);
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

    @Test
    void partialBatchFailureCheckpointsBeforeLowestFailedRecord() {
        stubTrimHorizon(List.of(
                ddbRecord("s1", "INSERT", "{}"),
                ddbRecord("s2", "INSERT", "{}"),
                ddbRecord("s3", "INSERT", "{}")));
        InvokeResult result = new InvokeResult();
        result.setPayload("{\"batchItemFailures\":[{\"itemIdentifier\":\"s3\"},{\"itemIdentifier\":\"s2\"}]}".getBytes());
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(result);
        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm();
        esm.setFunctionResponseTypes(List.of("ReportBatchItemFailures"));

        pollerWith(store).pollAndInvoke(esm);

        verify(store, timeout(2000)).saveForAccount(eq(ACCOUNT_ID), any());
        assertEquals("s1", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));
    }

    @Test
    void partialBatchFailureAtFirstRecordKeepsPriorCheckpoint() {
        stubTrimHorizon(List.of(
                ddbRecord("s1", "INSERT", "{}"),
                ddbRecord("s2", "INSERT", "{}")));
        InvokeResult result = new InvokeResult();
        result.setPayload("{\"batchItemFailures\":[{\"itemIdentifier\":\"s1\"}]}".getBytes());
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(result);
        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm();
        esm.setFunctionResponseTypes(List.of("ReportBatchItemFailures"));
        esm.getShardSequenceNumbers().put(DynamoDbStreamService.SHARD_ID, "s0");
        DynamoDbStreamsEventSourcePoller p = pollerWith(store);

        p.pollAndInvoke(esm);
        verify(executorService, timeout(2000))
                .invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse));
        awaitPollCompletedViaSecondFetch(p, esm);

        verify(store, never()).saveForAccount(anyString(), any());
        assertEquals("s0", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));
    }

    @Test
    void malformedPartialBatchResponseRetriesWholeBatch() {
        stubTrimHorizon(List.of(
                ddbRecord("s1", "INSERT", "{}"),
                ddbRecord("s2", "INSERT", "{}")));
        InvokeResult result = new InvokeResult();
        result.setPayload("{\"batchItemFailures\":[{\"itemIdentifier\":\"unknown\"}]}".getBytes());
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(result);
        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm();
        esm.setFunctionResponseTypes(List.of("ReportBatchItemFailures"));
        DynamoDbStreamsEventSourcePoller p = pollerWith(store);

        p.pollAndInvoke(esm);
        verify(executorService, timeout(2000))
                .invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse));
        awaitPollCompletedViaSecondFetch(p, esm);

        verify(store, never()).saveForAccount(anyString(), any());
        assertNull(esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));
    }

    @Test
    void partialBatchResponseIsIgnoredWhenNotConfigured() {
        stubTrimHorizon(List.of(
                ddbRecord("s1", "INSERT", "{}"),
                ddbRecord("s2", "INSERT", "{}")));
        InvokeResult result = new InvokeResult();
        result.setPayload("{\"batchItemFailures\":[{\"itemIdentifier\":\"s1\"}]}".getBytes());
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(result);
        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm();

        pollerWith(store).pollAndInvoke(esm);

        verify(store, timeout(2000)).saveForAccount(eq(ACCOUNT_ID), any());
        assertEquals("s2", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));
    }

    // ──────────────────── FilterCriteria + partial batch failure ────────────────────

    /**
     * The retry boundary is computed against the full FETCHED batch, while the function only ever
     * sees the post-filter records. A filtered-out record sitting immediately before the lowest
     * failed record is therefore the checkpoint: it is consumed (never re-read), and the failed
     * record plus everything after it is retried.
     */
    @Test
    void partialFailureWithFilterCheckpointsAtFilteredOutRecordBeforeLowestFailure() {
        stubTrimHorizon(List.of(
                ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}"),
                ddbRecord("s2", "INSERT", "{\"status\":{\"S\":\"inactive\"}}"), // filtered out
                ddbRecord("s3", "INSERT", "{\"status\":{\"S\":\"active\"}}"),
                ddbRecord("s4", "INSERT", "{\"status\":{\"S\":\"active\"}}")));
        InvokeResult result = new InvokeResult();
        result.setPayload("{\"batchItemFailures\":[{\"itemIdentifier\":\"s4\"},{\"itemIdentifier\":\"s3\"}]}".getBytes());
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(result);
        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm(PATTERN);
        esm.setFunctionResponseTypes(List.of("ReportBatchItemFailures"));

        pollerWith(store).pollAndInvoke(esm);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(executorService, timeout(2000)).invoke(any(), payload.capture(), eq(InvocationType.RequestResponse));
        JsonNode records = readRecords(payload.getValue());
        assertEquals(3, records.size(), "only the post-filter records are delivered");
        assertEquals("s1", records.get(0).path("dynamodb").path("SequenceNumber").asText());
        assertEquals("s3", records.get(1).path("dynamodb").path("SequenceNumber").asText());
        assertEquals("s4", records.get(2).path("dynamodb").path("SequenceNumber").asText());

        verify(store, timeout(2000)).saveForAccount(eq(ACCOUNT_ID), any());
        // s2 was never sent to the function, yet it is the record immediately before the lowest
        // failure (s3) in the fetched batch — so it is the checkpoint: s1 and s2 are consumed and
        // the next poll resumes AFTER s2, re-delivering s3 and s4.
        assertEquals("s2", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));
    }

    /**
     * A reported identifier that names a record the filter removed is a record the function never
     * received. It cannot be a legitimate failure, so it is treated as malformed and the whole batch
     * is retried instead of silently advancing past it.
     */
    @Test
    void partialFailureReportingFilteredOutRecordRetriesWholeBatch() {
        stubTrimHorizon(List.of(
                ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}"),
                ddbRecord("s2", "INSERT", "{\"status\":{\"S\":\"inactive\"}}"), // filtered out
                ddbRecord("s3", "INSERT", "{\"status\":{\"S\":\"active\"}}")));
        InvokeResult result = new InvokeResult();
        // s2 is in the fetched batch but was never delivered to the function.
        result.setPayload("{\"batchItemFailures\":[{\"itemIdentifier\":\"s2\"}]}".getBytes());
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(result);
        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm(PATTERN);
        esm.setFunctionResponseTypes(List.of("ReportBatchItemFailures"));
        esm.getShardSequenceNumbers().put(DynamoDbStreamService.SHARD_ID, "s0");
        DynamoDbStreamsEventSourcePoller p = pollerWith(store);

        p.pollAndInvoke(esm);
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(executorService, timeout(2000)).invoke(any(), payload.capture(), eq(InvocationType.RequestResponse));
        JsonNode records = readRecords(payload.getValue());
        assertEquals(2, records.size());
        assertEquals("s1", records.get(0).path("dynamodb").path("SequenceNumber").asText());
        assertEquals("s3", records.get(1).path("dynamodb").path("SequenceNumber").asText());
        awaitPollCompletedViaSecondFetch(p, esm);

        // Had the identifier been accepted, the checkpoint would have landed on s1 (the record
        // before s2). Instead the prior checkpoint is kept and the entire window is retried.
        verify(store, never()).saveForAccount(anyString(), any());
        assertEquals("s0", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));
    }

    /**
     * A checkpoint that has aged out of the retained window names a cursor that can never succeed.
     * Retrying it wedges the ESM for good: later writes keep reaching the stream and none is ever
     * delivered. The poller must resume from the trim horizon instead.
     */
    @Test
    void trimmedCheckpointResumesFromTrimHorizonInsteadOfWedging() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("fn");
        when(functionStore.getForAccount(ACCOUNT_ID, "us-east-1", "fn")).thenReturn(Optional.of(fn));

        when(streamService.getShardIterator(eq(STREAM_ARN), eq(DynamoDbStreamService.SHARD_ID),
                eq("AFTER_SEQUENCE_NUMBER"), any())).thenReturn("it-trimmed");
        when(streamService.getShardIterator(eq(STREAM_ARN), eq(DynamoDbStreamService.SHARD_ID),
                eq("TRIM_HORIZON"), any())).thenReturn("it-horizon");
        when(streamService.getRecords("it-trimmed", 10)).thenThrow(
                new AwsException("TrimmedDataAccessException",
                        "The requested sequence number has been trimmed", 400));
        when(streamService.getRecords("it-horizon", 10)).thenReturn(
                new DynamoDbStreamService.GetRecordsResult(
                        List.of(ddbRecord("s9", "INSERT", "{\"status\":{\"S\":\"active\"}}")), "it-horizon"));
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult());

        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm();
        esm.getShardSequenceNumbers().put(DynamoDbStreamService.SHARD_ID, STALE_CHECKPOINT);

        pollerWith(store).pollAndInvoke(esm);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(executorService, timeout(2000)).invoke(any(), payload.capture(), eq(InvocationType.RequestResponse));
        assertEquals(1, readRecords(payload.getValue()).size());
        verify(store, timeout(2000)).saveForAccount(eq(ACCOUNT_ID), any());
        assertEquals("s9", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));
    }

    /** Only a trimmed checkpoint resets the cursor; any other stream failure retries the same window. */
    @Test
    void nonTrimmedStreamErrorLeavesTheCheckpointAlone() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("fn");
        when(functionStore.getForAccount(ACCOUNT_ID, "us-east-1", "fn")).thenReturn(Optional.of(fn));

        when(streamService.getShardIterator(eq(STREAM_ARN), eq(DynamoDbStreamService.SHARD_ID),
                anyString(), any())).thenReturn("it");
        when(streamService.getRecords("it", 10)).thenThrow(
                new AwsException("InternalServerError", "boom", 500));

        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm();
        esm.getShardSequenceNumbers().put(DynamoDbStreamService.SHARD_ID, STALE_CHECKPOINT);

        pollerWith(store).pollAndInvoke(esm);
        awaitPollCompletedViaSecondFetch(pollerWith(store), esm);

        verify(executorService, never()).invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse));
        verify(store, never()).saveForAccount(anyString(), any());
        assertEquals(STALE_CHECKPOINT, esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));
    }

    @Test
    void maxRetryAttemptsExhaustedDeliversToSqsOnFailureDestinationAndAdvancesCheckpoint() throws Exception {
        stubTrimHorizon(List.of(ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}")));
        InvokeResult err = new InvokeResult();
        err.setFunctionError("Unhandled");
        err.setStatusCode(200);
        err.setRequestId("req-123");
        err.setExecutedVersion("$LATEST");
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(err);

        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm();
        esm.setMaximumRetryAttempts(1); // 1 retry attempt allowed, 2nd error exhausts retries

        String sqsArn = "arn:aws:sqs:us-east-1:000000000000:my-dlq";
        EventSourceMapping.DestinationConfig destConfig = new EventSourceMapping.DestinationConfig();
        EventSourceMapping.OnFailure onFailure = new EventSourceMapping.OnFailure();
        onFailure.setDestination(sqsArn);
        destConfig.setOnFailure(onFailure);
        esm.setDestinationConfig(destConfig);

        DynamoDbStreamsEventSourcePoller p = pollerWith(store);

        // First poll: attempt 1 (initial failure, retries left)
        p.pollAndInvoke(esm);
        // Verify invoke was called for attempt 1
        verify(executorService, timeout(2000)).invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse));
        // Wait for poll 1 to finish by ensuring activePolls is released
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (p.activePolls.isEmpty()) {
                break;
            }
            Thread.sleep(25);
        }
        // After 1st failure: checkpoint not advanced, no DLQ delivery
        verify(store, never()).saveForAccount(anyString(), any());
        verify(sqsService, never()).sendMessage(anyString(), anyString(), anyInt(), anyString());

        // Second poll: attempt 2 (retry 1, which exceeds maxRetryAttempts=1 -> exhausted!)
        p.pollAndInvoke(esm);

        // Checkpoint advanced to "s1"
        verify(store, timeout(2000)).saveForAccount(eq(ACCOUNT_ID), any());
        assertEquals("s1", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));

        // SQS delivery invoked
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        String expectedQueueUrl = "http://localhost:4566/000000000000/my-dlq";
        verify(sqsService, timeout(2000)).sendMessage(eq(expectedQueueUrl), bodyCaptor.capture(), eq(0), eq("us-east-1"));

        JsonNode dlqPayload = OBJECT_MAPPER.readTree(bodyCaptor.getValue());
        assertEquals("1.0", dlqPayload.path("version").asText());
        assertEquals("RetryAttemptsExhausted", dlqPayload.path("requestContext").path("condition").asText());
        assertEquals(2, dlqPayload.path("requestContext").path("approximateInvokeCount").asInt());
        assertEquals("req-123", dlqPayload.path("requestContext").path("requestId").asText());
        assertEquals("Unhandled", dlqPayload.path("responseContext").path("functionError").asText());
        assertEquals(DynamoDbStreamService.SHARD_ID, dlqPayload.path("DDBStreamBatchInfo").path("shardId").asText());
        assertEquals("s1", dlqPayload.path("DDBStreamBatchInfo").path("startSequenceNumber").asText());
        assertEquals("s1", dlqPayload.path("DDBStreamBatchInfo").path("endSequenceNumber").asText());
        assertEquals(1, dlqPayload.path("DDBStreamBatchInfo").path("batchSize").asInt());
        assertEquals(STREAM_ARN, dlqPayload.path("DDBStreamBatchInfo").path("streamArn").asText());
        assertTrue(dlqPayload.path("DDBStreamBatchInfo").has("approximateArrivalOfFirstRecord"));
        assertTrue(dlqPayload.path("DDBStreamBatchInfo").has("approximateArrivalOfLastRecord"));
        assertFalse(dlqPayload.has("hasBeenTruncated"));
    }

    @Test
    void maxRetryAttemptsExhaustedUsesEsmAccountOutsideRequestContext() throws Exception {
        S3OnFailureDelivery delivery = deliverFailedBatchToS3(ACCOUNT_ID, false);

        assertS3FailureRecord(delivery, ACCOUNT_ID);
        assertTrue(delivery.objects().keysForAccount(AMBIENT_ACCOUNT_ID).isEmpty(),
                "the async/default account must not receive a shadow failure object");
    }

    @Test
    void maxRetryAttemptsExhaustedDeliversOriginalEventToCrossAccountBucketOwner() throws Exception {
        S3OnFailureDelivery delivery = deliverFailedBatchToS3(BUCKET_OWNER_ACCOUNT_ID, true);

        assertS3FailureRecord(delivery, BUCKET_OWNER_ACCOUNT_ID);
        assertTrue(delivery.objects().keysForAccount(ACCOUNT_ID).isEmpty(),
                "the ESM account must not receive a shadow object for a cross-account bucket");
        assertTrue(delivery.objects().keysForAccount(AMBIENT_ACCOUNT_ID).isEmpty(),
                "the async/default account must not receive a shadow failure object");
    }

    private S3OnFailureDelivery deliverFailedBatchToS3(
            String bucketOwnerAccountId, boolean globalBucketNamespace) {
        stubTrimHorizon(List.of(ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}")));
        InvokeResult err = new InvokeResult();
        err.setFunctionError("Unhandled");
        err.setStatusCode(200);
        err.setRequestId("req-s3");
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(err);

        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm();
        esm.setMaximumRetryAttempts(0);

        EventSourceMapping.DestinationConfig destConfig = new EventSourceMapping.DestinationConfig();
        EventSourceMapping.OnFailure onFailure = new EventSourceMapping.OnFailure();
        onFailure.setDestination("arn:aws:s3:::my-failed-events");
        destConfig.setOnFailure(onFailure);
        esm.setDestinationConfig(destConfig);

        AccountAwareStorageBackend<Bucket> buckets = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), requestContextInstance, AMBIENT_ACCOUNT_ID);
        AccountAwareStorageBackend<S3Object> objects = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), requestContextInstance, AMBIENT_ACCOUNT_ID);
        AccountAwareStorageBackend<ObjectAnnotation> annotations = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), requestContextInstance, AMBIENT_ACCOUNT_ID);
        AccountAwareStorageBackend<String> accountPublicAccessBlocks = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), requestContextInstance, AMBIENT_ACCOUNT_ID);
        buckets.putForAccount(bucketOwnerAccountId, "my-failed-events", new Bucket("my-failed-events"));

        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(eq("s3"), anyString(), any())).thenAnswer(invocation -> {
            String fileName = invocation.getArgument(1);
            return switch (fileName) {
                case "s3-buckets.json" -> buckets;
                case "s3-objects.json" -> objects;
                case "s3-annotations.json" -> annotations;
                case "s3-account-public-access-block.json" -> accountPublicAccessBlocks;
                default -> throw new AssertionError("Unexpected S3 store: " + fileName);
            };
        });

        EmulatorConfig.StorageConfig storageConfig = mock(EmulatorConfig.StorageConfig.class);
        when(storageConfig.persistentPath()).thenReturn(Path.of("target", "s3-onfailure-test").toString());
        EmulatorConfig.ServiceStorageOverrides storageOverrides = mock(EmulatorConfig.ServiceStorageOverrides.class);
        EmulatorConfig.S3StorageConfig s3StorageConfig = mock(EmulatorConfig.S3StorageConfig.class);
        when(storageConfig.services()).thenReturn(storageOverrides);
        when(storageOverrides.s3()).thenReturn(s3StorageConfig);
        when(s3StorageConfig.mode()).thenReturn(Optional.of("memory"));
        when(config.storage()).thenReturn(storageConfig);
        EmulatorConfig.S3ServiceConfig s3Config = mock(EmulatorConfig.S3ServiceConfig.class);
        when(s3Config.globalBucketNamespace()).thenReturn(globalBucketNamespace);
        when(config.services().s3()).thenReturn(s3Config);

        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenAnswer(
                invocation -> requestContextInstance.get().getAccountId());
        s3Service = new S3Service(
                storageFactory, config,
                mock(io.github.hectorvent.floci.services.sqs.SqsService.class),
                mock(io.github.hectorvent.floci.services.sns.SnsService.class),
                mock(Instance.class), mock(EventBridgeService.class), mock(Event.class),
                regionResolver, OBJECT_MAPPER, mock(IamService.class));

        pollerWith(store).pollAndInvoke(esm);

        verify(store, timeout(2000)).saveForAccount(eq(ACCOUNT_ID), any());
        assertEquals("s1", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));

        return new S3OnFailureDelivery(s3Service, objects);
    }

    private void assertS3FailureRecord(S3OnFailureDelivery delivery, String expectedOwnerAccountId)
            throws Exception {
        String storedObjectKey = delivery.objects().keysForAccount(expectedOwnerAccountId).stream()
                .filter(key -> key.startsWith("my-failed-events/aws/lambda/esm-f/"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("failure object was not stored in the bucket owner's account"));

        String key = storedObjectKey.substring("my-failed-events/".length());
        byte[] body = RequestScopes.callAs(ACCOUNT_ID,
                () -> delivery.s3Service().getObject("my-failed-events", key).getData());

        assertTrue(key.matches(
                "^aws/lambda/esm-f/shardId-0000000001-00000000001/\\d{4}/\\d{2}/\\d{2}/"
                        + "\\d{4}-\\d{2}-\\d{2}T\\d{2}\\.\\d{2}\\.\\d{2}-[0-9a-f-]{36}$"));

        JsonNode failureRecord = OBJECT_MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
        assertEquals("RetryAttemptsExhausted",
                failureRecord.path("requestContext").path("condition").asText());
        assertEquals(1, failureRecord.path("requestContext").path("approximateInvokeCount").asInt());
        assertEquals("req-s3", failureRecord.path("requestContext").path("requestId").asText());
        assertTrue(failureRecord.path("payload").isTextual());

        JsonNode originalEvent = OBJECT_MAPPER.readTree(failureRecord.path("payload").asText());
        assertEquals(1, originalEvent.path("Records").size());
        assertEquals("s1", originalEvent.path("Records").get(0)
                .path("dynamodb").path("SequenceNumber").asText());
    }

    private record S3OnFailureDelivery(
            S3Service s3Service, AccountAwareStorageBackend<S3Object> objects) {
    }

    @Test
    void s3OnFailureKeyUsesUtcLayout() {
        UUID randomId = UUID.fromString("12345678-1234-1234-1234-123456789abc");

        String key = DynamoDbStreamsEventSourcePoller.buildS3OnFailureKey(
                "esm-1", "shard-1", Instant.parse("2026-09-14T23:07:08Z"), randomId);

        assertEquals("aws/lambda/esm-1/shard-1/2026/09/14/"
                + "2026-09-14T23.07.08-12345678-1234-1234-1234-123456789abc", key);
    }

    @Test
    void onFailurePayloadUsesEpochSecondsNotMillisForArrivalTimestamps() throws Exception {
        // Chosen so the seconds/millis interpretations land 46+ years apart: a millis bug cannot
        // accidentally parse back to this epoch-seconds value.
        long knownEpochSeconds = 1_700_000_000L;
        DynamoDbStreamRecord rec = ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}");
        rec.setApproximateCreationDateTime(knownEpochSeconds);
        stubTrimHorizon(List.of(rec));

        InvokeResult err = new InvokeResult();
        err.setFunctionError("Unhandled");
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(err);

        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm();
        esm.setMaximumRetryAttempts(0); // exhausts on the first failure

        String sqsArn = "arn:aws:sqs:us-east-1:000000000000:my-dlq";
        EventSourceMapping.DestinationConfig destConfig = new EventSourceMapping.DestinationConfig();
        EventSourceMapping.OnFailure onFailure = new EventSourceMapping.OnFailure();
        onFailure.setDestination(sqsArn);
        destConfig.setOnFailure(onFailure);
        esm.setDestinationConfig(destConfig);

        DynamoDbStreamsEventSourcePoller p = pollerWith(store);
        p.pollAndInvoke(esm);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(sqsService, timeout(2000)).sendMessage(anyString(), bodyCaptor.capture(), anyInt(), anyString());

        JsonNode dlqPayload = OBJECT_MAPPER.readTree(bodyCaptor.getValue());
        Instant first = Instant.parse(
                dlqPayload.path("DDBStreamBatchInfo").path("approximateArrivalOfFirstRecord").asText());
        Instant last = Instant.parse(
                dlqPayload.path("DDBStreamBatchInfo").path("approximateArrivalOfLastRecord").asText());
        assertEquals(knownEpochSeconds, first.getEpochSecond());
        assertEquals(knownEpochSeconds, last.getEpochSecond());
    }

    @Test
    void maxRetryAttemptsExhaustedDeliversToSnsOnFailureDestination() {
        stubTrimHorizon(List.of(ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}")));
        InvokeResult err = new InvokeResult();
        err.setFunctionError("Handled");
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(err);

        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm();
        esm.setMaximumRetryAttempts(0); // 0 retries allowed, 1st error exhausts immediately

        String snsArn = "arn:aws:sns:us-east-1:000000000000:my-dlq-topic";
        EventSourceMapping.DestinationConfig destConfig = new EventSourceMapping.DestinationConfig();
        EventSourceMapping.OnFailure onFailure = new EventSourceMapping.OnFailure();
        onFailure.setDestination(snsArn);
        destConfig.setOnFailure(onFailure);
        esm.setDestinationConfig(destConfig);

        DynamoDbStreamsEventSourcePoller p = pollerWith(store);

        p.pollAndInvoke(esm);

        verify(store, timeout(2000)).saveForAccount(eq(ACCOUNT_ID), any());
        assertEquals("s1", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));

        verify(snsService, timeout(2000)).publish(eq(snsArn), any(), anyString(), eq("ESM OnFailure"), eq("us-east-1"));
    }

    @Test
    void maxRetryAttemptsExhaustedTracksRetriesAcrossExpandingBatchWindow() throws Exception {
        when(streamService.getShardIterator(eq(STREAM_ARN), eq(DynamoDbStreamService.SHARD_ID),
                eq("TRIM_HORIZON"), any())).thenReturn("it-1", "it-2");
        when(streamService.getRecords(eq("it-1"), anyInt())).thenReturn(
                new DynamoDbStreamService.GetRecordsResult(
                        List.of(ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}")), "it-1"));
        when(streamService.getRecords(eq("it-2"), anyInt())).thenReturn(
                new DynamoDbStreamService.GetRecordsResult(
                        List.of(ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}"),
                                ddbRecord("s2", "INSERT", "{\"status\":{\"S\":\"active\"}}")), "it-2"));

        InvokeResult err = new InvokeResult();
        err.setFunctionError("Unhandled");
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(err);
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("fn");
        when(functionStore.getForAccount(ACCOUNT_ID, "us-east-1", "fn")).thenReturn(Optional.of(fn));

        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm();
        esm.setMaximumRetryAttempts(1); // 1 retry allowed, 2nd error exhausts

        String sqsArn = "arn:aws:sqs:us-east-1:000000000000:my-dlq";
        EventSourceMapping.DestinationConfig destConfig = new EventSourceMapping.DestinationConfig();
        EventSourceMapping.OnFailure onFailure = new EventSourceMapping.OnFailure();
        onFailure.setDestination(sqsArn);
        destConfig.setOnFailure(onFailure);
        esm.setDestinationConfig(destConfig);

        DynamoDbStreamsEventSourcePoller p = pollerWith(store);

        p.pollAndInvoke(esm);
        verify(executorService, timeout(2000)).invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse));

        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (p.activePolls.isEmpty()) {
                break;
            }
            Thread.sleep(25);
        }

        p.pollAndInvoke(esm);

        verify(store, timeout(2000)).saveForAccount(eq(ACCOUNT_ID), any());
        assertEquals("s2", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        String expectedQueueUrl = "http://localhost:4566/000000000000/my-dlq";
        verify(sqsService, timeout(2000)).sendMessage(eq(expectedQueueUrl), bodyCaptor.capture(), eq(0), eq("us-east-1"));

        JsonNode dlqPayload = OBJECT_MAPPER.readTree(bodyCaptor.getValue());
        assertEquals("1.0", dlqPayload.path("version").asText());
        assertEquals(2, dlqPayload.path("requestContext").path("approximateInvokeCount").asInt());
        assertEquals("s1", dlqPayload.path("DDBStreamBatchInfo").path("startSequenceNumber").asText());
        assertEquals("s2", dlqPayload.path("DDBStreamBatchInfo").path("endSequenceNumber").asText());
        assertEquals(2, dlqPayload.path("DDBStreamBatchInfo").path("batchSize").asInt());
        assertFalse(dlqPayload.has("hasBeenTruncated"));
    }

    @Test
    void reportBatchItemFailuresExhaustsRetriesAndDeliversToOnFailureDestination() throws Exception {
        stubTrimHorizon(List.of(ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}")));
        InvokeResult result = new InvokeResult();
        result.setStatusCode(200);
        result.setRequestId("req-batch-fail");
        result.setPayload("{\"batchItemFailures\":[{\"itemIdentifier\":\"s1\"}]}".getBytes());
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(result);

        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm();
        esm.setFunctionResponseTypes(List.of("ReportBatchItemFailures"));
        esm.setMaximumRetryAttempts(1); // 1 retry attempt allowed, 2nd error exhausts retries

        String sqsArn = "arn:aws:sqs:us-east-1:000000000000:my-dlq";
        EventSourceMapping.DestinationConfig destConfig = new EventSourceMapping.DestinationConfig();
        EventSourceMapping.OnFailure onFailure = new EventSourceMapping.OnFailure();
        onFailure.setDestination(sqsArn);
        destConfig.setOnFailure(onFailure);
        esm.setDestinationConfig(destConfig);

        DynamoDbStreamsEventSourcePoller p = pollerWith(store);

        // First poll: attempt 1 (initial partial failure, retries left)
        p.pollAndInvoke(esm);
        verify(executorService, timeout(2000)).invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse));

        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (p.activePolls.isEmpty()) {
                break;
            }
            Thread.sleep(25);
        }

        // After 1st failure: checkpoint not advanced, no DLQ delivery
        verify(store, never()).saveForAccount(anyString(), any());
        verify(sqsService, never()).sendMessage(anyString(), anyString(), anyInt(), anyString());

        // Second poll: attempt 2 (retry 1, which exceeds maxRetryAttempts=1 -> exhausted!)
        p.pollAndInvoke(esm);

        // Checkpoint advanced to "s1"
        verify(store, timeout(2000)).saveForAccount(eq(ACCOUNT_ID), any());
        assertEquals("s1", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));

        // SQS delivery invoked
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        String expectedQueueUrl = "http://localhost:4566/000000000000/my-dlq";
        verify(sqsService, timeout(2000)).sendMessage(eq(expectedQueueUrl), bodyCaptor.capture(), eq(0), eq("us-east-1"));

        JsonNode dlqPayload = OBJECT_MAPPER.readTree(bodyCaptor.getValue());
        assertEquals("1.0", dlqPayload.path("version").asText());
        assertEquals("RetryAttemptsExhausted", dlqPayload.path("requestContext").path("condition").asText());
        assertEquals(2, dlqPayload.path("requestContext").path("approximateInvokeCount").asInt());
        assertEquals("req-batch-fail", dlqPayload.path("requestContext").path("requestId").asText());
        assertEquals(200, dlqPayload.path("responseContext").path("statusCode").asInt());
        assertFalse(dlqPayload.path("responseContext").has("functionError"));
        assertEquals(DynamoDbStreamService.SHARD_ID, dlqPayload.path("DDBStreamBatchInfo").path("shardId").asText());
        assertEquals("s1", dlqPayload.path("DDBStreamBatchInfo").path("startSequenceNumber").asText());
        assertEquals("s1", dlqPayload.path("DDBStreamBatchInfo").path("endSequenceNumber").asText());
        assertEquals(1, dlqPayload.path("DDBStreamBatchInfo").path("batchSize").asInt());
        assertEquals(STREAM_ARN, dlqPayload.path("DDBStreamBatchInfo").path("streamArn").asText());
        assertFalse(dlqPayload.has("hasBeenTruncated"));
    }

    @Test
    void reportBatchItemFailuresPartialSuccessWithZeroRetriesExhaustsImmediately() throws Exception {
        when(streamService.getShardIterator(eq(STREAM_ARN), eq(DynamoDbStreamService.SHARD_ID),
                eq("TRIM_HORIZON"), any())).thenReturn("it-1");
        when(streamService.getRecords(eq("it-1"), anyInt())).thenReturn(
                new DynamoDbStreamService.GetRecordsResult(
                        List.of(ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}"),
                                ddbRecord("s2", "INSERT", "{\"status\":{\"S\":\"active\"}}")), "it-1"));

        InvokeResult partialFailure = new InvokeResult();
        partialFailure.setStatusCode(200);
        partialFailure.setPayload("{\"batchItemFailures\":[{\"itemIdentifier\":\"s2\"}]}".getBytes());
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(partialFailure);

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("fn");
        when(functionStore.getForAccount(ACCOUNT_ID, "us-east-1", "fn")).thenReturn(Optional.of(fn));

        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm();
        esm.setFunctionResponseTypes(List.of("ReportBatchItemFailures"));
        esm.setMaximumRetryAttempts(0); // 0 retries allowed: initial failure exhausts immediately

        String sqsArn = "arn:aws:sqs:us-east-1:000000000000:my-dlq";
        EventSourceMapping.DestinationConfig destConfig = new EventSourceMapping.DestinationConfig();
        EventSourceMapping.OnFailure onFailure = new EventSourceMapping.OnFailure();
        onFailure.setDestination(sqsArn);
        destConfig.setOnFailure(onFailure);
        esm.setDestinationConfig(destConfig);

        DynamoDbStreamsEventSourcePoller p = pollerWith(store);

        // First poll: s1 succeeds, s2 fails -> maxRetries=0 exhausts immediately
        p.pollAndInvoke(esm);

        // Checkpoint advanced to "s2"
        verify(store, timeout(2000)).saveForAccount(eq(ACCOUNT_ID), any());
        assertEquals("s2", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        String expectedQueueUrl = "http://localhost:4566/000000000000/my-dlq";
        verify(sqsService, timeout(2000)).sendMessage(eq(expectedQueueUrl), bodyCaptor.capture(), eq(0), eq("us-east-1"));

        JsonNode dlqPayload = OBJECT_MAPPER.readTree(bodyCaptor.getValue());
        assertEquals("1.0", dlqPayload.path("version").asText());
        assertEquals("RetryAttemptsExhausted", dlqPayload.path("requestContext").path("condition").asText());
        assertEquals(1, dlqPayload.path("requestContext").path("approximateInvokeCount").asInt());
        assertEquals("s2", dlqPayload.path("DDBStreamBatchInfo").path("startSequenceNumber").asText());
        assertEquals("s2", dlqPayload.path("DDBStreamBatchInfo").path("endSequenceNumber").asText());
        assertEquals(1, dlqPayload.path("DDBStreamBatchInfo").path("batchSize").asInt());

        // Prove there is NO second Lambda invocation for that failed item
        verify(executorService, times(1)).invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void reportBatchItemFailuresPartialSuccessAllowsOneRetryBeforeExhaustion() throws Exception {
        when(streamService.getShardIterator(eq(STREAM_ARN), eq(DynamoDbStreamService.SHARD_ID),
                eq("TRIM_HORIZON"), any())).thenReturn("it-1");
        when(streamService.getShardIterator(eq(STREAM_ARN), eq(DynamoDbStreamService.SHARD_ID),
                eq("AFTER_SEQUENCE_NUMBER"), eq("s1"))).thenReturn("it-2");
        when(streamService.getRecords(eq("it-1"), anyInt())).thenReturn(
                new DynamoDbStreamService.GetRecordsResult(
                        List.of(ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}"),
                                ddbRecord("s2", "INSERT", "{\"status\":{\"S\":\"active\"}}")), "it-1"));
        when(streamService.getRecords(eq("it-2"), anyInt())).thenReturn(
                new DynamoDbStreamService.GetRecordsResult(
                        List.of(ddbRecord("s2", "INSERT", "{\"status\":{\"S\":\"active\"}}")), "it-2"));

        InvokeResult partialFailure = new InvokeResult();
        partialFailure.setStatusCode(200);
        partialFailure.setPayload("{\"batchItemFailures\":[{\"itemIdentifier\":\"s2\"}]}".getBytes());
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(partialFailure);

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("fn");
        when(functionStore.getForAccount(ACCOUNT_ID, "us-east-1", "fn")).thenReturn(Optional.of(fn));

        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm();
        esm.setFunctionResponseTypes(List.of("ReportBatchItemFailures"));
        esm.setMaximumRetryAttempts(1); // 1 retry allowed; 2nd failure exhausts

        String sqsArn = "arn:aws:sqs:us-east-1:000000000000:my-dlq";
        EventSourceMapping.DestinationConfig destConfig = new EventSourceMapping.DestinationConfig();
        EventSourceMapping.OnFailure onFailure = new EventSourceMapping.OnFailure();
        onFailure.setDestination(sqsArn);
        destConfig.setOnFailure(onFailure);
        esm.setDestinationConfig(destConfig);

        DynamoDbStreamsEventSourcePoller p = pollerWith(store);

        // First poll: s1 succeeds, s2 fails -> checkpoint advances to s1, retry count carried over, no DLQ delivery
        p.pollAndInvoke(esm);
        verify(store, timeout(2000)).saveForAccount(eq(ACCOUNT_ID), any());
        assertEquals("s1", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));
        verify(sqsService, never()).sendMessage(anyString(), anyString(), anyInt(), anyString());

        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (p.activePolls.isEmpty()) {
                break;
            }
            Thread.sleep(25);
        }

        // Second poll: fetches [s2], s2 fails again -> maxRetries=1 exhausts (attempt 2, retry 1)
        p.pollAndInvoke(esm);

        verify(store, timeout(2000).times(2)).saveForAccount(eq(ACCOUNT_ID), any());
        assertEquals("s2", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        String expectedQueueUrl = "http://localhost:4566/000000000000/my-dlq";
        verify(sqsService, timeout(2000)).sendMessage(eq(expectedQueueUrl), bodyCaptor.capture(), eq(0), eq("us-east-1"));

        JsonNode dlqPayload = OBJECT_MAPPER.readTree(bodyCaptor.getValue());
        assertEquals("1.0", dlqPayload.path("version").asText());
        assertEquals("RetryAttemptsExhausted", dlqPayload.path("requestContext").path("condition").asText());
        assertEquals(2, dlqPayload.path("requestContext").path("approximateInvokeCount").asInt());
        assertEquals("s2", dlqPayload.path("DDBStreamBatchInfo").path("startSequenceNumber").asText());
        assertEquals("s2", dlqPayload.path("DDBStreamBatchInfo").path("endSequenceNumber").asText());
        assertEquals(1, dlqPayload.path("DDBStreamBatchInfo").path("batchSize").asInt());

        verify(executorService, times(2)).invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    private JsonNode readRecords(byte[] payload) {
        try {
            return OBJECT_MAPPER.readTree(payload).path("Records");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
