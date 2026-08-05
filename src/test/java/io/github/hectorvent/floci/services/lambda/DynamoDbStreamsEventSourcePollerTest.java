package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambdaConfig = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambdaConfig);
        when(lambdaConfig.pollIntervalMs()).thenReturn(1000L);

        streamService = mock(DynamoDbStreamService.class);
        executorService = mock(LambdaExecutorService.class);
        functionStore = mock(LambdaFunctionStore.class);
        esmStore = new EsmStore(new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT_ID));

        // A mocked Vertx makes setPeriodic a no-op, so startPolling registers no live timer and
        // the tests drive pollAndInvoke deterministically.
        poller = new DynamoDbStreamsEventSourcePoller(
                mock(Vertx.class), streamService, executorService, functionStore,
                esmStore, OBJECT_MAPPER, config);
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
}
