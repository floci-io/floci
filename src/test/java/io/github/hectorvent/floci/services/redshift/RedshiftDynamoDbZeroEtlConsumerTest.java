package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.CheckpointLifetime;
import io.github.hectorvent.floci.services.dynamodb.backend.RecordingDynamoDbBackend;
import io.github.hectorvent.floci.services.dynamodb.backend.RecordingDynamoDbBackend.Invocation;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.redshift.model.Integration;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftZeroEtlWriter;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@QuarkusTest
class RedshiftDynamoDbZeroEtlConsumerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    DynamoDbService dynamoDbService;

    @Inject
    DynamoDbFacade dynamoDb;

    private static final String SHARD_ID = "shardId-0000000001-00000000001";

    /**
     * Serves shards from memory the way the native engine does. A cursor is the sequence to read
     * after, or empty for the trim horizon, a sequence older than the oldest retained record is
     * trimmed, and a closed shard read to its end has no next cursor.
     */
    static class FakeStreamReader implements DynamoDbStreamReader {

        final List<String> iteratorRequests = new CopyOnWriteArrayList<>();
        private final List<Shard> shards = new CopyOnWriteArrayList<>();
        private final Map<String, List<Record>> records = new ConcurrentHashMap<>();
        private final Set<String> closed = ConcurrentHashMap.newKeySet();
        private final CheckpointLifetime lifetime;

        FakeStreamReader(CheckpointLifetime lifetime) {
            this.lifetime = lifetime;
        }

        List<Record> shard(String shardId, String parentShardId) {
            shards.add(new Shard(shardId, parentShardId, null, null));
            return records.computeIfAbsent(shardId, ignored -> new CopyOnWriteArrayList<>());
        }

        void close(String shardId) {
            closed.add(shardId);
        }

        @Override
        public ShardsPage describeStream(Stream stream, String exclusiveStartShardId, Integer limit) {
            return new ShardsPage(stream, List.copyOf(shards), null);
        }

        @Override
        public Cursor getShardIterator(Stream stream, String shardId, Position position, String sequenceNumber) {
            iteratorRequests.add(shardId + ":" + position + ":" + sequenceNumber);
            return new Cursor(stream, shardId, position == Position.TRIM_HORIZON ? "" : sequenceNumber);
        }

        @Override
        public RecordsPage getRecords(Cursor cursor, int limit) {
            List<Record> held = new ArrayList<>(records.get(cursor.shardId()));
            String after = cursor.token();
            if (!after.isEmpty() && !held.isEmpty() && after.compareTo(held.get(0).sequenceNumber()) < 0) {
                throw new AwsException("TrimmedDataAccessException",
                        "The requested sequence number has been trimmed", 400);
            }
            List<Record> page = held.stream()
                    .filter(record -> record.sequenceNumber().compareTo(after) > 0)
                    .limit(limit)
                    .toList();
            if (page.isEmpty()) {
                return new RecordsPage(page, closed.contains(cursor.shardId()) ? null : cursor);
            }
            return new RecordsPage(page,
                    new Cursor(cursor.stream(), cursor.shardId(), page.get(page.size() - 1).sequenceNumber()));
        }

        @Override
        public CheckpointLifetime checkpointLifetime() {
            return lifetime;
        }
    }

    @Test
    void writesTheAwsStreamRecordsAndCommitsShardProgressAfterTheWrite() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        Integration integration = streamingIntegration();
        DynamoDbStreamReader.Record first = streamRecord("s1");
        DynamoDbStreamReader.Record second = streamRecord("s2");
        streams.shard(SHARD_ID, null).addAll(List.of(first, second));

        new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, redshiftService, writer).pollOnce(integration);

        verify(writer).createLandingTable(integration.getAccountId(), "warehouse", "floci_zetl_orders");
        verify(writer).writeBatch(integration.getAccountId(), "warehouse", "floci_zetl_orders",
                List.of(first.awsRecord(), second.awsRecord()));
        verify(redshiftService).updateIntegrationRuntime(integration.getAccountId(), integration.getIntegrationArn(),
                Map.of(SHARD_ID, "s2"), true, null);
        assertEquals(Map.of(SHARD_ID, "s2"), integration.getShardSequenceNumbers());
        assertEquals(List.of(SHARD_ID + ":TRIM_HORIZON:null"), streams.iteratorRequests);
    }

    @Test
    void commitsProgressForEachShardAndResumesAfterIt() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        Integration integration = streamingIntegration();
        streams.shard("shard-a", null).add(streamRecord("a1"));
        streams.shard("shard-b", null).add(streamRecord("b1"));
        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, mock(RedshiftService.class), writer);

        consumer.pollOnce(integration);
        consumer.pollOnce(integration);

        assertEquals(Map.of("shard-a", "a1", "shard-b", "b1"), integration.getShardSequenceNumbers());
        assertEquals(List.of("shard-a:TRIM_HORIZON:null", "shard-b:TRIM_HORIZON:null",
                "shard-a:AFTER_SEQUENCE_NUMBER:a1", "shard-b:AFTER_SEQUENCE_NUMBER:b1"), streams.iteratorRequests);
        verify(writer, times(2)).writeBatch(any(), any(), any(), any());
    }

    @Test
    void failedWriteKeepsProgressSoTheSameRecordsAreWrittenNextTick() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        doThrow(new AwsException("InternalFailure", "Could not write Redshift zero-ETL records.", 500))
                .doNothing()
                .when(writer).writeBatch(any(), any(), any(), any());
        Integration integration = streamingIntegration();
        integration.setShardSequenceNumbers(Map.of(SHARD_ID, "s1"));
        DynamoDbStreamReader.Record second = streamRecord("s2");
        streams.shard(SHARD_ID, null).addAll(List.of(streamRecord("s1"), second));
        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, redshiftService, writer);

        assertThrows(AwsException.class, () -> consumer.pollOnce(integration));

        assertEquals(Map.of(SHARD_ID, "s1"), integration.getShardSequenceNumbers());
        verify(redshiftService, never()).updateIntegrationRuntime(any(), any(), any(), eq(true), any());

        consumer.pollOnce(integration);

        verify(writer, times(2)).writeBatch(integration.getAccountId(), "warehouse", "floci_zetl_orders",
                List.of(second.awsRecord()));
        assertEquals(Map.of(SHARD_ID, "s2"), integration.getShardSequenceNumbers());
    }

    @Test
    void trimmedCheckpointRestartsTheShardAtTheTrimHorizon() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        Integration integration = streamingIntegration();
        integration.setShardSequenceNumbers(Map.of(SHARD_ID, "s0"));
        streams.shard(SHARD_ID, null).addAll(List.of(streamRecord("s1"), streamRecord("s2")));
        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, mock(RedshiftService.class), writer);

        AwsException trimmed = assertThrows(AwsException.class, () -> consumer.pollOnce(integration));
        assertEquals("TrimmedDataAccessException", trimmed.getErrorCode());
        assertTrue(integration.getShardSequenceNumbers().isEmpty());

        consumer.pollOnce(integration);

        assertEquals(Map.of(SHARD_ID, "s2"), integration.getShardSequenceNumbers());
    }

    @Test
    void readsAChildShardOnlyAfterItsParentIsReadToItsEnd() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        Integration integration = streamingIntegration();
        streams.shard("parent", null).add(streamRecord("p1"));
        streams.close("parent");
        streams.shard("child", "parent").add(streamRecord("c1"));
        RedshiftDynamoDbZeroEtlConsumer consumer = new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb,
                mock(RedshiftService.class), mock(RedshiftZeroEtlWriter.class));

        consumer.pollOnce(integration);
        consumer.pollOnce(integration);
        assertEquals(Map.of("parent", "p1"), integration.getShardSequenceNumbers());

        consumer.pollOnce(integration);
        consumer.pollOnce(integration);

        assertEquals(Map.of("parent", "p1", "child", "c1"), integration.getShardSequenceNumbers());
        assertEquals(List.of("parent:TRIM_HORIZON:null", "parent:AFTER_SEQUENCE_NUMBER:p1",
                "child:TRIM_HORIZON:null", "child:AFTER_SEQUENCE_NUMBER:c1"), streams.iteratorRequests);
    }

    @Test
    void processLifetimeDiscardsPersistedShardProgressAtStartup() {
        Integration integration = streamingIntegration();
        integration.setShardSequenceNumbers(Map.of(SHARD_ID, "s9"));

        startPersisted(CheckpointLifetime.PROCESS, integration);

        assertTrue(integration.getShardSequenceNumbers().isEmpty());
    }

    @Test
    void streamLifetimeKeepsPersistedShardProgressAtStartup() {
        Integration integration = streamingIntegration();
        integration.setShardSequenceNumbers(Map.of(SHARD_ID, "s9"));

        startPersisted(CheckpointLifetime.STREAM, integration);

        assertEquals(Map.of(SHARD_ID, "s9"), integration.getShardSequenceNumbers());
    }

    private void startPersisted(CheckpointLifetime lifetime, Integration integration) {
        RedshiftService redshiftService = mock(RedshiftService.class);
        when(redshiftService.listDynamoDbZeroEtlIntegrations()).thenReturn(List.of(integration));
        new RedshiftDynamoDbZeroEtlConsumer(new FakeStreamReader(lifetime), dynamoDb, redshiftService,
                mock(RedshiftZeroEtlWriter.class)).startPersistedIntegrations();
    }

    private static DynamoDbStreamReader.Record streamRecord(String sequenceNumber) {
        ObjectNode awsRecord = MAPPER.createObjectNode()
                .put("eventID", "event-" + sequenceNumber)
                .put("eventName", "MODIFY");
        awsRecord.putObject("dynamodb").put("SequenceNumber", sequenceNumber);
        return new DynamoDbStreamReader.Record(sequenceNumber, awsRecord);
    }

    @Test
    void anEmulatorResetStopsEveryIntegrationTimer() {
        Vertx vertx = mock(Vertx.class);
        when(vertx.setPeriodic(anyLong(), any())).thenReturn(7L);
        RedshiftDynamoDbZeroEtlConsumer consumer = new RedshiftDynamoDbZeroEtlConsumer(vertx,
                new FakeStreamReader(CheckpointLifetime.PROCESS), dynamoDb, mock(RedshiftService.class),
                mock(RedshiftZeroEtlWriter.class), MAPPER, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS));
        consumer.startPolling(streamingIntegration());
        Resettable resettable = consumer;

        resettable.clear();

        verify(vertx).cancelTimer(7L);
    }

    @Test
    void anEmulatorResetWaitsForTheWriteInProgressAndWritesNothingUntilItEnds() throws Exception {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            writing.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).doNothing().when(writer).writeBatch(any(), any(), any(), any());
        Integration integration = streamingIntegration();
        List<DynamoDbStreamReader.Record> shard = streams.shard(SHARD_ID, null);
        shard.add(streamRecord("s1"));
        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, mock(RedshiftService.class), writer);
        Thread poll = new Thread(() -> consumer.pollOnce(integration));
        poll.start();
        assertTrue(writing.await(10, TimeUnit.SECONDS));

        Thread reset = new Thread(consumer::beforeReset);
        reset.start();
        awaitWaiting(reset);
        release.countDown();
        reset.join(10_000);
        poll.join(10_000);
        assertEquals(Thread.State.TERMINATED, reset.getState());

        DynamoDbStreamReader.Record second = streamRecord("s2");
        shard.add(second);
        consumer.pollOnce(integration);
        verify(writer, times(1)).createLandingTable(any(), any(), any());
        verify(writer, times(1)).writeBatch(any(), any(), any(), any());

        consumer.afterReset();
        consumer.pollOnce(integration);
        verify(writer).writeBatch(integration.getAccountId(), "warehouse", "floci_zetl_orders",
                List.of(second.awsRecord()));
        assertEquals(Map.of(SHARD_ID, "s2"), integration.getShardSequenceNumbers());
    }

    @Test
    void aPollSubmittedBeforeAResetIsSkippedEvenWhenItRunsAfterTheReset() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        Integration integration = streamingIntegration();
        DynamoDbStreamReader.Record first = streamRecord("s1");
        streams.shard(SHARD_ID, null).add(first);
        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, redshiftService, writer);
        Runnable submittedBeforeReset = consumer.pollTask(integration);

        consumer.beforeReset();
        consumer.clear();
        consumer.afterReset();
        submittedBeforeReset.run();

        verifyNoInteractions(writer);
        verify(redshiftService, never()).updateIntegrationRuntime(any(), any(), any(), anyBoolean(), any());

        consumer.pollTask(integration).run();

        verify(writer).writeBatch(integration.getAccountId(), "warehouse", "floci_zetl_orders",
                List.of(first.awsRecord()));
        verify(redshiftService).updateIntegrationRuntime(integration.getAccountId(), integration.getIntegrationArn(),
                Map.of(SHARD_ID, "s1"), true, null);
    }

    @Test
    void shutdownWaitsForThePollInProgressSoItsCheckpointIsSavedFirst() throws Exception {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            writing.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(writer).writeBatch(any(), any(), any(), any());
        doAnswer(invocation -> events.add("checkpoint saved"))
                .when(redshiftService).updateIntegrationRuntime(any(), any(), any(), anyBoolean(), any());
        Vertx vertx = mock(Vertx.class);
        when(vertx.setPeriodic(anyLong(), any())).thenReturn(7L);
        Integration integration = streamingIntegration();
        streams.shard(SHARD_ID, null).add(streamRecord("s1"));
        RedshiftDynamoDbZeroEtlConsumer consumer = new RedshiftDynamoDbZeroEtlConsumer(vertx, streams, dynamoDb,
                redshiftService, writer, MAPPER, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS));
        consumer.startPolling(integration);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Handler<Long>> tick = ArgumentCaptor.forClass(Handler.class);
        verify(vertx).setPeriodic(anyLong(), tick.capture());
        Thread shutdown = new Thread(() -> {
            consumer.shutdown();
            events.add("shutdown returned");
        });

        try {
            tick.getValue().handle(7L);
            assertTrue(writing.await(10, TimeUnit.SECONDS), "the tick hands the poll to the poll executor");
            shutdown.start();
            assertEquals(Thread.State.TIMED_WAITING, awaitTimedWaitingOrTerminated(shutdown),
                    "shutdown() returned while a poll was still writing");
            release.countDown();
            shutdown.join(10_000);
        } finally {
            release.countDown();
        }

        assertEquals(List.of("checkpoint saved", "shutdown returned"), events);
        verify(redshiftService).updateIntegrationRuntime(integration.getAccountId(), integration.getIntegrationArn(),
                Map.of(SHARD_ID, "s1"), true, null);
    }

    @Test
    void aTickFromATimerCanceledByAResetDoesNotPoll() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        Vertx vertx = mock(Vertx.class);
        when(vertx.setPeriodic(anyLong(), any())).thenReturn(7L);
        Integration integration = streamingIntegration();
        streams.shard(SHARD_ID, null).add(streamRecord("s1"));
        RedshiftDynamoDbZeroEtlConsumer consumer = new RedshiftDynamoDbZeroEtlConsumer(vertx, streams, dynamoDb,
                redshiftService, writer, MAPPER, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS));
        consumer.startPolling(integration);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Handler<Long>> tick = ArgumentCaptor.forClass(Handler.class);
        verify(vertx).setPeriodic(anyLong(), tick.capture());

        consumer.beforeReset();
        consumer.clear();
        consumer.afterReset();
        tick.getValue().handle(7L);
        // shutdown() waits for every submitted poll to end, so anything the tick submitted has run.
        consumer.shutdown();

        assertTrue(streams.iteratorRequests.isEmpty(), "the stale tick read the stream");
        verifyNoInteractions(writer);
        verify(redshiftService, never()).updateIntegrationRuntime(any(), any(), any(), anyBoolean(), any());
    }

    /** Observes the thread park in a timed wait, or end, instead of sleeping to hope for either. */
    private static Thread.State awaitTimedWaitingOrTerminated(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Thread.State state = thread.getState();
        while (state != Thread.State.TIMED_WAITING && state != Thread.State.TERMINATED) {
            if (System.nanoTime() > deadline) {
                fail("the thread neither parked in a timed wait nor ended");
            }
            Thread.onSpinWait();
            state = thread.getState();
        }
        return state;
    }

    /** Observes the reset thread park on the poll's lock instead of sleeping to hope for it. */
    private static void awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.WAITING) {
            if (System.nanoTime() > deadline) {
                fail("beforeReset() returned or never waited while a write was in progress");
            }
            Thread.onSpinWait();
        }
    }

    private static Integration streamingIntegration() {
        Integration integration = integration("orders");
        integration.setBackfillCompleted(true);
        return integration;
    }

    @Test
    void backfillsOnePageAtATimeBeforeSwitchingToStreamPolling() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        String tableName = "orders-page-" + System.nanoTime();
        Integration integration = integration(tableName);
        assertFalse(integration.isBackfillCompleted());

        // Create table and items in the integration's account scope (111111111111).
        // Populate > BATCH_SIZE (100) items so the scan produces a LastEvaluatedKey.
        RequestScopes.runAs(integration.getAccountId(), () -> {
            dynamoDbService.createTable(tableName,
                    List.of(new KeySchemaElement("id", "HASH")),
                    List.of(new AttributeDefinition("id", "S")),
                    5L, 5L, "us-east-1");
            for (int i = 1; i <= 101; i++) {
                dynamoDbService.putItem(tableName, itemWithId(String.valueOf(i)), "us-east-1");
            }
        });

        // The table must NOT exist in the default account: this proves the account scope is applied.
        assertThrows(AwsException.class, () -> dynamoDbService.describeTable(tableName, "us-east-1"));

        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, redshiftService, writer);
        consumer.pollOnce(integration);

        assertFalse(integration.isBackfillCompleted());
        assertNotNull(integration.getBackfillLastEvaluatedKey());
        verify(redshiftService).updateIntegrationBackfillProgress(eq(integration.getAccountId()),
                eq(integration.getIntegrationArn()), eq(integration.getBackfillLastEvaluatedKey()), eq(false));
        assertTrue(streams.iteratorRequests.isEmpty());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JsonNode>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeBatch(eq(integration.getAccountId()), eq("warehouse"), eq("floci_zetl_orders"),
                captor.capture());
        assertEquals(100, captor.getValue().size());
        JsonNode written = captor.getValue().get(0);
        assertEquals("INSERT", written.path("eventName").asText());
        assertTrue(written.path("eventID").asText().startsWith("backfill#" + integration.getIntegrationArn() + "#"));
        assertEquals("backfill", written.path("dynamodb").path("SequenceNumber").asText());
        assertEquals(written.path("dynamodb").path("Keys").path("id"),
                written.path("dynamodb").path("NewImage").path("id"));
    }

    @Test
    void backfillCompletesWhenScanReturnsNoLastEvaluatedKey() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        String tableName = "orders-empty-" + System.nanoTime();
        Integration integration = integration(tableName);

        RequestScopes.runAs(integration.getAccountId(), () -> {
            dynamoDbService.createTable(tableName,
                    List.of(new KeySchemaElement("id", "HASH")),
                    List.of(new AttributeDefinition("id", "S")),
                    5L, 5L, "us-east-1");
        });

        assertThrows(AwsException.class, () -> dynamoDbService.describeTable(tableName, "us-east-1"));

        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, redshiftService, writer);
        consumer.pollOnce(integration);

        assertTrue(integration.isBackfillCompleted());
        verify(redshiftService).updateIntegrationBackfillProgress(integration.getAccountId(),
                integration.getIntegrationArn(), null, true);
        verify(writer, never()).writeBatch(any(), any(), any(), any());
    }

    @Test
    void sameItemProducesTheSameEventIdAcrossScans() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        String tableName = "orders-same-" + System.nanoTime();
        Integration integration = integration(tableName);

        ObjectNode item = itemWithId("1");
        RequestScopes.runAs(integration.getAccountId(), () -> {
            dynamoDbService.createTable(tableName,
                    List.of(new KeySchemaElement("id", "HASH")),
                    List.of(new AttributeDefinition("id", "S")),
                    5L, 5L, "us-east-1");
            dynamoDbService.putItem(tableName, item, "us-east-1");
        });

        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, redshiftService, writer);
        consumer.pollOnce(integration);
        integration.setBackfillCompleted(false);
        consumer.pollOnce(integration);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JsonNode>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer, times(2)).writeBatch(any(), any(), any(), captor.capture());
        assertEquals(captor.getAllValues().get(0).get(0).path("eventID"),
                captor.getAllValues().get(1).get(0).path("eventID"));
    }

    @Test
    void backfillReadsTheTableAsTheIntegrationAccountInTheStreamRegion() {
        RecordingDynamoDbBackend backend = new RecordingDynamoDbBackend();
        DynamoDbFacade recording = new DynamoDbFacade(backend, backend, new RegionResolver("us-east-1", "000000000000"));
        Integration integration = integration("orders");
        integration.setSourceStreamArn("arn:aws:dynamodb:eu-west-1:111111111111:table/orders/stream/one");

        new RedshiftDynamoDbZeroEtlConsumer(new FakeStreamReader(CheckpointLifetime.PROCESS), recording,
                mock(RedshiftService.class), mock(RedshiftZeroEtlWriter.class)).pollOnce(integration);

        assertEquals(List.of(
                new Invocation("describeTable", "111111111111", "eu-west-1"),
                new Invocation("scan", "111111111111", "eu-west-1")), backend.invocations());
    }

    private static ObjectNode itemWithId(String id) {
        ObjectNode item = MAPPER.createObjectNode();
        ObjectNode idAttr = MAPPER.createObjectNode();
        idAttr.put("S", id);
        item.set("id", idAttr);
        return item;
    }

    private static Integration integration(String tableName) {
        Integration integration = new Integration();
        integration.setIntegrationArn("arn:aws:redshift:us-east-1:111111111111:integration:one");
        integration.setAccountId("111111111111");
        integration.setSourceStreamArn("arn:aws:dynamodb:us-east-1:111111111111:table/" + tableName + "/stream/one");
        integration.setTargetClusterIdentifier("warehouse");
        integration.setLandingTableName("floci_zetl_orders");
        integration.setPollingEnabled(true);
        return integration;
    }
}
