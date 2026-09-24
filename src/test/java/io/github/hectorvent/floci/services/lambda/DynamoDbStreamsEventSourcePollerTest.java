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
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

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
import static org.mockito.Mockito.verifyNoInteractions;
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
    private LambdaAliasStore aliasStore;
    private EsmStore esmStore;
    private EmulatorConfig config;
    private PipesFilterMatcher filterMatcher;
    private io.github.hectorvent.floci.services.sqs.SqsService sqsService;
    private io.github.hectorvent.floci.services.sns.SnsService snsService;
    private io.github.hectorvent.floci.services.s3.S3Service s3Service;
    private final AtomicLong clock = new AtomicLong();

    @Inject
    Instance<RequestContext> requestContextInstance;

    @BeforeEach
    void setUp() {
        clock.set(1_800_000_000_000L);
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
        aliasStore = mock(LambdaAliasStore.class);
        esmStore = new EsmStore(new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT_ID));
        filterMatcher = new PipesFilterMatcher(OBJECT_MAPPER);
        sqsService = mock(io.github.hectorvent.floci.services.sqs.SqsService.class);
        snsService = mock(io.github.hectorvent.floci.services.sns.SnsService.class);
        s3Service = mock(io.github.hectorvent.floci.services.s3.S3Service.class);

        // A mocked Vertx makes setPeriodic a no-op, so startPolling registers no live timer and
        // the tests drive pollAndInvoke deterministically.
        poller = new DynamoDbStreamsEventSourcePoller(
                mock(Vertx.class), streamService, executorService, new LambdaTargetResolver(functionStore, aliasStore),
                esmStore, OBJECT_MAPPER, config, filterMatcher, sqsService, snsService, s3Service, clock::get);
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
    void startPersistedPollersDiscardsDisabledMappingCheckpointsWithoutPolling() {
        EventSourceMapping esm = persistedStreamsEsmWithStaleCheckpoint();
        esm.setEnabled(false);
        esmStore.saveForAccount(ACCOUNT_ID, esm);
        Vertx vertx = mock(Vertx.class);
        DynamoDbStreamsEventSourcePoller p = new DynamoDbStreamsEventSourcePoller(
                vertx, streamService, executorService, new LambdaTargetResolver(functionStore, aliasStore),
                esmStore, OBJECT_MAPPER, config, filterMatcher, sqsService, snsService, s3Service, clock::get);

        p.startPersistedPollers();

        EventSourceMapping reloaded = esmStore.getForAccount(ACCOUNT_ID, "esm-1").orElseThrow();
        assertTrue(reloaded.getShardSequenceNumbers().isEmpty(),
                "a disabled mapping's checkpoint is just as stale after restart, and enabling it later "
                        + "would otherwise skip every new record");
        verifyNoInteractions(vertx);
    }

    @Test
    void startPersistedPollersSkipsADisabledMappingWithoutAnEventSourceArn() {
        EventSourceMapping kafka = new EventSourceMapping();
        kafka.setUuid("esm-kafka");
        kafka.setAccountId(ACCOUNT_ID);
        kafka.setEnabled(false);
        esmStore.saveForAccount(ACCOUNT_ID, kafka);

        poller.startPersistedPollers();

        assertTrue(esmStore.getForAccount(ACCOUNT_ID, "esm-kafka").isPresent());
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
        // The mock records writes for verification; reads see what filterEsm stored, as a real store would.
        when(store.getForAccount(anyString(), anyString()))
                .thenAnswer(inv -> esmStore.getForAccount(inv.getArgument(0), inv.getArgument(1)));
        return new DynamoDbStreamsEventSourcePoller(
                mock(Vertx.class), streamService, executorService, new LambdaTargetResolver(functionStore, aliasStore),
                store, OBJECT_MAPPER, config, filterMatcher, sqsService, snsService, s3Service, clock::get);
    }

    private void advancePastRetry(DynamoDbStreamsEventSourcePoller poller) {
        clock.addAndGet(poller.retryBackoffMs(1));
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
        esmStore.saveForAccount(ACCOUNT_ID, esm);
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
    void versionQualifiedMappingInvokesThatVersion() {
        stubTrimHorizon(List.of(ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}")));
        LambdaFunction version1 = stubVersion("1");
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult());
        EventSourceMapping esm = filterEsm();
        esm.setFunctionArn("arn:aws:lambda:us-east-1:" + ACCOUNT_ID + ":function:fn:1");

        pollerWith(mock(EsmStore.class)).pollAndInvoke(esm);

        verify(executorService, timeout(2000)).invoke(eq(version1), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void aliasQualifiedMappingInvokesTheAliasVersion() {
        stubTrimHorizon(List.of(ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}")));
        LambdaFunction version2 = stubVersion("2");
        LambdaAlias alias = new LambdaAlias();
        alias.setName("live");
        alias.setFunctionName("fn");
        alias.setFunctionVersion("2");
        when(aliasStore.getForAccount(ACCOUNT_ID, "us-east-1", "fn", "live")).thenReturn(Optional.of(alias));
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult());
        EventSourceMapping esm = filterEsm();
        esm.setFunctionArn("arn:aws:lambda:us-east-1:" + ACCOUNT_ID + ":function:fn:live");

        pollerWith(mock(EsmStore.class)).pollAndInvoke(esm);

        verify(executorService, timeout(2000)).invoke(eq(version2), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    private LambdaFunction stubVersion(String version) {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("fn");
        fn.setVersion(version);
        when(functionStore.getForAccount(ACCOUNT_ID, "us-east-1", "fn", version)).thenReturn(Optional.of(fn));
        return fn;
    }

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
        advancePastRetry(p);
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
        advancePastRetry(p);
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

        advancePastRetry(p);
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
        advancePastRetry(p);
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
        advancePastRetry(p);
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

    @Test
    void failingBatchIsNotReinvokedUntilItsBackoffElapses() throws Exception {
        stubTrimHorizon(List.of(ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}")));
        InvokeResult error = new InvokeResult();
        error.setFunctionError("Unhandled");
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(error);

        EventSourceMapping esm = filterEsm();
        DynamoDbStreamsEventSourcePoller p = pollerWith(mock(EsmStore.class));

        p.pollAndInvoke(esm);
        awaitPollCompleted(p);
        p.pollAndInvoke(esm);
        awaitPollCompleted(p);
        verify(executorService, times(1)).invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse));

        advancePastRetry(p);
        p.pollAndInvoke(esm);
        verify(executorService, timeout(2000).times(2)).invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void retryBackoffDoublesFromThePollIntervalAndCapsOut() {
        assertEquals(2_000, poller.retryBackoffMs(1));
        assertEquals(4_000, poller.retryBackoffMs(2));
        assertEquals(DynamoDbStreamsEventSourcePoller.MAX_RETRY_BACKOFF_MS, poller.retryBackoffMs(20));
    }

    @Test
    void batchOlderThan24HoursWithUnlimitedMaximumRecordAgeIsRetried() throws Exception {
        EventSourceMapping esm = filterEsm();
        esm.setMaximumRecordAgeInSeconds(-1);

        assertOldBatchIsRetriedWithoutAnAgeCutoff(esm);
    }

    @Test
    void batchOlderThan24HoursWithUnsetMaximumRecordAgeIsRetried() throws Exception {
        EventSourceMapping esm = filterEsm();

        assertOldBatchIsRetriedWithoutAnAgeCutoff(esm);
    }

    private void assertOldBatchIsRetriedWithoutAnAgeCutoff(EventSourceMapping esm) throws Exception {
        DynamoDbStreamRecord record = ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}");
        record.setApproximateCreationDateTime(clock.get() / 1_000 - 86_401);
        stubTrimHorizon(List.of(record));
        InvokeResult error = new InvokeResult();
        error.setFunctionError("Unhandled");
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(error);

        EventSourceMapping.OnFailure onFailure = new EventSourceMapping.OnFailure();
        onFailure.setDestination("arn:aws:sqs:us-east-1:000000000000:my-dlq");
        EventSourceMapping.DestinationConfig destinationConfig = new EventSourceMapping.DestinationConfig();
        destinationConfig.setOnFailure(onFailure);
        esm.setDestinationConfig(destinationConfig);

        EsmStore store = mock(EsmStore.class);
        DynamoDbStreamsEventSourcePoller p = pollerWith(store);

        p.pollAndInvoke(esm);
        awaitPollCompleted(p);

        verify(store, never()).saveForAccount(anyString(), any());
        verify(sqsService, never()).sendMessage(anyString(), anyString(), anyInt(), anyString());

        advancePastRetry(p);
        p.pollAndInvoke(esm);
        verify(executorService, timeout(2000).times(2))
                .invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void batchOlderThanMaximumRecordAgeIsDiscardedAndDeliveredToOnFailure() throws Exception {
        DynamoDbStreamRecord record = ddbRecord("s1", "INSERT", "{\"status\":{\"S\":\"active\"}}");
        record.setApproximateCreationDateTime(clock.get() / 1_000 - 61);
        stubTrimHorizon(List.of(record));
        InvokeResult error = new InvokeResult();
        error.setFunctionError("Unhandled");
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(error);

        EventSourceMapping esm = filterEsm();
        esm.setMaximumRecordAgeInSeconds(60);
        EventSourceMapping.OnFailure onFailure = new EventSourceMapping.OnFailure();
        onFailure.setDestination("arn:aws:sqs:us-east-1:000000000000:my-dlq");
        EventSourceMapping.DestinationConfig destinationConfig = new EventSourceMapping.DestinationConfig();
        destinationConfig.setOnFailure(onFailure);
        esm.setDestinationConfig(destinationConfig);

        EsmStore store = mock(EsmStore.class);
        pollerWith(store).pollAndInvoke(esm);

        verify(store, timeout(2000)).saveForAccount(eq(ACCOUNT_ID), any());
        assertEquals("s1", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(sqsService, timeout(2000)).sendMessage(anyString(), bodyCaptor.capture(), anyInt(), anyString());
        JsonNode payload = OBJECT_MAPPER.readTree(bodyCaptor.getValue());
        assertEquals("MaximumRecordAgeExceeded", payload.path("requestContext").path("condition").asText());
    }

    // ──────────────────── BisectBatchOnFunctionError and refused destinations ────────────────────

    /**
     * Serves the listed sequences as the stream and returns it for trimming or appending. The iterator
     * is the sequence to read after, or empty for the trim horizon; a checkpoint missing from the stream
     * behaves as trimmed.
     */
    private List<DynamoDbStreamRecord> stubStream(String... sequences) {
        List<DynamoDbStreamRecord> stream = new CopyOnWriteArrayList<>();
        for (String seq : sequences) {
            stream.add(ddbRecord(seq, "INSERT", "{}"));
        }
        when(streamService.getShardIterator(eq(STREAM_ARN), eq(DynamoDbStreamService.SHARD_ID), anyString(), any()))
                .thenAnswer(inv -> "TRIM_HORIZON".equals(inv.getArgument(2)) ? "" : inv.getArgument(3));
        when(streamService.getRecords(anyString(), anyInt())).thenAnswer(inv -> {
            String after = inv.getArgument(0);
            List<DynamoDbStreamRecord> snapshot = new ArrayList<>(stream);
            int from = after.isEmpty() ? 0
                    : snapshot.stream().map(DynamoDbStreamRecord::getSequenceNumber).toList().indexOf(after) + 1;
            if (!after.isEmpty() && from == 0) {
                throw new AwsException("TrimmedDataAccessException", "trimmed", 400);
            }
            int limit = inv.getArgument(1);
            return new DynamoDbStreamService.GetRecordsResult(
                    new ArrayList<>(snapshot.subList(from, Math.min(from + limit, snapshot.size()))), "unused");
        });
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("fn");
        when(functionStore.getForAccount(ACCOUNT_ID, "us-east-1", "fn")).thenReturn(Optional.of(fn));
        return stream;
    }

    /** Records the sequences of every delivered batch and answers each with {@code respond}. */
    private List<List<String>> recordInvocations(Function<List<String>, InvokeResult> respond) {
        List<List<String>> invocations = new CopyOnWriteArrayList<>();
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse))).thenAnswer(inv -> {
            List<String> seqs = new ArrayList<>();
            readRecords(inv.getArgument(1)).forEach(r -> seqs.add(r.path("dynamodb").path("SequenceNumber").asText()));
            invocations.add(seqs);
            return respond.apply(seqs);
        });
        return invocations;
    }

    private List<List<String>> failInvocationsContaining(String poison) {
        return recordInvocations(seqs -> {
            InvokeResult result = new InvokeResult();
            if (seqs.contains(poison)) {
                result.setFunctionError("Unhandled");
            }
            return result;
        });
    }

    private static InvokeResult partialFailure(String sequence) {
        InvokeResult result = new InvokeResult();
        result.setPayload(("{\"batchItemFailures\":[{\"itemIdentifier\":\"" + sequence + "\"}]}").getBytes());
        return result;
    }

    /** Refuses the first {@code refusals} SQS sends and returns the bodies of the accepted ones. */
    private List<String> refuseSqsSends(int refusals) {
        List<String> delivered = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        when(sqsService.sendMessage(anyString(), anyString(), anyInt(), anyString())).thenAnswer(inv -> {
            if (attempts.getAndIncrement() < refusals) {
                throw new AwsException("AWS.SimpleQueueService.NonExistentQueue", "The specified queue does not exist.", 400);
            }
            delivered.add(inv.getArgument(1));
            return null;
        });
        return delivered;
    }

    private EventSourceMapping esmWithDlq(int maximumRetryAttempts) {
        EventSourceMapping esm = filterEsm();
        esm.setMaximumRetryAttempts(maximumRetryAttempts);
        EventSourceMapping.OnFailure onFailure = new EventSourceMapping.OnFailure();
        onFailure.setDestination("arn:aws:sqs:us-east-1:000000000000:my-dlq");
        EventSourceMapping.DestinationConfig destinationConfig = new EventSourceMapping.DestinationConfig();
        destinationConfig.setOnFailure(onFailure);
        esm.setDestinationConfig(destinationConfig);
        return esm;
    }

    private void pollOnce(DynamoDbStreamsEventSourcePoller p, EventSourceMapping esm) throws InterruptedException {
        p.pollAndInvoke(esm);
        awaitPollCompleted(p);
    }

    private String checkpoint(EventSourceMapping esm) {
        return esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID);
    }

    private JsonNode batchInfo(String body) throws Exception {
        return OBJECT_MAPPER.readTree(body).path("DDBStreamBatchInfo");
    }

    /** Also covers a trimmed checkpoint, where each poll refetches from the trim horizon. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = STALE_CHECKPOINT)
    void bisectIsolatesThePoisonRecordAndDeliversTheRest(String startingCheckpoint) throws Exception {
        stubStream("s1", "s2", "s3", "s4");
        List<List<String>> invocations = failInvocationsContaining("s3");
        EventSourceMapping esm = esmWithDlq(0);
        esm.setBisectBatchOnFunctionError(true);
        if (startingCheckpoint != null) {
            esm.getShardSequenceNumbers().put(DynamoDbStreamService.SHARD_ID, startingCheckpoint);
        }
        DynamoDbStreamsEventSourcePoller p = pollerWith(mock(EsmStore.class));

        for (int i = 0; i < 6; i++) {
            pollOnce(p, esm);
        }

        assertEquals(List.of(List.of("s1", "s2", "s3", "s4"), List.of("s1", "s2"), List.of("s3", "s4"),
                List.of("s3"), List.of("s4")), invocations);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sqsService).sendMessage(anyString(), body.capture(), anyInt(), anyString());
        assertEquals("s3", batchInfo(body.getValue()).path("startSequenceNumber").asText());
        assertEquals("s3", batchInfo(body.getValue()).path("endSequenceNumber").asText());
        assertEquals(1, batchInfo(body.getValue()).path("batchSize").asInt());
        assertEquals("s4", checkpoint(esm));
    }

    @Test
    void bisectSplitsDoNotConsumeRetryAttempts() throws Exception {
        stubStream("s1", "s2", "s3", "s4");
        List<List<String>> invocations = failInvocationsContaining("s3");
        EventSourceMapping esm = esmWithDlq(1);
        esm.setBisectBatchOnFunctionError(true);
        DynamoDbStreamsEventSourcePoller p = pollerWith(mock(EsmStore.class));

        for (int i = 0; i < 4; i++) {
            pollOnce(p, esm);
        }
        assertEquals(List.of(List.of("s1", "s2", "s3", "s4"), List.of("s1", "s2"), List.of("s3", "s4"),
                List.of("s3")), invocations);
        verify(sqsService, never()).sendMessage(anyString(), anyString(), anyInt(), anyString());

        advancePastRetry(p);
        pollOnce(p, esm);

        assertEquals(List.of("s3"), invocations.get(4));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sqsService).sendMessage(anyString(), body.capture(), anyInt(), anyString());
        assertEquals(2, OBJECT_MAPPER.readTree(body.getValue())
                .path("requestContext").path("approximateInvokeCount").asInt());
    }

    @Test
    void halvedWindowFullyFilteredOutDoesNotLeakItsLimitIntoALaterHorizonRefetch() throws Exception {
        List<DynamoDbStreamRecord> stream = stubStream("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8");
        stream.subList(0, 4).forEach(r -> r.setEventName("MODIFY"));
        List<List<String>> invocations = failInvocationsContaining("s5");
        EventSourceMapping esm = filterEsm("{\"eventName\":[\"INSERT\"]}");
        esm.setBisectBatchOnFunctionError(true);
        DynamoDbStreamsEventSourcePoller p = pollerWith(mock(EsmStore.class));

        pollOnce(p, esm);
        pollOnce(p, esm);
        assertEquals("s4", checkpoint(esm), "the halved window [s1..s4] is fully filtered out");
        stream.removeIf(r -> "MODIFY".equals(r.getEventName()));
        stream.add(ddbRecord("s9", "INSERT", "{}"));
        pollOnce(p, esm);

        assertEquals(List.of("s5", "s6", "s7", "s8", "s9"), invocations.get(1),
                "the trimmed checkpoint refetches the horizon at the full BatchSize");
    }

    @Test
    void bisectLimitNeverExceedsALaterSmallerBatchSize() throws Exception {
        stubStream("s1", "s2", "s3", "s4");
        List<List<String>> invocations = failInvocationsContaining("s1");
        EventSourceMapping esm = filterEsm();
        esm.setBisectBatchOnFunctionError(true);
        DynamoDbStreamsEventSourcePoller p = pollerWith(mock(EsmStore.class));

        pollOnce(p, esm);
        esm.setBatchSize(1);
        pollOnce(p, esm);

        assertEquals(List.of(List.of("s1", "s2", "s3", "s4"), List.of("s1")), invocations);
    }

    @Test
    void turningBisectOffMidSplitRestoresTheFullBatchSize() throws Exception {
        stubStream("s1", "s2", "s3", "s4");
        List<List<String>> invocations = failInvocationsContaining("s1");
        EventSourceMapping esm = filterEsm();
        esm.setBisectBatchOnFunctionError(true);
        DynamoDbStreamsEventSourcePoller p = pollerWith(mock(EsmStore.class));

        pollOnce(p, esm);
        esm.setBisectBatchOnFunctionError(false);
        advancePastRetry(p);
        pollOnce(p, esm);

        assertEquals(List.of(List.of("s1", "s2", "s3", "s4"), List.of("s1", "s2", "s3", "s4")), invocations);
    }

    @Test
    void bisectSplitsOnFunctionErrorEvenWithReportBatchItemFailures() throws Exception {
        stubStream("s1", "s2", "s3", "s4");
        List<List<String>> invocations = failInvocationsContaining("s3");
        EventSourceMapping esm = esmWithDlq(0);
        esm.setBisectBatchOnFunctionError(true);
        esm.setFunctionResponseTypes(List.of("ReportBatchItemFailures"));
        DynamoDbStreamsEventSourcePoller p = pollerWith(mock(EsmStore.class));

        pollOnce(p, esm);
        pollOnce(p, esm);

        assertEquals(List.of(List.of("s1", "s2", "s3", "s4"), List.of("s1", "s2")), invocations);
        assertEquals("s2", checkpoint(esm));
    }

    @Test
    void partialBatchResponseWithBisectRetriesFromTheFailedRecordWithoutSplitting() throws Exception {
        stubStream("s1", "s2", "s3", "s4");
        List<List<String>> invocations = recordInvocations(
                seqs -> seqs.contains("s3") ? partialFailure("s3") : new InvokeResult());
        EventSourceMapping esm = filterEsm();
        esm.setBisectBatchOnFunctionError(true);
        esm.setFunctionResponseTypes(List.of("ReportBatchItemFailures"));
        DynamoDbStreamsEventSourcePoller p = pollerWith(mock(EsmStore.class));

        pollOnce(p, esm);
        assertEquals("s2", checkpoint(esm));
        advancePastRetry(p);
        pollOnce(p, esm);

        assertEquals(List.of(List.of("s1", "s2", "s3", "s4"), List.of("s3", "s4")), invocations);
    }

    @Test
    void throttledBatchIsNotBisected() throws Exception {
        stubStream("s1", "s2", "s3", "s4");
        AtomicBoolean throttled = new AtomicBoolean();
        List<List<String>> invocations = recordInvocations(seqs -> {
            if (throttled.compareAndSet(false, true)) {
                throw new AwsException("TooManyRequestsException", "throttled", 429);
            }
            return new InvokeResult();
        });
        EventSourceMapping esm = filterEsm();
        esm.setBisectBatchOnFunctionError(true);
        DynamoDbStreamsEventSourcePoller p = pollerWith(mock(EsmStore.class));

        pollOnce(p, esm);
        pollOnce(p, esm);

        assertEquals(List.of(List.of("s1", "s2", "s3", "s4"), List.of("s1", "s2", "s3", "s4")), invocations);
        assertEquals("s4", checkpoint(esm));
    }

    @Test
    void refusedOnFailureDestinationKeepsTheBatchAndRetriesTheSend() throws Exception {
        stubStream("s1");
        List<List<String>> invocations = failInvocationsContaining("s1");
        List<String> delivered = refuseSqsSends(1);
        EventSourceMapping esm = esmWithDlq(0);
        DynamoDbStreamsEventSourcePoller p = pollerWith(mock(EsmStore.class));

        pollOnce(p, esm);
        assertNull(checkpoint(esm), "a refused send must not checkpoint the discarded batch");
        pollOnce(p, esm);
        verify(sqsService, times(1)).sendMessage(anyString(), anyString(), anyInt(), anyString());

        advancePastRetry(p);
        pollOnce(p, esm);

        assertEquals(List.of(List.of("s1")), invocations, "the parked batch is re-sent, not re-invoked");
        assertEquals(1, delivered.size());
        assertEquals("s1", batchInfo(delivered.get(0)).path("startSequenceNumber").asText());
        assertEquals("s1", batchInfo(delivered.get(0)).path("endSequenceNumber").asText());
        assertEquals("s1", checkpoint(esm));
    }

    @Test
    void parkedFailureIsResentAfterTheFunctionStopsResolving() throws Exception {
        stubStream("s1");
        failInvocationsContaining("s1");
        List<String> delivered = refuseSqsSends(1);
        EventSourceMapping esm = esmWithDlq(0);
        DynamoDbStreamsEventSourcePoller p = pollerWith(mock(EsmStore.class));

        pollOnce(p, esm);
        assertNull(checkpoint(esm));
        when(functionStore.getForAccount(ACCOUNT_ID, "us-east-1", "fn")).thenReturn(Optional.empty());
        advancePastRetry(p);
        pollOnce(p, esm);

        assertEquals(1, delivered.size(), "the resend does not need the function");
        assertEquals("s1", checkpoint(esm));
    }

    @Test
    void refusedOnFailureDestinationAfterPartialSuccessKeepsTheAcknowledgedPrefix() throws Exception {
        stubStream("s1", "s2", "s3");
        List<List<String>> invocations = recordInvocations(seqs -> partialFailure("s2"));
        List<String> delivered = refuseSqsSends(1);
        EventSourceMapping esm = esmWithDlq(0);
        esm.setFunctionResponseTypes(List.of("ReportBatchItemFailures"));
        DynamoDbStreamsEventSourcePoller p = pollerWith(mock(EsmStore.class));

        pollOnce(p, esm);
        assertEquals("s1", checkpoint(esm));

        advancePastRetry(p);
        pollOnce(p, esm);

        assertEquals(1, invocations.size());
        assertEquals(1, delivered.size());
        assertEquals("s2", batchInfo(delivered.get(0)).path("startSequenceNumber").asText());
        assertEquals("s3", batchInfo(delivered.get(0)).path("endSequenceNumber").asText());
        assertEquals("s3", checkpoint(esm));
    }

    @Test
    void exhaustedBatchWithoutOnFailureDestinationIsDiscarded() throws Exception {
        stubStream("s1");
        failInvocationsContaining("s1");
        EventSourceMapping esm = filterEsm();
        esm.setMaximumRetryAttempts(0);
        DynamoDbStreamsEventSourcePoller p = pollerWith(mock(EsmStore.class));

        pollOnce(p, esm);

        assertEquals("s1", checkpoint(esm));
    }

    // ──────────────────── StartingPosition and mapping lifecycle (#4311) ────────────────────

    private static final String TABLE_ARN = "arn:aws:dynamodb:us-east-1:000000000000:table/t";

    private DynamoDbStreamService realStream() {
        DynamoDbStreamService streams = new DynamoDbStreamService(OBJECT_MAPPER, mock(StorageFactory.class));
        streams.enableStream("t", TABLE_ARN, "NEW_AND_OLD_IMAGES", "us-east-1", STREAM_ARN);
        return streams;
    }

    private void write(DynamoDbStreamService streams, String pk) {
        TableDefinition table = new TableDefinition("t", List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")), "us-east-1", ACCOUNT_ID);
        ObjectNode item = OBJECT_MAPPER.createObjectNode();
        item.putObject("pk").put("S", pk);
        streams.captureEvent("t", "INSERT", null, item, table, "us-east-1");
    }

    private DynamoDbStreamsEventSourcePoller pollerOver(DynamoDbStreamService streams) {
        return new DynamoDbStreamsEventSourcePoller(
                mock(Vertx.class), streams, executorService, new LambdaTargetResolver(functionStore, aliasStore),
                esmStore, OBJECT_MAPPER, config, filterMatcher, sqsService, snsService, s3Service, clock::get);
    }

    private EventSourceMapping streamsEsm(String uuid, String startingPosition) {
        EventSourceMapping esm = new EventSourceMapping();
        esm.setUuid(uuid);
        esm.setAccountId(ACCOUNT_ID);
        esm.setRegion("us-east-1");
        esm.setFunctionName("fn");
        esm.setEventSourceArn(STREAM_ARN);
        esm.setBatchSize(10);
        esm.setEnabled(true);
        esm.setStartingPosition(startingPosition);
        return esm;
    }

    private void stubFunctionSucceeds() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("fn");
        when(functionStore.getForAccount(ACCOUNT_ID, "us-east-1", "fn")).thenReturn(Optional.of(fn));
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult());
    }

    private List<String> deliveredKeys(byte[] payload) {
        List<String> keys = new ArrayList<>();
        for (JsonNode rec : readRecords(payload)) {
            keys.add(rec.path("dynamodb").path("Keys").path("pk").path("S").asText());
        }
        return keys;
    }

    /** One-record read that signals {@code reading} and then holds until {@code release} opens. */
    private void stubBlockingRead(CountDownLatch reading, CountDownLatch release) {
        stubTrimHorizon(List.of());
        when(streamService.getRecords("it", 10)).thenAnswer(inv -> {
            reading.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new DynamoDbStreamService.GetRecordsResult(List.of(ddbRecord("s1", "INSERT", "{}")), "it");
        });
    }

    /** Invocation that signals {@code invoking} and then holds until {@code release} opens. */
    private void stubBlockingInvoke(CountDownLatch invoking, CountDownLatch release, InvokeResult result) {
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(inv -> {
                    invoking.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return result;
                });
    }

    @Test
    void latestMappingDeliversOnlyRecordsWrittenAfterItWasCreated() {
        DynamoDbStreamService streams = realStream();
        write(streams, "old-1");
        write(streams, "old-2");
        stubFunctionSucceeds();
        DynamoDbStreamsEventSourcePoller p = pollerOver(streams);
        EventSourceMapping esm = streamsEsm("esm-latest", "LATEST");
        p.initializeStartingPosition(esm);
        esmStore.saveForAccount(ACCOUNT_ID, esm);

        write(streams, "new");
        p.pollAndInvoke(esm);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(executorService, timeout(2000)).invoke(any(), payload.capture(), eq(InvocationType.RequestResponse));
        assertEquals(List.of("new"), deliveredKeys(payload.getValue()));
    }

    @Test
    void latestMappingOnAnEmptyStreamDeliversEveryLaterRecord() throws Exception {
        DynamoDbStreamService streams = realStream();
        stubFunctionSucceeds();
        DynamoDbStreamsEventSourcePoller p = pollerOver(streams);
        EventSourceMapping esm = streamsEsm("esm-latest", "LATEST");
        p.initializeStartingPosition(esm);
        esmStore.saveForAccount(ACCOUNT_ID, esm);
        assertTrue(esm.getShardSequenceNumbers().isEmpty(), "an empty stream has no record to start after");

        p.pollAndInvoke(esm);
        awaitPollCompleted(p);
        assertTrue(esm.getShardSequenceNumbers().isEmpty(), "an empty poll must not pin the cursor");
        verify(executorService, never()).invoke(any(), any(byte[].class), any());

        write(streams, "first");
        p.pollAndInvoke(esm);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(executorService, timeout(2000)).invoke(any(), payload.capture(), eq(InvocationType.RequestResponse));
        assertEquals(List.of("first"), deliveredKeys(payload.getValue()));
    }

    @Test
    void trimHorizonMappingIsNotPinnedAtCreation() {
        DynamoDbStreamService streams = realStream();
        write(streams, "old");
        EventSourceMapping esm = streamsEsm("esm-trim", "TRIM_HORIZON");

        pollerOver(streams).initializeStartingPosition(esm);

        assertTrue(esm.getShardSequenceNumbers().isEmpty());
    }

    @Test
    void reEnabledMappingResumesAfterItsLastProcessedRecord() throws Exception {
        DynamoDbStreamService streams = realStream();
        write(streams, "old");
        stubFunctionSucceeds();
        DynamoDbStreamsEventSourcePoller p = pollerOver(streams);
        EventSourceMapping esm = streamsEsm("esm-latest", "LATEST");
        p.initializeStartingPosition(esm);
        esmStore.saveForAccount(ACCOUNT_ID, esm);
        write(streams, "a");
        p.pollAndInvoke(esm);
        verify(executorService, timeout(2000)).invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse));
        awaitPollCompleted(p);

        esm.setEnabled(false);
        esmStore.saveForAccount(ACCOUNT_ID, esm);
        p.stopPolling(esm.getUuid());
        write(streams, "b");
        esm.setEnabled(true);
        esmStore.saveForAccount(ACCOUNT_ID, esm);
        p.startPolling(esm);
        p.pollAndInvoke(esm);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(executorService, timeout(2000).times(2))
                .invoke(any(), payload.capture(), eq(InvocationType.RequestResponse));
        assertEquals(List.of("a"), deliveredKeys(payload.getAllValues().get(0)));
        assertEquals(List.of("b"), deliveredKeys(payload.getAllValues().get(1)),
                "re-enabling resumes after the last processed record, neither from the trim horizon nor LATEST");
    }

    @Test
    void mappingDeletedDuringABlockedReadIsNotInvokedOrResurrected() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stubBlockingRead(reading, release);
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult());
        EventSourceMapping esm = streamsEsm("esm-live", null);
        esmStore.saveForAccount(ACCOUNT_ID, esm);

        poller.pollAndInvoke(esm);
        assertTrue(reading.await(2, TimeUnit.SECONDS));
        poller.stopPolling(esm.getUuid());
        esmStore.delete(esm.getUuid());
        release.countDown();
        awaitPollCompleted(poller);

        verify(executorService, never()).invoke(any(), any(byte[].class), any());
        assertTrue(esmStore.getForAccount(ACCOUNT_ID, esm.getUuid()).isEmpty(),
                "a deleted mapping must not reappear in List/Get");
    }

    @Test
    void mappingDisabledDuringABlockedReadIsNotInvoked() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stubBlockingRead(reading, release);
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult());
        EventSourceMapping esm = streamsEsm("esm-live", null);
        esmStore.saveForAccount(ACCOUNT_ID, esm);

        poller.pollAndInvoke(esm);
        assertTrue(reading.await(2, TimeUnit.SECONDS));
        esm.setEnabled(false);
        esmStore.saveForAccount(ACCOUNT_ID, esm);
        poller.stopPolling(esm.getUuid());
        release.countDown();
        awaitPollCompleted(poller);

        verify(executorService, never()).invoke(any(), any(byte[].class), any());
    }

    @Test
    void mappingDeletedDuringASuccessfulInvokeDropsTheResult() throws Exception {
        assertDeletionDuringInvokeDropsTheResult(new InvokeResult());
    }

    @Test
    void mappingDeletedDuringAFailedInvokeSendsNothingToItsDestination() throws Exception {
        InvokeResult error = new InvokeResult();
        error.setFunctionError("Unhandled");
        assertDeletionDuringInvokeDropsTheResult(error);
    }

    private void assertDeletionDuringInvokeDropsTheResult(InvokeResult result) throws Exception {
        stubTrimHorizon(List.of(ddbRecord("s1", "INSERT", "{}")));
        CountDownLatch invoking = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stubBlockingInvoke(invoking, release, result);
        EventSourceMapping esm = streamsEsm("esm-live", null);
        esm.setMaximumRetryAttempts(0);
        EventSourceMapping.OnFailure onFailure = new EventSourceMapping.OnFailure();
        onFailure.setDestination("arn:aws:sqs:us-east-1:000000000000:my-dlq");
        EventSourceMapping.DestinationConfig destinationConfig = new EventSourceMapping.DestinationConfig();
        destinationConfig.setOnFailure(onFailure);
        esm.setDestinationConfig(destinationConfig);
        esmStore.saveForAccount(ACCOUNT_ID, esm);

        poller.pollAndInvoke(esm);
        assertTrue(invoking.await(2, TimeUnit.SECONDS));
        poller.stopPolling(esm.getUuid());
        esmStore.delete(esm.getUuid());
        release.countDown();
        awaitPollCompleted(poller);

        assertTrue(esmStore.getForAccount(ACCOUNT_ID, esm.getUuid()).isEmpty(),
                "a deleted mapping must not reappear in List/Get");
        assertTrue(esm.getShardSequenceNumbers().isEmpty(), "no checkpoint is written for a deleted mapping");
        verify(sqsService, never()).sendMessage(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void mappingDeletedDuringABisectingFailedInvokeLeavesNoBisectStateOrDestinationSend() throws Exception {
        stubStream("s1", "s2", "s3", "s4");
        EventSourceMapping esm = esmWithDlq(0);
        esm.setBisectBatchOnFunctionError(true);
        DynamoDbStreamsEventSourcePoller p = pollerWith(mock(EsmStore.class));
        List<List<String>> invocations = recordInvocations(seqs -> {
            p.stopPolling(esm.getUuid());
            esmStore.delete(esm.getUuid());
            InvokeResult error = new InvokeResult();
            error.setFunctionError("Unhandled");
            return error;
        });

        pollOnce(p, esm);
        pollOnce(p, esm);

        assertEquals(List.of(List.of("s1", "s2", "s3", "s4")), invocations);
        // A recorded bisect would halve the next fetch; both fetches must still use the full batch size.
        verify(streamService, times(2)).getRecords(anyString(), eq(10));
        assertNull(checkpoint(esm));
        verify(sqsService, never()).sendMessage(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void parkedFailureIsNotResentOnceTheMappingIsStopped() throws Exception {
        stubStream("s1");
        List<List<String>> invocations = failInvocationsContaining("s1");
        refuseSqsSends(1);
        EventSourceMapping esm = esmWithDlq(0);
        DynamoDbStreamsEventSourcePoller p = pollerWith(mock(EsmStore.class));
        pollOnce(p, esm);
        assertNull(checkpoint(esm));
        // A worker that loaded the parked entry before stopPolling cleared it sees only the tombstone.
        p.stopped.add(esm.getUuid());

        advancePastRetry(p);
        pollOnce(p, esm);

        verify(sqsService, times(1)).sendMessage(anyString(), anyString(), anyInt(), anyString());
        assertNull(checkpoint(esm));
        assertEquals(List.of(List.of("s1")), invocations);
    }

    @Test
    void checkpointIsNotSavedWhileAResetIsInProgress() throws Exception {
        stubStream("s1");
        EventSourceMapping esm = filterEsm();
        EsmStore store = mock(EsmStore.class);
        DynamoDbStreamsEventSourcePoller p = pollerWith(store);
        AtomicBoolean resetDuringInvoke = new AtomicBoolean(true);
        List<List<String>> invocations = recordInvocations(seqs -> {
            if (resetDuringInvoke.getAndSet(false)) {
                p.beforeReset();
            }
            return new InvokeResult();
        });

        pollOnce(p, esm);
        assertNull(checkpoint(esm));
        verify(store, never()).saveForAccount(anyString(), any());

        p.afterReset();
        pollOnce(p, esm);

        assertEquals(List.of(List.of("s1"), List.of("s1")), invocations);
        assertEquals("s1", checkpoint(esm));
        verify(store).saveForAccount(eq(ACCOUNT_ID), any());
    }

    @Test
    void pollDuringAResetDoesNotInvokeUntilTheResetEnds() throws Exception {
        stubStream("s1");
        List<List<String>> invocations = recordInvocations(seqs -> new InvokeResult());
        EventSourceMapping esm = filterEsm();
        DynamoDbStreamsEventSourcePoller p = pollerWith(mock(EsmStore.class));

        p.beforeReset();
        pollOnce(p, esm);
        assertTrue(invocations.isEmpty(), "a poll during a reset must not invoke the function");

        p.afterReset();
        pollOnce(p, esm);

        assertEquals(List.of(List.of("s1")), invocations);
        assertEquals("s1", checkpoint(esm));
    }

    @Test
    void stopPollingDuringAnInvocationBlocksItsCheckpointWrite() throws Exception {
        stubTrimHorizon(List.of(ddbRecord("s1", "INSERT", "{}")));
        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm();
        DynamoDbStreamsEventSourcePoller p = pollerWith(store);
        // A delete has stopped the poller but has not reached the store yet, so the mapping still exists.
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(inv -> {
                    p.stopPolling(esm.getUuid());
                    return new InvokeResult();
                });

        p.pollAndInvoke(esm);
        verify(executorService, timeout(2000)).invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse));
        awaitPollCompleted(p);

        verify(store, never()).saveForAccount(anyString(), any());
    }

    @Test
    void startPollingAfterStopPollingLetsTheCheckpointAdvanceAgain() {
        stubTrimHorizon(List.of(ddbRecord("s1", "INSERT", "{}")));
        when(executorService.invoke(any(), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult());
        EsmStore store = mock(EsmStore.class);
        EventSourceMapping esm = filterEsm();
        DynamoDbStreamsEventSourcePoller p = pollerWith(store);

        p.stopPolling(esm.getUuid());
        p.startPolling(esm);
        p.pollAndInvoke(esm);

        verify(store, timeout(2000)).saveForAccount(eq(ACCOUNT_ID), any());
        assertEquals("s1", esm.getShardSequenceNumbers().get(DynamoDbStreamService.SHARD_ID));
    }

    @Test
    void mappingDeletedReleasesItsStopTombstone() {
        poller.stopPolling("esm-gone");

        poller.mappingDeleted("esm-gone");

        assertTrue(poller.stopped.isEmpty());
    }

    @Test
    void resetMappingsInFlightInvokeDoesNotResurrectIt() throws Exception {
        stubTrimHorizon(List.of(ddbRecord("s1", "INSERT", "{}")));
        CountDownLatch invoking = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stubBlockingInvoke(invoking, release, new InvokeResult());
        EventSourceMapping old = streamsEsm("esm-old", null);
        esmStore.saveForAccount(ACCOUNT_ID, old);

        poller.pollAndInvoke(old);
        assertTrue(invoking.await(2, TimeUnit.SECONDS));
        esmStore.delete(old.getUuid());
        poller.clear();
        EventSourceMapping recreated = streamsEsm("esm-new", null);
        esmStore.saveForAccount(ACCOUNT_ID, recreated);
        // clear() forgot the in-flight poll; re-mark it so awaitPollCompleted waits for the old worker.
        poller.activePolls.put(old.getUuid(), Boolean.TRUE);
        release.countDown();
        awaitPollCompleted(poller);

        assertTrue(esmStore.getForAccount(ACCOUNT_ID, old.getUuid()).isEmpty(),
                "a mapping wiped by a reset must not be written back by its in-flight poll");
    }

    private void awaitPollCompleted(DynamoDbStreamsEventSourcePoller poller) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (!poller.activePolls.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(poller.activePolls.isEmpty(), "poll did not complete before the test timeout");
    }

    private JsonNode readRecords(byte[] payload) {
        try {
            return OBJECT_MAPPER.readTree(payload).path("Records");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
