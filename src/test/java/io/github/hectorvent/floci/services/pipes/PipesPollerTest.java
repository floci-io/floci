package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.pipes.model.DesiredState;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipesPollerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STREAM_ARN =
            "arn:aws:dynamodb:us-east-1:000000000000:table/orders/stream/2026-09-01T00:00:00.000";
    private static final String SHARD_ID = "shardId-0000000001-00000000001";
    private static final String DLQ_ARN = "arn:aws:sqs:us-east-1:000000000000:pipe-dlq";

    @Mock private Vertx vertx;
    @Mock private SqsService sqsService;
    @Mock private KinesisService kinesisService;
    @Mock private PipesKafkaConsumerManager kafkaConsumerManager;
    @Mock private PipesTargetInvoker targetInvoker;
    @Mock private EmulatorConfig config;

    private FakeStreamReader streams;
    private PipesPoller poller;

    @BeforeEach
    void setUp() {
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        lenient().when(kafkaConsumerManager.resolveBatchSize(any(), anyInt())).thenReturn(10);
        streams = new FakeStreamReader();
        poller = pollerOver(streams);
    }

    private PipesPoller pollerOver(DynamoDbStreamReader streamReader) {
        return new PipesPoller(vertx, sqsService, kinesisService, streamReader,
                kafkaConsumerManager, targetInvoker, new PipesFilterMatcher(MAPPER), MAPPER, config);
    }

    /**
     * Serves one open shard from memory the way the native engine does. A cursor is the sequence to
     * read after, or empty for the trim horizon, and a sequence older than the oldest retained record
     * is trimmed.
     */
    static class FakeStreamReader implements DynamoDbStreamReader {

        final List<Record> shard = new CopyOnWriteArrayList<>();

        @Override
        public ShardsPage describeStream(Stream stream, String exclusiveStartShardId, Integer limit) {
            return new ShardsPage(stream, List.of(new Shard(SHARD_ID, null, null, null)), null);
        }

        @Override
        public Cursor getShardIterator(Stream stream, String shardId, Position position, String sequenceNumber) {
            return new Cursor(stream, shardId, position == Position.TRIM_HORIZON ? "" : sequenceNumber);
        }

        @Override
        public RecordsPage getRecords(Cursor cursor, int limit) {
            String after = cursor.token();
            if (!after.isEmpty() && !shard.isEmpty() && after.compareTo(shard.get(0).sequenceNumber()) < 0) {
                throw new AwsException("TrimmedDataAccessException",
                        "The requested sequence number has been trimmed", 400);
            }
            List<Record> page = shard.stream()
                    .filter(record -> record.sequenceNumber().compareTo(after) > 0)
                    .limit(limit)
                    .toList();
            return new RecordsPage(page, page.isEmpty() ? cursor
                    : new Cursor(cursor.stream(), cursor.shardId(), page.get(page.size() - 1).sequenceNumber()));
        }

        @Override
        public CheckpointLifetime checkpointLifetime() {
            return CheckpointLifetime.PROCESS;
        }
    }

    @Test
    void asEventArrayWrapsSingleObjectInBatchArray() throws Exception {
        // Pipes delivers events to a target as a batch array; a single-object enrichment response
        // must become a one-element array so a target like "InputPath": "$.[0]" can unwrap it.
        String wrapped = PipesPoller.asEventArray(MAPPER, "{\"systemId\":\"S1\",\"solutions\":[1]}");
        JsonNode node = MAPPER.readTree(wrapped);
        assertTrue(node.isArray());
        assertEquals(1, node.size());
        assertEquals("S1", node.get(0).path("systemId").asText());
    }

    @Test
    void asEventArrayLeavesArrayResponseUnchanged() throws Exception {
        String out = PipesPoller.asEventArray(MAPPER, "[{\"a\":1},{\"a\":2}]");
        JsonNode node = MAPPER.readTree(out);
        assertTrue(node.isArray());
        assertEquals(2, node.size());
        assertEquals(2, node.get(1).path("a").asInt());
    }

    @Test
    void asEventArrayWrapsNonJsonAsSingleStringEvent() throws Exception {
        String out = PipesPoller.asEventArray(MAPPER, "not-json");
        JsonNode node = MAPPER.readTree(out);
        assertTrue(node.isArray());
        assertEquals(1, node.size());
        assertEquals("not-json", node.get(0).asText());
    }

    @Test
    void pollSqs_enrichmentToNonLambdaTargetForwardsRawResponse() throws Exception {
        // A non-Lambda target (here Step Functions) must receive the raw enrichment response, not a
        // one-element batch array — array-wrapping would start the execution with [{...}] instead of {...}.
        Pipe pipe = new Pipe();
        pipe.setName("enrich-sfn");
        pipe.setSource("arn:aws:sqs:us-east-1:000000000000:src-queue");
        pipe.setEnrichment("arn:aws:lambda:us-east-1:000000000000:function:enrich");
        pipe.setTarget("arn:aws:states:us-east-1:000000000000:stateMachine:tgt");

        Message msg = new Message("{\"orderId\":\"o1\"}");
        msg.setMessageId("m1");
        msg.setReceiptHandle("rh1");
        when(sqsService.receiveMessage(anyString(), anyInt(), anyInt(), anyInt(), eq("us-east-1")))
                .thenReturn(List.of(msg));
        when(targetInvoker.applyEnrichment(eq(pipe), anyString(), eq("us-east-1")))
                .thenReturn("{\"systemId\":\"S1\"}");

        poller.pollSqs(pipe, "us-east-1");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(targetInvoker).invoke(eq(pipe), payload.capture(), eq("us-east-1"));
        JsonNode delivered = MAPPER.readTree(payload.getValue());
        assertFalse(delivered.isArray(),
                "non-Lambda target must receive the raw enrichment response, not a batch array");
        assertEquals("S1", delivered.path("systemId").asText());
    }

    @Test
    void pollSqs_enrichmentToLambdaTargetWrapsResponseInBatchArray() throws Exception {
        // A Lambda target expects the SQSRecord[]-style batch, so a single-object enrichment response
        // is wrapped in a one-element array.
        Pipe pipe = new Pipe();
        pipe.setName("enrich-lambda");
        pipe.setSource("arn:aws:sqs:us-east-1:000000000000:src-queue");
        pipe.setEnrichment("arn:aws:lambda:us-east-1:000000000000:function:enrich");
        pipe.setTarget("arn:aws:lambda:us-east-1:000000000000:function:tgt");

        Message msg = new Message("{\"orderId\":\"o1\"}");
        msg.setMessageId("m1");
        msg.setReceiptHandle("rh1");
        when(sqsService.receiveMessage(anyString(), anyInt(), anyInt(), anyInt(), eq("us-east-1")))
                .thenReturn(List.of(msg));
        when(targetInvoker.applyEnrichment(eq(pipe), anyString(), eq("us-east-1")))
                .thenReturn("{\"systemId\":\"S1\"}");

        poller.pollSqs(pipe, "us-east-1");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(targetInvoker).invoke(eq(pipe), payload.capture(), eq("us-east-1"));
        JsonNode delivered = MAPPER.readTree(payload.getValue());
        assertTrue(delivered.isArray(), "Lambda target must receive the batch array shape");
        assertEquals(1, delivered.size());
        assertEquals("S1", delivered.get(0).path("systemId").asText());
    }

    @Test
    void pollKafka_filtersUsingDecodedPayloadButDeliversOriginalRecord() throws Exception {
        Pipe pipe = selfManagedKafkaPipe();
        byte[] key = "customer-123".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] value = "{\"status\":\"active\",\"id\":\"order-1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] traceId = "abc123".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        KafkaRecordDto record = kafkaRecord("orders", 0, 42L, key, value, new KafkaHeaderDto("traceId", traceId));

        when(kafkaConsumerManager.poll(pipe)).thenReturn(List.of(record));
        when(kafkaConsumerManager.resolveBootstrapServers(pipe)).thenReturn("broker-1:9092");

        poller.pollKafka(pipe, "us-east-1");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(targetInvoker).invoke(eq(pipe), payloadCaptor.capture(), eq("us-east-1"));
        verify(kafkaConsumerManager).commit(eq(pipe), anyList());

        JsonNode delivered = MAPPER.readTree(payloadCaptor.getValue());
        assertEquals("orders", delivered.path("topic").asText());
        assertTrue(delivered.path("partition").isInt());
        assertEquals("broker-1:9092", delivered.path("bootstrapServers").asText());
        assertEquals("Y3VzdG9tZXItMTIz", delivered.path("key").asText());
        assertEquals("eyJzdGF0dXMiOiJhY3RpdmUiLCJpZCI6Im9yZGVyLTEifQ==", delivered.path("value").asText());
        assertTrue(delivered.path("headers").get(0).path("traceId").isArray());
        assertEquals(List.of(97, 98, 99, 49, 50, 51),
                MAPPER.convertValue(delivered.path("headers").get(0).path("traceId"), List.class));
        assertFalse(delivered.has("eventSourceKey"));
        assertTrue(delivered.path("value").isTextual());
    }

    @Test
    void pollKafka_doesNotCommitWhenDeliveryFails() throws Exception {
        Pipe pipe = selfManagedKafkaPipe();
        KafkaRecordDto record = kafkaRecord("orders", 0, 7L, null,
                "{\"status\":\"active\"}".getBytes(StandardCharsets.UTF_8));

        when(kafkaConsumerManager.poll(pipe)).thenReturn(List.of(record));
        when(kafkaConsumerManager.resolveBootstrapServers(pipe)).thenReturn("broker-1:9092");
        doThrow(new RuntimeException("boom"))
                .when(targetInvoker).invoke(eq(pipe), anyString(), eq("us-east-1"));

        poller.pollKafka(pipe, "us-east-1");

        verify(kafkaConsumerManager, never()).commit(eq(pipe), anyList());
    }

    @Test
    void pollKafka_commitsDeliveredPrefixWhenLaterRecordFails() throws Exception {
        Pipe pipe = selfManagedKafkaPipe();
        KafkaRecordDto first = kafkaRecord("orders", 0, 0L, null,
                "{\"status\":\"active\",\"id\":\"order-1\"}".getBytes(StandardCharsets.UTF_8));
        KafkaRecordDto second = kafkaRecord("orders", 0, 1L, null,
                "{\"status\":\"active\",\"id\":\"order-2\"}".getBytes(StandardCharsets.UTF_8));

        when(kafkaConsumerManager.poll(pipe)).thenReturn(List.of(first, second));
        when(kafkaConsumerManager.resolveBootstrapServers(pipe)).thenReturn("broker-1:9092");
        doNothing()
                .doThrow(new RuntimeException("boom"))
                .when(targetInvoker).invoke(eq(pipe), anyString(), eq("us-east-1"));

        poller.pollKafka(pipe, "us-east-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KafkaOffsetDto>> offsetsCaptor = ArgumentCaptor.forClass(List.class);
        verify(kafkaConsumerManager).commit(eq(pipe), offsetsCaptor.capture());
        assertEquals(1L, singleOffset(offsetsCaptor.getValue(), "orders", 0));
    }

    @Test
    void pollKafka_laterGeneratedBatchDoesNotCommitPastAnEarlierFailureInTheSamePartition() throws Exception {
        // A REST Proxy poll can return more than one BatchSize worth of records, so pollKafka
        // splits them into several generated batches. If the first batch fails to deliver record 0
        // and a later batch then succeeds delivering record 1 from the same partition, the second
        // batch's commit must not advance past record 0, or it is lost forever.
        Pipe pipe = selfManagedKafkaPipe();
        when(kafkaConsumerManager.resolveBatchSize(eq(pipe), anyInt())).thenReturn(1);
        KafkaRecordDto first = kafkaRecord("orders", 0, 0L, null,
                "{\"status\":\"active\",\"id\":\"order-1\"}".getBytes(StandardCharsets.UTF_8));
        KafkaRecordDto second = kafkaRecord("orders", 0, 1L, null,
                "{\"status\":\"active\",\"id\":\"order-2\"}".getBytes(StandardCharsets.UTF_8));

        when(kafkaConsumerManager.poll(pipe)).thenReturn(List.of(first, second));
        when(kafkaConsumerManager.resolveBootstrapServers(pipe)).thenReturn("broker-1:9092");
        doThrow(new RuntimeException("boom"))
                .doNothing()
                .when(targetInvoker).invoke(eq(pipe), anyString(), eq("us-east-1"));

        poller.pollKafka(pipe, "us-east-1");

        verify(kafkaConsumerManager, never()).commit(eq(pipe), anyList());
    }

    @Test
    void pollKafka_representsNullKeyAndValueAsJsonNull() throws Exception {
        Pipe pipe = nullableSelfManagedKafkaPipe();
        KafkaRecordDto record = kafkaRecord("orders", 0, 3L, null, null);

        when(kafkaConsumerManager.poll(pipe)).thenReturn(List.of(record));
        when(kafkaConsumerManager.resolveBootstrapServers(pipe)).thenReturn("broker-1:9092");

        poller.pollKafka(pipe, "us-east-1");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(targetInvoker).invoke(eq(pipe), payloadCaptor.capture(), eq("us-east-1"));
        JsonNode delivered = MAPPER.readTree(payloadCaptor.getValue());
        assertTrue(delivered.path("key").isNull());
        assertTrue(delivered.path("value").isNull());
    }

    @Test
    void pollKafka_lambdaCommitsSuccessfulPrefixBeforeLaterFailure() throws Exception {
        Pipe pipe = lambdaSelfManagedKafkaPipe();
        KafkaRecordDto first = kafkaRecord("orders", 0, 0L, null,
                "{\"status\":\"active\",\"id\":\"order-1\"}".getBytes(StandardCharsets.UTF_8));
        KafkaRecordDto skipped = kafkaRecord("orders", 0, 1L, null,
                "{\"status\":\"inactive\",\"id\":\"order-2\"}".getBytes(StandardCharsets.UTF_8));
        KafkaRecordDto failing = kafkaRecord("orders", 0, 2L, null,
                "{\"status\":\"active\",\"id\":\"order-3\"}".getBytes(StandardCharsets.UTF_8));

        when(kafkaConsumerManager.poll(pipe)).thenReturn(List.of(first, skipped, failing));
        when(kafkaConsumerManager.resolveBootstrapServers(pipe)).thenReturn("broker-1:9092");
        doNothing()
                .doThrow(new RuntimeException("boom"))
                .when(targetInvoker).invoke(eq(pipe), anyString(), eq("us-east-1"));

        poller.pollKafka(pipe, "us-east-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KafkaOffsetDto>> offsetsCaptor = ArgumentCaptor.forClass(List.class);
        verify(kafkaConsumerManager).commit(eq(pipe), offsetsCaptor.capture());
        assertEquals(2L, singleOffset(offsetsCaptor.getValue(), "orders", 0));
    }

    // ──────────────────────────── DynamoDB Streams ────────────────────────────

    @Test
    void targetFailureWithoutDlqRereadsTheSameDynamoDbRecordsNextPoll() throws Exception {
        Pipe pipe = dynamoDbPipe("{\"StartingPosition\":\"TRIM_HORIZON\"}");
        streams.shard.add(ddbRecord("001", "active"));
        doThrow(new RuntimeException("target down")).doNothing()
                .when(targetInvoker).invoke(eq(pipe), anyString(), eq("us-east-1"));

        poller.startPolling(pipe);
        poller.pollDynamoDbStreams(pipe, "us-east-1");
        poller.pollDynamoDbStreams(pipe, "us-east-1");
        poller.pollDynamoDbStreams(pipe, "us-east-1");

        assertEquals(List.of(List.of("001"), List.of("001")), deliveredSequences(pipe));
    }

    @Test
    void dynamoDbBatchSentToTheDeadLetterQueueIsCommitted() throws Exception {
        Pipe pipe = dynamoDbPipe("{\"DeadLetterConfig\":{\"Arn\":\"" + DLQ_ARN + "\"}}");
        streams.shard.add(ddbRecord("001", "active"));
        doThrow(new RuntimeException("target down"))
                .when(targetInvoker).invoke(eq(pipe), anyString(), eq("us-east-1"));

        poller.startPolling(pipe);
        poller.pollDynamoDbStreams(pipe, "us-east-1");
        streams.shard.add(ddbRecord("002", "active"));
        poller.pollDynamoDbStreams(pipe, "us-east-1");

        assertEquals(List.of(List.of("001"), List.of("002")), deliveredSequences(pipe));
        verify(sqsService, times(2)).sendMessage(contains("pipe-dlq"), anyString(), eq(0), eq("us-east-1"));
    }

    @Test
    void dynamoDbBatchTheDeadLetterQueueRejectsIsRereadNextPoll() throws Exception {
        Pipe pipe = dynamoDbPipe("{\"DeadLetterConfig\":{\"Arn\":\"" + DLQ_ARN + "\"}}");
        streams.shard.add(ddbRecord("001", "active"));
        doThrow(new RuntimeException("target down"))
                .when(targetInvoker).invoke(eq(pipe), anyString(), eq("us-east-1"));
        doThrow(new RuntimeException("dlq down"))
                .when(sqsService).sendMessage(anyString(), anyString(), anyInt(), anyString());

        poller.startPolling(pipe);
        poller.pollDynamoDbStreams(pipe, "us-east-1");
        poller.pollDynamoDbStreams(pipe, "us-east-1");

        assertEquals(List.of(List.of("001"), List.of("001")), deliveredSequences(pipe));
    }

    @Test
    void laterDynamoDbRecordsWaitBehindAnUndeliveredBatch() throws Exception {
        Pipe pipe = dynamoDbPipe("{\"BatchSize\":1}");
        streams.shard.add(ddbRecord("001", "active"));
        streams.shard.add(ddbRecord("002", "active"));
        doThrow(new RuntimeException("target down")).doNothing()
                .when(targetInvoker).invoke(eq(pipe), anyString(), eq("us-east-1"));

        poller.startPolling(pipe);
        poller.pollDynamoDbStreams(pipe, "us-east-1");
        poller.pollDynamoDbStreams(pipe, "us-east-1");
        poller.pollDynamoDbStreams(pipe, "us-east-1");

        assertEquals(List.of(List.of("001"), List.of("001"), List.of("002")), deliveredSequences(pipe));
    }

    @Test
    void latestStartingPositionDeliversOnlyDynamoDbRecordsWrittenAfterTheFirstPoll() throws Exception {
        Pipe pipe = dynamoDbPipe("{\"StartingPosition\":\"LATEST\"}");
        streams.shard.add(ddbRecord("001", "active"));

        poller.startPolling(pipe);
        poller.pollDynamoDbStreams(pipe, "us-east-1");
        streams.shard.add(ddbRecord("002", "active"));
        poller.pollDynamoDbStreams(pipe, "us-east-1");

        assertEquals(List.of(List.of("002")), deliveredSequences(pipe));
    }

    @Test
    void stoppedAndRestartedLatestPipeResumesAfterItsLastDeliveredRecord() throws Exception {
        Pipe pipe = dynamoDbPipe("{\"StartingPosition\":\"LATEST\"}");
        streams.shard.add(ddbRecord("000", "active"));
        poller.startPolling(pipe);
        poller.pollDynamoDbStreams(pipe, "us-east-1");
        streams.shard.add(ddbRecord("001", "active"));
        poller.pollDynamoDbStreams(pipe, "us-east-1");

        poller.stopPolling(pipe);
        streams.shard.add(ddbRecord("002", "active"));
        poller.startPolling(pipe);
        streams.shard.add(ddbRecord("003", "active"));
        poller.pollDynamoDbStreams(pipe, "us-east-1");

        assertEquals(List.of(List.of("001"), List.of("002", "003")), deliveredSequences(pipe));
    }

    @Test
    void deletedPipeForgetsItsProgress() throws Exception {
        PipesService pipes = pipesOverPoller();
        Pipe pipe = createDynamoDbPipe(pipes);
        streams.shard.add(ddbRecord("001", "active"));
        poller.pollDynamoDbStreams(pipe, "us-east-1");

        pipes.deletePipe(pipe.getName(), "us-east-1");
        Pipe recreated = createDynamoDbPipe(pipes);
        poller.pollDynamoDbStreams(recreated, "us-east-1");

        assertEquals(List.of(List.of("001")), deliveredSequences(recreated));
    }

    @Test
    void aPollThatOutlivesItsDeletedPipeLeavesTheRecreatedPipeAlone() throws Exception {
        Pipe pipe = dynamoDbPipe("{\"StartingPosition\":\"TRIM_HORIZON\"}");
        pipe.setCreationTime(Instant.parse("2026-09-01T00:00:00Z"));
        streams.shard.add(ddbRecord("001", "active"));
        CountDownLatch delivering = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            delivering.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(targetInvoker).invoke(any(), anyString(), eq("us-east-1"));
        poller.startPolling(pipe);
        CompletableFuture<Void> inFlight =
                CompletableFuture.runAsync(() -> poller.pollDynamoDbStreams(pipe, "us-east-1"));
        assertTrue(delivering.await(5, TimeUnit.SECONDS));

        poller.stopPolling(pipe);
        poller.forget(pipe);
        release.countDown();
        inFlight.get(5, TimeUnit.SECONDS);
        Pipe recreated = dynamoDbPipe("{\"StartingPosition\":\"TRIM_HORIZON\"}");
        recreated.setCreationTime(Instant.parse("2026-09-02T00:00:00Z"));
        poller.startPolling(recreated);
        // A tick the delete raced: it starts once the pipe is recreated under the same ARN.
        poller.pollDynamoDbStreams(pipe, "us-east-1");
        poller.pollDynamoDbStreams(recreated, "us-east-1");

        assertEquals(List.of(List.of("001")), deliveredSequences(recreated));
        assertEquals(List.of(List.of("001")), deliveredSequences(pipe));
    }

    @Test
    void latestPinFailureDoesNotFailStartPolling() throws Exception {
        AtomicBoolean readerDown = new AtomicBoolean(true);
        FakeStreamReader flaky = new FakeStreamReader() {
            @Override
            public Map<String, String> newestSequenceNumbers(Stream stream) {
                if (readerDown.get()) {
                    throw new IllegalStateException("engine unreachable");
                }
                return super.newestSequenceNumbers(stream);
            }
        };
        PipesPoller flakyPoller = pollerOver(flaky);
        Pipe pipe = dynamoDbPipe("{\"StartingPosition\":\"LATEST\"}");
        flaky.shard.add(ddbRecord("001", "active"));

        flakyPoller.startPolling(pipe);
        assertThrows(IllegalStateException.class, () -> flakyPoller.pollDynamoDbStreams(pipe, "us-east-1"));
        readerDown.set(false);
        flakyPoller.pollDynamoDbStreams(pipe, "us-east-1");
        flaky.shard.add(ddbRecord("002", "active"));
        flakyPoller.pollDynamoDbStreams(pipe, "us-east-1");

        assertEquals(List.of(List.of("002")), deliveredSequences(pipe));
    }

    @Test
    void dynamoDbRecordsAreFilteredAndDeliveredInTheAwsStreamRecordShape() throws Exception {
        Pipe pipe = dynamoDbPipe("{}");
        pipe.setSourceParameters(MAPPER.readTree("""
                {"DynamoDBStreamParameters":{"StartingPosition":"TRIM_HORIZON"},
                 "FilterCriteria":{"Filters":[
                   {"Pattern":"{\\"dynamodb\\":{\\"NewImage\\":{\\"status\\":{\\"S\\":[\\"active\\"]}}}}"}]}}"""));
        DynamoDbStreamReader.Record active = ddbRecord("001", "active");
        JsonNode original = active.awsRecord().deepCopy();
        streams.shard.add(active);
        streams.shard.add(ddbRecord("002", "inactive"));

        poller.startPolling(pipe);
        poller.pollDynamoDbStreams(pipe, "us-east-1");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(targetInvoker).invoke(eq(pipe), payload.capture(), eq("us-east-1"));
        ObjectNode expected = original.deepCopy();
        expected.put("eventSourceARN", STREAM_ARN);
        ObjectNode event = MAPPER.createObjectNode();
        event.putArray("Records").add(expected);
        assertEquals(event, MAPPER.readTree(payload.getValue()));
        assertEquals(original, active.awsRecord(), "the reader's record is never mutated");
    }

    @Test
    void trimmedDynamoDbCheckpointRestartsAtTheTrimHorizon() throws Exception {
        Pipe pipe = dynamoDbPipe("{}");
        streams.shard.add(ddbRecord("001", "active"));
        poller.startPolling(pipe);
        poller.pollDynamoDbStreams(pipe, "us-east-1");
        streams.shard.clear();
        streams.shard.add(ddbRecord("003", "active"));

        poller.pollDynamoDbStreams(pipe, "us-east-1");
        poller.pollDynamoDbStreams(pipe, "us-east-1");

        assertEquals(List.of(List.of("001"), List.of("003")), deliveredSequences(pipe));
    }

    @Test
    void pollTickReturnsAtOnceAndReadsTheStreamOnAPipesPollerThread() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> readThread = new AtomicReference<>();
        AtomicBoolean readReturned = new AtomicBoolean();
        PipesPoller blockedPoller = pollerOver(new FakeStreamReader() {
            @Override
            public ShardsPage describeStream(Stream stream, String exclusiveStartShardId, Integer limit) {
                readThread.set(Thread.currentThread().getName());
                reading.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                readReturned.set(true);
                return super.describeStream(stream, exclusiveStartShardId, limit);
            }
        });
        Pipe pipe = dynamoDbPipe("{}");
        blockedPoller.startPolling(pipe);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Handler<Long>> tick = ArgumentCaptor.forClass(Handler.class);
        verify(vertx).setPeriodic(eq(1000L), tick.capture());

        try {
            tick.getValue().handle(1L);

            assertFalse(readReturned.get(), "the tick returned while the read is still blocked");
            assertTrue(reading.await(5, TimeUnit.SECONDS), "the tick hands the read to the poll executor");
            assertEquals("pipes-poller", readThread.get());
        } finally {
            release.countDown();
        }
    }

    private Pipe dynamoDbPipe(String dynamoDbStreamParameters) throws Exception {
        Pipe pipe = new Pipe();
        pipe.setName("orders-stream-pipe");
        pipe.setArn("arn:aws:pipes:us-east-1:000000000000:pipe/orders-stream-pipe");
        pipe.setSource(STREAM_ARN);
        pipe.setTarget("arn:aws:lambda:us-east-1:000000000000:function:orders-target");
        pipe.setDesiredState(DesiredState.RUNNING);
        pipe.setSourceParameters(MAPPER.readTree("{\"DynamoDBStreamParameters\":" + dynamoDbStreamParameters + "}"));
        return pipe;
    }

    private PipesService pipesOverPoller() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        doReturn(AccountAwareStorageBackend.inMemory("000000000000"))
                .when(storageFactory).create(anyString(), anyString(), any());
        return new PipesService(storageFactory, new RegionResolver("us-east-1", "000000000000"), poller);
    }

    private Pipe createDynamoDbPipe(PipesService pipes) throws Exception {
        return pipes.createPipe("orders-stream-pipe", STREAM_ARN,
                "arn:aws:lambda:us-east-1:000000000000:function:orders-target",
                "arn:aws:iam::000000000000:role/pipe-role", null, null, null,
                MAPPER.readTree("{\"DynamoDBStreamParameters\":{\"StartingPosition\":\"TRIM_HORIZON\"}}"),
                null, null, null, "us-east-1");
    }

    /** A GetRecords {@code Records[]} element as the native engine returns it. */
    private static DynamoDbStreamReader.Record ddbRecord(String sequenceNumber, String status) {
        ObjectNode awsRecord = MAPPER.createObjectNode()
                .put("eventID", "event-" + sequenceNumber)
                .put("eventName", "INSERT")
                .put("eventVersion", "1.1")
                .put("eventSource", "aws:dynamodb")
                .put("awsRegion", "us-east-1");
        ObjectNode dynamodb = awsRecord.putObject("dynamodb");
        dynamodb.putObject("NewImage").putObject("status").put("S", status);
        dynamodb.put("SequenceNumber", sequenceNumber).put("SizeBytes", 50).put("StreamViewType", "NEW_IMAGE");
        return new DynamoDbStreamReader.Record(sequenceNumber, awsRecord);
    }

    /** The sequence numbers of each batch the Lambda target was invoked with, in invocation order. */
    private List<List<String>> deliveredSequences(Pipe pipe) throws Exception {
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(targetInvoker, atLeastOnce()).invoke(eq(pipe), payloads.capture(), eq("us-east-1"));
        List<List<String>> batches = new ArrayList<>();
        for (String payload : payloads.getAllValues()) {
            List<String> batch = new ArrayList<>();
            for (JsonNode record : MAPPER.readTree(payload).path("Records")) {
                batch.add(record.path("dynamodb").path("SequenceNumber").asText());
            }
            batches.add(batch);
        }
        return batches;
    }

    private Pipe selfManagedKafkaPipe() throws Exception {
        Pipe pipe = new Pipe();
        pipe.setName("orders-pipe");
        pipe.setArn("arn:aws:pipes:us-east-1:000000000000:pipe/orders-pipe");
        pipe.setSource("smk://broker-1:9092");
        pipe.setTarget("arn:aws:sqs:us-east-1:000000000000:orders-target");
        pipe.setSourceParameters(MAPPER.readTree("""
                {
                  "SelfManagedKafkaParameters": {
                    "TopicName": "orders"
                  },
                  "FilterCriteria": {
                    "Filters": [
                      {"Pattern": "{\\\"value\\\": {\\\"status\\\": [\\\"active\\\"]}}"}
                    ]
                  }
                }
                """));
        return pipe;
    }

    private Pipe nullableSelfManagedKafkaPipe() throws Exception {
        Pipe pipe = new Pipe();
        pipe.setName("nullable-orders-pipe");
        pipe.setArn("arn:aws:pipes:us-east-1:000000000000:pipe/nullable-orders-pipe");
        pipe.setSource("smk://broker-1:9092");
        pipe.setTarget("arn:aws:sqs:us-east-1:000000000000:orders-target");
        pipe.setSourceParameters(MAPPER.readTree("""
                {
                  "SelfManagedKafkaParameters": {
                    "TopicName": "orders"
                  },
                  "FilterCriteria": {
                    "Filters": [
                      {"Pattern": "{\\\"key\\\": [{\\\"exists\\\": false}]}"}
                    ]
                  }
                }
                """));
        return pipe;
    }

    private Pipe lambdaSelfManagedKafkaPipe() throws Exception {
        Pipe pipe = selfManagedKafkaPipe();
        pipe.setTarget("arn:aws:lambda:us-east-1:000000000000:function:orders-target");
        return pipe;
    }

    private static KafkaRecordDto kafkaRecord(String topic, int partition, long offset, byte[] key, byte[] value,
                                              KafkaHeaderDto... headers) {
        return new KafkaRecordDto(topic, partition, offset, System.currentTimeMillis(),
                KafkaRecordDto.DEFAULT_TIMESTAMP_TYPE, key, value, List.of(headers));
    }

    private static long singleOffset(List<KafkaOffsetDto> offsets, String topic, int partition) {
        return offsets.stream()
                .filter(offset -> offset.topic().equals(topic) && offset.partition() == partition)
                .mapToLong(KafkaOffsetDto::offset)
                .findFirst()
                .orElseThrow();
    }
}
