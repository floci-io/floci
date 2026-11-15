package io.floci.aws.services.redshift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.services.dynamodb.DynamoDbFacade;
import io.floci.aws.services.dynamodb.DynamoDbService;
import io.floci.aws.services.dynamodb.backend.DynamoDbStreamReader;
import io.floci.aws.services.dynamodb.backend.DynamoDbStreamReader.CheckpointLifetime;
import io.floci.aws.services.dynamodb.backend.RecordingDynamoDbBackend;
import io.floci.aws.services.dynamodb.backend.RecordingDynamoDbBackend.Invocation;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshift.model.Integration;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlCurrentState;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlCurrentState.Change;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter;
import io.floci.aws.testing.ThreadStates;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
    private static final String WAREHOUSE_ARN = "arn:aws:redshift:us-east-1:111111111111:cluster:warehouse";

    @Test
    void healthyIdlePollsDoNotRewriteCheckpointState() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService service = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        Integration integration = streamingIntegration();
        integration.setStatus("active");
        integration.setShardSequenceNumbers(Map.of(SHARD_ID, "s1"));
        streams.shard(SHARD_ID, null).add(streamRecord("s1"));
        RedshiftDynamoDbZeroEtlConsumer consumer = new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, service, writer);

        consumer.pollOnce(integration);
        consumer.pollOnce(integration);

        verify(service, never()).updateIntegrationRuntime(any(), any(), any(), anyBoolean(), any());
        verify(writer, never()).writeBatch(any(), any(), any(), any());
        assertEquals(Map.of(SHARD_ID, "s1"), integration.getShardSequenceNumbers());
    }

    @Test
    void pausedClusterSkipsPollingWithoutAdvancingProgressOrFailingIntegration() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService service = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        Integration integration = streamingIntegration();
        integration.setStatus("active");
        integration.setShardSequenceNumbers(Map.of(SHARD_ID, "s1"));
        streams.shard(SHARD_ID, null).addAll(List.of(streamRecord("s1"), streamRecord("s2")));
        Cluster cluster = new Cluster();
        cluster.setClusterStatus("paused");
        when(service.describeClustersForAccount(integration.getAccountId(), "warehouse")).thenReturn(List.of(cluster));
        RedshiftDynamoDbZeroEtlConsumer consumer = new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, service, writer);

        consumer.pollTask(integration).run();

        verifyNoInteractions(writer);
        assertTrue(streams.iteratorRequests.isEmpty());
        assertEquals(Map.of(SHARD_ID, "s1"), integration.getShardSequenceNumbers());
        verify(service, never()).updateIntegrationRuntime(any(), any(), any(), anyBoolean(), any());
        cluster.setClusterStatus("available");
        consumer.pollOnce(integration);
        verify(writer).writeBatch(integration.getAccountId(), WAREHOUSE_ARN, "floci_zetl_orders",
                List.of(streamRecord("s2").awsRecord()));
        assertEquals(Map.of(SHARD_ID, "s2"), integration.getShardSequenceNumbers());
    }

    @Test
    void healthyEmptyPollRecoversFailedIntegrationAfterResume() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService service = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        Integration integration = streamingIntegration();
        integration.setStatus("failed");
        integration.setLastError("cluster was paused");
        integration.setRetryCount(1);
        integration.setShardSequenceNumbers(Map.of(SHARD_ID, "s1"));
        streams.shard(SHARD_ID, null).add(streamRecord("s1"));
        Cluster cluster = new Cluster();
        cluster.setClusterStatus("available");
        when(service.describeClustersForAccount(integration.getAccountId(), "warehouse")).thenReturn(List.of(cluster));

        new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, service, writer).pollOnce(integration);

        verify(service).recoverIntegrationRuntime(integration.getAccountId(), integration.getIntegrationArn());
        verify(writer, never()).writeBatch(any(), any(), any(), any());
        assertEquals(Map.of(SHARD_ID, "s1"), integration.getShardSequenceNumbers());
    }

    @Test
    void pollFailureDuringPauseDoesNotMarkIntegrationFailed() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService service = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        Integration integration = streamingIntegration();
        Cluster cluster = new Cluster();
        cluster.setClusterStatus("available");
        when(service.describeClustersForAccount(integration.getAccountId(), "warehouse")).thenReturn(List.of(cluster));
        doAnswer(invocation -> {
            cluster.setClusterStatus("paused");
            throw new AwsException("InternalFailure", "database stopped during poll", 500);
        }).when(writer).createLandingTable(any(), any(), any());

        new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, service, writer).pollTask(integration).run();

        verify(service, never()).updateIntegrationRuntime(any(), any(), any(), anyBoolean(), any());
        assertTrue(integration.getShardSequenceNumbers().isEmpty());
    }

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
        /** Shards of one named stream, for tests that tell a table's streams apart. */
        private final Map<String, List<Shard>> streamShards = new ConcurrentHashMap<>();
        private final Set<String> expired = ConcurrentHashMap.newKeySet();
        private final CheckpointLifetime lifetime;

        FakeStreamReader(CheckpointLifetime lifetime) {
            this.lifetime = lifetime;
        }

        List<Record> shard(String shardId, String parentShardId) {
            shards.add(new Shard(shardId, parentShardId, null, null));
            return records.computeIfAbsent(shardId, ignored -> new CopyOnWriteArrayList<>());
        }

        List<Record> shard(String streamArn, String shardId, String parentShardId) {
            streamShards.computeIfAbsent(streamArn, ignored -> new CopyOnWriteArrayList<>())
                    .add(new Shard(shardId, parentShardId, null, null));
            return records.computeIfAbsent(streamArn + " " + shardId, ignored -> new CopyOnWriteArrayList<>());
        }

        void close(String shardId) {
            closed.add(shardId);
        }

        void expire(String streamArn) {
            expired.add(streamArn);
        }

        @Override
        public ShardsPage describeStream(Stream stream, String exclusiveStartShardId, Integer limit) {
            if (expired.contains(stream.arn())) {
                throw new AwsException("ResourceNotFoundException", "Stream not found: " + stream.arn(), 400);
            }
            return new ShardsPage(stream, List.copyOf(streamShards.getOrDefault(stream.arn(), shards)), null);
        }

        @Override
        public Cursor getShardIterator(Stream stream, String shardId, Position position, String sequenceNumber) {
            iteratorRequests.add((streamShards.containsKey(stream.arn()) ? stream.arn() + " " : "")
                    + shardId + ":" + position + ":" + sequenceNumber);
            return new Cursor(stream, shardId, position == Position.TRIM_HORIZON ? "" : sequenceNumber);
        }

        @Override
        public RecordsPage getRecords(Cursor cursor, int limit) {
            List<Record> held = new ArrayList<>(records.getOrDefault(cursor.stream().arn() + " " + cursor.shardId(),
                    records.get(cursor.shardId())));
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

        verify(writer).createLandingTable(integration.getAccountId(), WAREHOUSE_ARN, "floci_zetl_orders");
        verify(writer).writeBatch(integration.getAccountId(), WAREHOUSE_ARN, "floci_zetl_orders",
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

        verify(writer, times(2)).writeBatch(integration.getAccountId(), WAREHOUSE_ARN, "floci_zetl_orders",
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
        verify(writer).writeBatch(integration.getAccountId(), WAREHOUSE_ARN, "floci_zetl_orders",
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

        verify(writer).writeBatch(integration.getAccountId(), WAREHOUSE_ARN, "floci_zetl_orders",
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
        return ThreadStates.awaitState(thread, EnumSet.of(Thread.State.TIMED_WAITING, Thread.State.TERMINATED),
                Duration.ofSeconds(10), "the thread");
    }

    /** Observes the reset thread park on the poll's lock instead of sleeping to hope for it. */
    private static void awaitWaiting(Thread thread) {
        ThreadStates.awaitState(thread, EnumSet.of(Thread.State.WAITING), Duration.ofSeconds(10),
                "beforeReset(), while a write was in progress,");
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
        verify(writer).writeBatch(eq(integration.getAccountId()), eq(WAREHOUSE_ARN), eq("floci_zetl_orders"),
                captor.capture());
        assertEquals(100, captor.getValue().size());
        JsonNode written = captor.getValue().get(0);
        assertEquals("INSERT", written.path("eventName").asText());
        assertTrue(written.path("eventID").asText().startsWith("backfill#" + integration.getIntegrationArn() + "#"));
        assertEquals("backfill", written.path("dynamodb").path("SequenceNumber").asText());
        assertEquals(written.path("dynamodb").path("Keys").path("id"),
                written.path("dynamodb").path("NewImage").path("id"));

        consumer.pollOnce(integration);

        // Only the first page empties the current-state table; the second continues the same backfill.
        assertTrue(integration.isBackfillCompleted());
        verify(writer, times(1)).resetCurrentStateTable(any(), any(), any());
        verify(writer, times(2)).applyChanges(any(), any(), any(), any());
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

    @Test
    void aTableSourceDrainsItsStreamBeforeMovingToTheTablesLatestAndDropsItsCheckpoints() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        String tableName = "orders-streamed-" + System.nanoTime();
        TableDefinition table = createTableAs("111111111111", tableName, true);
        Integration integration = tableSourceIntegration(table);
        integration.setBackfillCompleted(true);
        // Progress made on the stream the table had before its streams were turned off and on again,
        // which still holds a record the integration has not read.
        String previous = table.getTableArn() + "/stream/2026-01-01T00:00:00.000";
        integration.setSourceStreamArn(previous);
        integration.setShardSequenceNumbers(Map.of(SHARD_ID, "s1"));
        DynamoDbStreamReader.Record unread = streamRecord("s2");
        streams.shard(previous, SHARD_ID, null).addAll(List.of(streamRecord("s1"), unread));
        DynamoDbStreamReader.Record first = streamRecord("s1");
        DynamoDbStreamReader.Record second = streamRecord("s2");
        streams.shard(table.getStreamArn(), SHARD_ID, null).addAll(List.of(first, second));
        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, redshiftService, writer);

        consumer.pollOnce(integration);
        assertEquals(previous, integration.getSourceStreamArn());
        verify(writer).writeBatch(integration.getAccountId(), WAREHOUSE_ARN, "floci_zetl_orders",
                List.of(unread.awsRecord()));

        consumer.pollOnce(integration);
        assertEquals(table.getStreamArn(), integration.getSourceStreamArn());
        assertTrue(integration.getShardSequenceNumbers().isEmpty());
        verify(redshiftService).updateIntegrationSourceStream(integration.getAccountId(),
                integration.getIntegrationArn(), table.getStreamArn());

        consumer.pollOnce(integration);
        assertEquals(List.of(
                previous + " " + SHARD_ID + ":AFTER_SEQUENCE_NUMBER:s1",
                previous + " " + SHARD_ID + ":AFTER_SEQUENCE_NUMBER:s2",
                table.getStreamArn() + " " + SHARD_ID + ":TRIM_HORIZON:null"), streams.iteratorRequests);
        verify(writer).writeBatch(integration.getAccountId(), WAREHOUSE_ARN, "floci_zetl_orders",
                List.of(first.awsRecord(), second.awsRecord()));
        assertEquals(Map.of(SHARD_ID, "s2"), integration.getShardSequenceNumbers());
    }

    @Test
    void aTableSourceKeepsReadingItsStreamWhileTheTableHasStreamsTurnedOff() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        String tableName = "orders-stream-off-" + System.nanoTime();
        // A backend that reports no stream for a table whose streams are off, as DynamoDB Local does.
        TableDefinition table = createTableAs("111111111111", tableName, false);
        Integration integration = tableSourceIntegration(table);
        integration.setBackfillCompleted(true);
        String disabled = table.getTableArn() + "/stream/2026-01-01T00:00:00.000";
        integration.setSourceStreamArn(disabled);
        DynamoDbStreamReader.Record last = streamRecord("s1");
        streams.shard(disabled, SHARD_ID, null).add(last);
        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, redshiftService, writer);

        consumer.pollOnce(integration);
        consumer.pollOnce(integration);

        assertEquals(disabled, integration.getSourceStreamArn());
        verify(writer).writeBatch(integration.getAccountId(), WAREHOUSE_ARN, "floci_zetl_orders",
                List.of(last.awsRecord()));
        assertEquals(Map.of(SHARD_ID, "s1"), integration.getShardSequenceNumbers());
        verify(redshiftService, never()).updateIntegrationSourceStream(any(), any(), any());
    }

    @Test
    void aTableSourceMovesOnWhenItsReplacedStreamHasExpired() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        String tableName = "orders-stream-expired-" + System.nanoTime();
        TableDefinition table = createTableAs("111111111111", tableName, true);
        Integration integration = tableSourceIntegration(table);
        integration.setBackfillCompleted(true);
        String expired = table.getTableArn() + "/stream/2026-01-01T00:00:00.000";
        integration.setSourceStreamArn(expired);
        integration.setShardSequenceNumbers(Map.of(SHARD_ID, "s1"));
        streams.expire(expired);

        new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, redshiftService, writer).pollOnce(integration);

        assertEquals(table.getStreamArn(), integration.getSourceStreamArn());
        assertTrue(integration.getShardSequenceNumbers().isEmpty());
        verify(redshiftService).updateIntegrationSourceStream(integration.getAccountId(),
                integration.getIntegrationArn(), table.getStreamArn());
    }

    @Test
    void aTableSourceWithoutAStreamIsBackfilledAndThenIdle() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        String tableName = "orders-unstreamed-" + System.nanoTime();
        TableDefinition table = createTableAs("111111111111", tableName, false);
        RequestScopes.runAs("111111111111",
                () -> dynamoDbService.putItem(tableName, itemWithId("1"), "us-east-1"));
        Integration integration = tableSourceIntegration(table);
        streams.shard(SHARD_ID, null).add(streamRecord("s1"));
        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, redshiftService, writer);

        consumer.pollOnce(integration);
        consumer.pollOnce(integration);

        assertTrue(integration.isBackfillCompleted());
        verify(writer, times(1)).writeBatch(any(), eq(WAREHOUSE_ARN), any(), any());
        verify(redshiftService).recoverIntegrationRuntime(integration.getAccountId(), integration.getIntegrationArn());
        assertTrue(streams.iteratorRequests.isEmpty());
    }

    @Test
    void aServerlessTargetIsNeverPausedAndIsWrittenByItsArn() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        String namespaceArn = "arn:aws:redshift-serverless:us-east-1:111111111111:namespace/"
                + "8f3e0b52-6c1a-4f0e-9d7b-2a4c5e6f7a8b";
        Integration integration = streamingIntegration();
        integration.setTargetArn(namespaceArn);
        integration.setTargetClusterIdentifier(null);
        DynamoDbStreamReader.Record first = streamRecord("s1");
        streams.shard(SHARD_ID, null).add(first);

        new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, redshiftService, writer).pollOnce(integration);

        verify(redshiftService, never()).describeClustersForAccount(any(), any());
        verify(writer).createLandingTable(integration.getAccountId(), namespaceArn, "floci_zetl_orders");
        verify(writer).writeBatch(integration.getAccountId(), namespaceArn, "floci_zetl_orders",
                List.of(first.awsRecord()));
    }

    @Test
    void projectsStreamRecordsIntoTheCurrentStateTableAfterTheyLand() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        String tableName = "orders-projected-" + System.nanoTime();
        TableDefinition table = createTableAs("111111111111", tableName, true);
        Integration integration = tableSourceIntegration(table);
        integration.setBackfillCompleted(true);
        // Key 1 is in the source and key 2 was deleted from it; each is named by an insert and a remove.
        ObjectNode held = itemWithId("1");
        held.putObject("note").put("S", "held");
        RequestScopes.runAs("111111111111", () -> dynamoDbService.putItem(tableName, held, "us-east-1"));
        DynamoDbStreamReader.Record inserted = imageRecord("s1", "INSERT", "1", "first");
        DynamoDbStreamReader.Record removed = keysOnlyRecord("s2", "REMOVE", "2");
        streams.shard(table.getStreamArn(), SHARD_ID, null).addAll(List.of(inserted, removed));

        new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, redshiftService, writer).pollOnce(integration);

        ArgumentCaptor<RedshiftZeroEtlCurrentState> state = ArgumentCaptor.forClass(RedshiftZeroEtlCurrentState.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Change>> changes = ArgumentCaptor.forClass(List.class);
        InOrder inOrder = inOrder(writer);
        inOrder.verify(writer).writeBatch(integration.getAccountId(), WAREHOUSE_ARN, "floci_zetl_orders",
                List.of(inserted.awsRecord(), removed.awsRecord()));
        inOrder.verify(writer).createCurrentStateTable(eq(integration.getAccountId()), eq(WAREHOUSE_ARN), state.capture(),
                eq("one"));
        inOrder.verify(writer).applyChanges(eq(integration.getAccountId()), eq(WAREHOUSE_ARN), eq(state.getValue()),
                changes.capture());
        assertEquals(tableName, state.getValue().tableName());
        assertEquals(List.of(new Change(inserted.awsRecord().path("dynamodb").path("Keys"), held),
                new Change(removed.awsRecord().path("dynamodb").path("Keys"), null)), changes.getValue());
        assertEquals(Map.of(SHARD_ID, "s2"), integration.getShardSequenceNumbers());
    }

    /**
     * The scan read version three of an item while the stream still holds the MODIFY records that led to it. They
     * are replayed after the scan, carrying older images, and must not put those over the newer row.
     */
    @Test
    void replayedRecordsOfAnItemTheScanReadNewerNeverRegressItsRow() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        String tableName = "orders-replayed-" + System.nanoTime();
        TableDefinition table = createTableAs("111111111111", tableName, true);
        ObjectNode versionThree = itemWithId("1");
        versionThree.putObject("note").put("S", "version three");
        RequestScopes.runAs("111111111111", () -> dynamoDbService.putItem(tableName, versionThree, "us-east-1"));
        Integration integration = tableSourceIntegration(table);
        integration.setBackfillCompleted(true);
        DynamoDbStreamReader.Record versionTwo = imageRecord("s1", "MODIFY", "1", "version two");
        DynamoDbStreamReader.Record versionThreeRecord = imageRecord("s2", "MODIFY", "1", "version three");
        streams.shard(table.getStreamArn(), SHARD_ID, null).addAll(List.of(versionTwo));
        RedshiftDynamoDbZeroEtlConsumer consumer = new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb,
                mock(RedshiftService.class), writer);

        consumer.pollOnce(integration);
        streams.shard(table.getStreamArn(), SHARD_ID, null).add(versionThreeRecord);
        consumer.pollOnce(integration);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Change>> changes = ArgumentCaptor.forClass(List.class);
        verify(writer, times(2)).applyChanges(any(), any(), any(), changes.capture());
        // Across polls, the older record's poll and the newer one's both leave version three in the row.
        for (List<Change> applied : changes.getAllValues()) {
            assertEquals(1, applied.size());
            assertEquals(versionThree, applied.get(0).item());
        }
    }

    @Test
    void manyRecordsOfOneKeyProjectItOnce() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        String tableName = "orders-chatty-" + System.nanoTime();
        TableDefinition table = createTableAs("111111111111", tableName, true);
        Integration integration = tableSourceIntegration(table);
        integration.setBackfillCompleted(true);
        streams.shard(table.getStreamArn(), SHARD_ID, null).addAll(List.of(
                imageRecord("s1", "INSERT", "1", "a"), imageRecord("s2", "MODIFY", "1", "b"),
                keysOnlyRecord("s3", "REMOVE", "1")));

        new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, mock(RedshiftService.class), writer)
                .pollOnce(integration);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Change>> changes = ArgumentCaptor.forClass(List.class);
        verify(writer).applyChanges(any(), any(), any(), changes.capture());
        assertEquals(1, changes.getValue().size());
        assertTrue(changes.getValue().get(0).isDelete());
    }

    @Test
    void aRecordWithoutANewImageIsProjectedFromTheSourceItem() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        String tableName = "orders-keys-only-" + System.nanoTime();
        TableDefinition table = createTableAs("111111111111", tableName, true);
        ObjectNode item = itemWithId("1");
        item.putObject("note").put("S", "as it is now");
        RequestScopes.runAs("111111111111", () -> dynamoDbService.putItem(tableName, item, "us-east-1"));
        Integration integration = tableSourceIntegration(table);
        integration.setBackfillCompleted(true);
        streams.shard(table.getStreamArn(), SHARD_ID, null).addAll(List.of(
                keysOnlyRecord("s1", "MODIFY", "1"), keysOnlyRecord("s2", "MODIFY", "gone")));

        new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, mock(RedshiftService.class), writer)
                .pollOnce(integration);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Change>> changes = ArgumentCaptor.forClass(List.class);
        verify(writer).applyChanges(eq(integration.getAccountId()), eq(WAREHOUSE_ARN), any(), changes.capture());
        assertEquals(item, changes.getValue().get(0).item());
        assertTrue(changes.getValue().get(1).isDelete());
    }

    @Test
    void aFailedProjectionDoesNotHoldBackTheLandingLogAndTheTableIsRebuiltOnceItCanBeWritten() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        doThrow(new AwsException("InternalFailure", "Could not apply Redshift zero-ETL changes to orders.", 500))
                .when(writer).applyChanges(any(), any(), any(), any());
        String tableName = "orders-retried-" + System.nanoTime();
        TableDefinition table = createTableAs("111111111111", tableName, true);
        Integration integration = tableSourceIntegration(table);
        integration.setBackfillCompleted(true);
        integration.setCurrentStateProjected(true);
        DynamoDbStreamReader.Record first = imageRecord("s1", "INSERT", "1", "first");
        DynamoDbStreamReader.Record second = imageRecord("s2", "INSERT", "2", "second");
        streams.shard(table.getStreamArn(), SHARD_ID, null).add(first);
        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, redshiftService, writer);

        consumer.pollOnce(integration);

        // The record landed and its checkpoint advanced; only the projection stalled.
        verify(writer).writeBatch(integration.getAccountId(), WAREHOUSE_ARN, "floci_zetl_orders",
                List.of(first.awsRecord()));
        assertEquals(Map.of(SHARD_ID, "s1"), integration.getShardSequenceNumbers());
        assertFalse(integration.isCurrentStateProjected());
        verify(redshiftService).updateIntegrationCurrentState(integration.getAccountId(),
                integration.getIntegrationArn(), false);

        // While the table cannot be written, later batches still land and none is projected.
        doThrow(new AwsException("InvalidParameterValue", "Table public.\"x\" is not ours.", 400))
                .when(writer).createCurrentStateTable(any(), any(), any(), any());
        streams.shard(table.getStreamArn(), SHARD_ID, null).add(second);
        consumer.pollOnce(integration);

        verify(writer).writeBatch(integration.getAccountId(), WAREHOUSE_ARN, "floci_zetl_orders",
                List.of(second.awsRecord()));
        assertEquals(Map.of(SHARD_ID, "s2"), integration.getShardSequenceNumbers());
        verify(writer, times(1)).applyChanges(any(), any(), any(), any());
        assertTrue(integration.isBackfillCompleted());

        // Once the table can be made, the backfill starts over to fill it, and a scan that completes marks it so.
        doReturn(true).when(writer).createCurrentStateTable(any(), any(), any(), any());
        consumer.pollOnce(integration);

        assertTrue(integration.isBackfillCompleted());
        assertTrue(integration.isCurrentStateProjected());
        verify(redshiftService, times(1)).updateIntegrationBackfillProgress(integration.getAccountId(),
                integration.getIntegrationArn(), null, false);
        verify(redshiftService).updateIntegrationCurrentState(integration.getAccountId(),
                integration.getIntegrationArn(), true);
        verify(writer, times(1)).resetCurrentStateTable(any(), any(), any());
    }

    @Test
    void aTableTheIntegrationCannotMakeStallsTheProjectionAloneWhileTheScanLands() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        doThrow(new AwsException("InvalidParameterValue", "Table public.\"x\" is not ours.", 400))
                .when(writer).createCurrentStateTable(any(), any(), any(), any());
        String tableName = "orders-refused-" + System.nanoTime();
        TableDefinition table = createTableAs("111111111111", tableName, false);
        RequestScopes.runAs("111111111111", () -> dynamoDbService.putItem(tableName, itemWithId("1"), "us-east-1"));
        Integration integration = tableSourceIntegration(table);

        new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, redshiftService, writer).pollOnce(integration);

        verify(writer).writeBatch(eq(integration.getAccountId()), eq(WAREHOUSE_ARN), eq("floci_zetl_orders"), any());
        verify(writer, never()).applyChanges(any(), any(), any(), any());
        verify(writer, never()).resetCurrentStateTable(any(), any(), any());
        assertTrue(integration.isBackfillCompleted());
        assertFalse(integration.isCurrentStateProjected());
        verify(redshiftService).updateIntegrationBackfillProgress(integration.getAccountId(),
                integration.getIntegrationArn(), null, true);
        verify(redshiftService, never()).updateIntegrationCurrentState(any(), any(), eq(true));
    }

    @Test
    void aTableMadeAfreshOverARunningIntegrationIsFilledByAnotherScan() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        doReturn(true).when(writer).createCurrentStateTable(any(), any(), any(), any());
        String tableName = "orders-dropped-" + System.nanoTime();
        TableDefinition table = createTableAs("111111111111", tableName, true);
        Integration integration = tableSourceIntegration(table);
        integration.setBackfillCompleted(true);
        integration.setCurrentStateProjected(true);
        streams.shard(table.getStreamArn(), SHARD_ID, null).add(imageRecord("s1", "INSERT", "1", "first"));

        new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, redshiftService, writer).pollOnce(integration);

        // The user dropped the table: it is made again and holds nothing but this batch, so the scan refills it.
        assertFalse(integration.isBackfillCompleted());
        assertFalse(integration.isCurrentStateProjected());
        verify(redshiftService).updateIntegrationBackfillProgress(integration.getAccountId(),
                integration.getIntegrationArn(), null, false);
    }

    @Test
    void aPersistedIntegrationWithoutACurrentStateScansItsSourceAgainAtStartup() {
        Integration integration = streamingIntegration();
        integration.setBackfillLastEvaluatedKey(null);
        assertTrue(integration.isBackfillCompleted());
        assertFalse(integration.isCurrentStateProjected());

        startPersisted(CheckpointLifetime.STREAM, integration);

        assertFalse(integration.isBackfillCompleted());
    }

    @Test
    void aPersistedIntegrationWhoseCurrentStateIsFilledKeepsItsBackfill() {
        Integration integration = streamingIntegration();
        integration.setCurrentStateProjected(true);

        startPersisted(CheckpointLifetime.STREAM, integration);

        assertTrue(integration.isBackfillCompleted());
    }

    @Test
    void backfilledItemsAreProjectedAsUpserts() {
        FakeStreamReader streams = new FakeStreamReader(CheckpointLifetime.PROCESS);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        String tableName = "orders-backfill-state-" + System.nanoTime();
        TableDefinition table = createTableAs("111111111111", tableName, false);
        ObjectNode item = itemWithId("1");
        RequestScopes.runAs("111111111111", () -> dynamoDbService.putItem(tableName, item, "us-east-1"));
        Integration integration = tableSourceIntegration(table);

        new RedshiftDynamoDbZeroEtlConsumer(streams, dynamoDb, mock(RedshiftService.class), writer)
                .pollOnce(integration);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Change>> changes = ArgumentCaptor.forClass(List.class);
        InOrder inOrder = inOrder(writer);
        inOrder.verify(writer).createCurrentStateTable(eq(integration.getAccountId()), eq(WAREHOUSE_ARN), any(),
                eq("one"));
        // A table left behind by an earlier integration of the source is emptied before the first page lands.
        inOrder.verify(writer).resetCurrentStateTable(eq(integration.getAccountId()), eq(WAREHOUSE_ARN), any());
        inOrder.verify(writer).writeBatch(eq(integration.getAccountId()), eq(WAREHOUSE_ARN), eq("floci_zetl_orders"), any());
        inOrder.verify(writer).applyChanges(eq(integration.getAccountId()), eq(WAREHOUSE_ARN), any(), changes.capture());
        assertEquals(List.of(new Change(item, item)), changes.getValue());
    }

    private static DynamoDbStreamReader.Record imageRecord(String sequenceNumber, String eventName, String id,
                                                           String note) {
        DynamoDbStreamReader.Record record = keysOnlyRecord(sequenceNumber, eventName, id);
        ObjectNode image = ((ObjectNode) record.awsRecord().path("dynamodb")).putObject("NewImage");
        image.putObject("id").put("S", id);
        image.putObject("note").put("S", note);
        return record;
    }

    private static DynamoDbStreamReader.Record keysOnlyRecord(String sequenceNumber, String eventName, String id) {
        ObjectNode awsRecord = MAPPER.createObjectNode()
                .put("eventID", "event-" + sequenceNumber)
                .put("eventName", eventName);
        ObjectNode dynamodb = awsRecord.putObject("dynamodb").put("SequenceNumber", sequenceNumber);
        dynamodb.putObject("Keys").putObject("id").put("S", id);
        return new DynamoDbStreamReader.Record(sequenceNumber, awsRecord);
    }

    private TableDefinition createTableAs(String accountId, String tableName, boolean streamed) {
        return RequestScopes.callAs(accountId, () -> {
            dynamoDbService.createTable(tableName, List.of(new KeySchemaElement("id", "HASH")),
                    List.of(new AttributeDefinition("id", "S")), 5L, 5L, "us-east-1");
            return streamed ? dynamoDbService.enableStream(tableName, null, "us-east-1")
                    : dynamoDbService.describeTable(tableName, "us-east-1");
        });
    }

    private static Integration tableSourceIntegration(TableDefinition table) {
        Integration integration = integration(table.getTableName());
        integration.setSourceArn(table.getTableArn());
        integration.setSourceStreamArn(table.getStreamArn());
        return integration;
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
        integration.setTargetArn(WAREHOUSE_ARN);
        integration.setTargetClusterIdentifier("warehouse");
        integration.setLandingTableName("floci_zetl_orders");
        integration.setPollingEnabled(true);
        return integration;
    }
}
