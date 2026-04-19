package io.github.hectorvent.floci.services.kinesis;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.kinesis.model.KinesisConsumer;
import io.github.hectorvent.floci.services.kinesis.model.KinesisRecord;
import io.github.hectorvent.floci.services.kinesis.model.KinesisShard;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KinesisServiceTest {

    private static final String REGION = "us-east-1";

    private KinesisService kinesisService;

    @BeforeEach
    void setUp() {
        kinesisService = new KinesisService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000")
        );
    }

    @Test
    void createStream() {
        KinesisStream stream = kinesisService.createStream("my-stream", 2, REGION);

        assertEquals("my-stream", stream.getStreamName());
        assertNotNull(stream.getStreamArn());
        assertEquals(2, stream.getShards().size());
        assertEquals("ACTIVE", stream.getStreamStatus());
    }

    @Test
    void createStreamAlreadyExistsThrows() {
        kinesisService.createStream("my-stream", 1, REGION);
        assertThrows(AwsException.class, () ->
                kinesisService.createStream("my-stream", 1, REGION));
    }

    @Test
    void listStreams() {
        kinesisService.createStream("stream-a", 1, REGION);
        kinesisService.createStream("stream-b", 1, REGION);
        kinesisService.createStream("other", 1, "eu-west-1");

        List<String> names = kinesisService.listStreams(REGION);
        assertEquals(2, names.size());
        assertTrue(names.containsAll(List.of("stream-a", "stream-b")));
    }

    @Test
    void describeStreamNotFound() {
        assertThrows(AwsException.class, () ->
                kinesisService.describeStream("missing", REGION));
    }

    @Test
    void deleteStream() {
        kinesisService.createStream("to-delete", 1, REGION);
        kinesisService.deleteStream("to-delete", REGION);

        assertTrue(kinesisService.listStreams(REGION).isEmpty());
    }

    @Test
    void putAndGetRecord() {
        kinesisService.createStream("my-stream", 1, REGION);
        String seqNum = kinesisService.putRecord("my-stream",
                "hello".getBytes(StandardCharsets.UTF_8), "partition-1", REGION);

        assertNotNull(seqNum);

        KinesisStream stream = kinesisService.describeStream("my-stream", REGION);
        String shardId = stream.getShards().getFirst().getShardId();

        String iterator = kinesisService.getShardIterator("my-stream", shardId,
                "TRIM_HORIZON", null, REGION);
        Map<String, Object> result = kinesisService.getRecords(iterator, 10, REGION);

        @SuppressWarnings("unchecked")
        var records = (List<?>) result.get("Records");
        assertEquals(1, records.size());
    }

    @Test
    void getRecordsLatestIteratorReturnsEmpty() {
        kinesisService.createStream("my-stream", 1, REGION);
        kinesisService.putRecord("my-stream", "msg".getBytes(StandardCharsets.UTF_8), "pk", REGION);

        String shardId = kinesisService.describeStream("my-stream", REGION).getShards().getFirst().getShardId();
        String iterator = kinesisService.getShardIterator("my-stream", shardId, "LATEST", null, REGION);
        Map<String, Object> result = kinesisService.getRecords(iterator, 10, REGION);

        @SuppressWarnings("unchecked")
        var records = (List<?>) result.get("Records");
        assertTrue(records.isEmpty());
        assertEquals(0L, ((Number) result.get("MillisBehindLatest")).longValue());
    }

    @Test
    void millisBehindLatestIsZeroOnEmptyShard() {
        kinesisService.createStream("empty", 1, REGION);
        String shardId = kinesisService.describeStream("empty", REGION).getShards().getFirst().getShardId();
        String iterator = kinesisService.getShardIterator("empty", shardId, "TRIM_HORIZON", null, REGION);

        Map<String, Object> result = kinesisService.getRecords(iterator, 10, REGION);

        assertEquals(0L, ((Number) result.get("MillisBehindLatest")).longValue());
    }

    @Test
    void millisBehindLatestIsZeroWhenCaughtUp() {
        kinesisService.createStream("my-stream", 1, REGION);
        kinesisService.putRecord("my-stream", "a".getBytes(StandardCharsets.UTF_8), "pk", REGION);
        kinesisService.putRecord("my-stream", "b".getBytes(StandardCharsets.UTF_8), "pk", REGION);

        String shardId = kinesisService.describeStream("my-stream", REGION).getShards().getFirst().getShardId();
        String iterator = kinesisService.getShardIterator("my-stream", shardId, "TRIM_HORIZON", null, REGION);

        Map<String, Object> result = kinesisService.getRecords(iterator, 10, REGION);

        @SuppressWarnings("unchecked")
        var records = (List<?>) result.get("Records");
        assertEquals(2, records.size());
        assertEquals(0L, ((Number) result.get("MillisBehindLatest")).longValue());
    }

    @Test
    void millisBehindLatestIsTimeDeltaWhenBatchLimitHit() {
        kinesisService.createStream("my-stream", 1, REGION);
        kinesisService.putRecord("my-stream", "a".getBytes(StandardCharsets.UTF_8), "pk", REGION);
        kinesisService.putRecord("my-stream", "b".getBytes(StandardCharsets.UTF_8), "pk", REGION);
        kinesisService.putRecord("my-stream", "c".getBytes(StandardCharsets.UTF_8), "pk", REGION);

        // Overwrite timestamps so we can assert a deterministic delta.
        KinesisShard shard = kinesisService.describeStream("my-stream", REGION).getShards().getFirst();
        List<KinesisRecord> records = shard.getRecords();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        records.get(0).setApproximateArrivalTimestamp(base);
        records.get(1).setApproximateArrivalTimestamp(base.plusMillis(1500));
        records.get(2).setApproximateArrivalTimestamp(base.plusMillis(4000));

        String iterator = kinesisService.getShardIterator("my-stream", shard.getShardId(), "TRIM_HORIZON", null, REGION);

        Map<String, Object> result = kinesisService.getRecords(iterator, 2, REGION);

        @SuppressWarnings("unchecked")
        var returned = (List<?>) result.get("Records");
        assertEquals(2, returned.size());
        // Last returned = records[1] at +1500ms, tip = records[2] at +4000ms, delta = 2500ms
        assertEquals(2500L, ((Number) result.get("MillisBehindLatest")).longValue());
    }

    @Test
    void millisBehindLatestIsZeroWhenTimestampsMissing() {
        kinesisService.createStream("my-stream", 1, REGION);
        kinesisService.putRecord("my-stream", "a".getBytes(StandardCharsets.UTF_8), "pk", REGION);
        kinesisService.putRecord("my-stream", "b".getBytes(StandardCharsets.UTF_8), "pk", REGION);

        KinesisShard shard = kinesisService.describeStream("my-stream", REGION).getShards().getFirst();
        // Simulate a record with no arrival timestamp (e.g. legacy data or a partial put).
        shard.getRecords().getFirst().setApproximateArrivalTimestamp(null);

        String iterator = kinesisService.getShardIterator("my-stream", shard.getShardId(), "TRIM_HORIZON", null, REGION);
        Map<String, Object> result = kinesisService.getRecords(iterator, 1, REGION);

        // First record returned, second still ahead; null timestamp must not NPE.
        assertEquals(0L, ((Number) result.get("MillisBehindLatest")).longValue());
    }

    @Test
    void addAndListTags() {
        kinesisService.createStream("tagged", 1, REGION);
        kinesisService.addTagsToStream("tagged", Map.of("env", "prod", "team", "infra"), REGION);

        Map<String, String> tags = kinesisService.listTagsForStream("tagged", REGION);
        assertEquals("prod", tags.get("env"));
        assertEquals("infra", tags.get("team"));
    }

    @Test
    void removeTags() {
        kinesisService.createStream("tagged", 1, REGION);
        kinesisService.addTagsToStream("tagged", Map.of("env", "prod", "team", "infra"), REGION);
        kinesisService.removeTagsFromStream("tagged", List.of("env"), REGION);

        Map<String, String> tags = kinesisService.listTagsForStream("tagged", REGION);
        assertFalse(tags.containsKey("env"));
        assertTrue(tags.containsKey("team"));
    }

    @Test
    void registerAndDescribeConsumer() {
        KinesisStream stream = kinesisService.createStream("my-stream", 1, REGION);
        KinesisConsumer consumer = kinesisService.registerStreamConsumer(
                stream.getStreamArn(), "my-consumer", REGION);

        assertNotNull(consumer.getConsumerArn());
        assertEquals("my-consumer", consumer.getConsumerName());

        KinesisConsumer described = kinesisService.describeStreamConsumer(
                stream.getStreamArn(), "my-consumer", null, REGION);
        assertEquals(consumer.getConsumerArn(), described.getConsumerArn());
    }

    @Test
    void listStreamConsumers() {
        KinesisStream stream = kinesisService.createStream("my-stream", 1, REGION);
        kinesisService.registerStreamConsumer(stream.getStreamArn(), "c1", REGION);
        kinesisService.registerStreamConsumer(stream.getStreamArn(), "c2", REGION);

        List<KinesisConsumer> consumers = kinesisService.listStreamConsumers(stream.getStreamArn(), REGION);
        assertEquals(2, consumers.size());
    }

    @Test
    void deregisterConsumer() {
        KinesisStream stream = kinesisService.createStream("my-stream", 1, REGION);
        KinesisConsumer consumer = kinesisService.registerStreamConsumer(
                stream.getStreamArn(), "my-consumer", REGION);

        kinesisService.deregisterStreamConsumer(
                stream.getStreamArn(), "my-consumer", consumer.getConsumerArn(), REGION);

        assertTrue(kinesisService.listStreamConsumers(stream.getStreamArn(), REGION).isEmpty());
    }

    @Test
    void splitShard() {
        kinesisService.createStream("my-stream", 1, REGION);
        KinesisStream stream = kinesisService.describeStream("my-stream", REGION);
        String shardId = stream.getShards().getFirst().getShardId();

        kinesisService.splitShard("my-stream", shardId, "170141183460469231731687303715884105728", REGION);

        KinesisStream updated = kinesisService.describeStream("my-stream", REGION);
        assertEquals(3, updated.getShards().size());
        assertTrue(updated.getShards().getFirst().isClosed());
    }

    @Test
    void mergeShards() {
        kinesisService.createStream("my-stream", 2, REGION);
        KinesisStream stream = kinesisService.describeStream("my-stream", REGION);
        String shard0 = stream.getShards().get(0).getShardId();
        String shard1 = stream.getShards().get(1).getShardId();

        kinesisService.mergeShards("my-stream", shard0, shard1, REGION);

        KinesisStream updated = kinesisService.describeStream("my-stream", REGION);
        assertEquals(3, updated.getShards().size());
        assertTrue(updated.getShards().get(0).isClosed());
        assertTrue(updated.getShards().get(1).isClosed());
        assertFalse(updated.getShards().get(2).isClosed());
    }

    @Test
    void enableEnhancedMonitoring() {
        kinesisService.createStream("my-stream", 1, REGION);
        Set<String> before = kinesisService.enableEnhancedMonitoring(
                "my-stream", List.of("IncomingBytes", "OutgoingBytes"), REGION);

        assertTrue(before.isEmpty());
        KinesisStream stream = kinesisService.describeStream("my-stream", REGION);
        assertTrue(stream.getEnhancedMonitoringMetrics().contains("IncomingBytes"));
        assertTrue(stream.getEnhancedMonitoringMetrics().contains("OutgoingBytes"));
    }

    @Test
    void enableEnhancedMonitoringAll() {
        kinesisService.createStream("my-stream", 1, REGION);
        kinesisService.enableEnhancedMonitoring("my-stream", List.of("ALL"), REGION);

        KinesisStream stream = kinesisService.describeStream("my-stream", REGION);
        assertEquals(7, stream.getEnhancedMonitoringMetrics().size());
        assertTrue(stream.getEnhancedMonitoringMetrics().contains("IncomingBytes"));
        assertTrue(stream.getEnhancedMonitoringMetrics().contains("IteratorAgeMilliseconds"));
    }

    @Test
    void disableEnhancedMonitoring() {
        kinesisService.createStream("my-stream", 1, REGION);
        kinesisService.enableEnhancedMonitoring(
                "my-stream", List.of("IncomingBytes", "OutgoingBytes", "IncomingRecords"), REGION);
        Set<String> before = kinesisService.disableEnhancedMonitoring(
                "my-stream", List.of("OutgoingBytes"), REGION);

        assertEquals(3, before.size());
        KinesisStream stream = kinesisService.describeStream("my-stream", REGION);
        assertTrue(stream.getEnhancedMonitoringMetrics().contains("IncomingBytes"));
        assertTrue(stream.getEnhancedMonitoringMetrics().contains("IncomingRecords"));
        assertFalse(stream.getEnhancedMonitoringMetrics().contains("OutgoingBytes"));
    }

    @Test
    void disableEnhancedMonitoringAll() {
        kinesisService.createStream("my-stream", 1, REGION);
        kinesisService.enableEnhancedMonitoring(
                "my-stream", List.of("IncomingBytes", "OutgoingBytes"), REGION);
        kinesisService.disableEnhancedMonitoring("my-stream", List.of("ALL"), REGION);

        KinesisStream stream = kinesisService.describeStream("my-stream", REGION);
        assertTrue(stream.getEnhancedMonitoringMetrics().isEmpty());
    }

    @Test
    void enableEnhancedMonitoringInvalidMetric() {
        kinesisService.createStream("my-stream", 1, REGION);
        assertThrows(AwsException.class, () ->
                kinesisService.enableEnhancedMonitoring("my-stream", List.of("BogusMetric"), REGION));
    }

    @Test
    void enableEnhancedMonitoringEmptyListThrows() {
        kinesisService.createStream("my-stream", 1, REGION);
        assertThrows(AwsException.class, () ->
                kinesisService.enableEnhancedMonitoring("my-stream", List.of(), REGION));
    }

    @Test
    void enableEnhancedMonitoringAllWithInvalidThrows() {
        kinesisService.createStream("my-stream", 1, REGION);
        assertThrows(AwsException.class, () ->
                kinesisService.enableEnhancedMonitoring("my-stream", List.of("ALL", "BogusMetric"), REGION));
    }

    @Test
    void startAndStopEncryption() {
        kinesisService.createStream("my-stream", 1, REGION);
        kinesisService.startStreamEncryption("my-stream", "KMS", "my-key-id", REGION);

        KinesisStream encrypted = kinesisService.describeStream("my-stream", REGION);
        assertEquals("KMS", encrypted.getEncryptionType());
        assertEquals("my-key-id", encrypted.getKeyId());

        kinesisService.stopStreamEncryption("my-stream", REGION);

        KinesisStream unencrypted = kinesisService.describeStream("my-stream", REGION);
        assertEquals("NONE", unencrypted.getEncryptionType());
        assertNull(unencrypted.getKeyId());
    }

    @Test
    void legacyFivePartIteratorStillDecodes() {
        kinesisService.createStream("my-stream", 1, REGION);
        kinesisService.putRecord("my-stream", "a".getBytes(StandardCharsets.UTF_8), "pk", REGION);
        kinesisService.putRecord("my-stream", "b".getBytes(StandardCharsets.UTF_8), "pk", REGION);

        // Hand-crafted 5-part iterator in the pre-bump format.
        String raw = "my-stream|shardId-000000000000|TRIM_HORIZON||0";
        String legacyIterator = java.util.Base64.getEncoder()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> result = kinesisService.getRecords(legacyIterator, null, REGION);
        @SuppressWarnings("unchecked")
        List<KinesisRecord> records = (List<KinesisRecord>) result.get("Records");
        assertEquals(2, records.size(), "5-part iterator must still decode after encoding bump");
    }

    @Test
    void atTimestampIteratorRequiresTimestamp() {
        kinesisService.createStream("my-stream", 1, REGION);
        kinesisService.putRecord("my-stream", "a".getBytes(StandardCharsets.UTF_8), "pk", REGION);

        // getShardIterator encodes even with null timestamp (handler is the enforcement point for the API).
        // But getRecords must reject an AT_TIMESTAMP iterator that lacks the timestamp slot.
        String iterator = kinesisService.getShardIterator("my-stream", "shardId-000000000000",
                "AT_TIMESTAMP", null, null, REGION);

        AwsException ex = assertThrows(AwsException.class, () ->
                kinesisService.getRecords(iterator, null, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void atTimestampBoundaryIsInclusive() {
        kinesisService.createStream("my-stream", 1, REGION);
        kinesisService.putRecord("my-stream", "a".getBytes(StandardCharsets.UTF_8), "pk", REGION);
        // Read back the exact timestamp of record 0 to use as the boundary.
        String firstIter = kinesisService.getShardIterator("my-stream", "shardId-000000000000",
                "TRIM_HORIZON", null, null, REGION);
        @SuppressWarnings("unchecked")
        List<KinesisRecord> first = (List<KinesisRecord>) kinesisService.getRecords(firstIter, null, REGION)
                .get("Records");
        Instant arrivedAt = first.get(0).getApproximateArrivalTimestamp();

        String atIter = kinesisService.getShardIterator("my-stream", "shardId-000000000000",
                "AT_TIMESTAMP", null, arrivedAt.toEpochMilli(), REGION);
        @SuppressWarnings("unchecked")
        List<KinesisRecord> got = (List<KinesisRecord>) kinesisService.getRecords(atIter, null, REGION)
                .get("Records");
        assertEquals(1, got.size(), "AT_TIMESTAMP boundary is >= (inclusive)");
    }
}