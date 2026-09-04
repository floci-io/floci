package io.github.hectorvent.floci.services.kinesis;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.kinesis.model.KinesisConsumer;
import io.github.hectorvent.floci.services.kinesis.model.KinesisRecord;
import io.github.hectorvent.floci.services.kinesis.model.KinesisShard;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

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

        var records = (List<?>) result.get("Records");
        assertTrue(records.isEmpty());
        assertEquals(0L, ((Number) result.get("MillisBehindLatest")).longValue());
    }

    @Test
    void latestIteratorReturnsRecordsWrittenAfterItWasObtained() {
        kinesisService.createStream("my-stream", 1, REGION);
        kinesisService.putRecord("my-stream", "before".getBytes(StandardCharsets.UTF_8), "pk", REGION);

        String shardId = kinesisService.describeStream("my-stream", REGION).getShards().getFirst().getShardId();
        String iterator = kinesisService.getShardIterator("my-stream", shardId, "LATEST", null, REGION);

        kinesisService.putRecord("my-stream", "after".getBytes(StandardCharsets.UTF_8), "pk", REGION);

        Map<String, Object> result = kinesisService.getRecords(iterator, 10, REGION);
        var records = (List<?>) result.get("Records");
        assertEquals(1, records.size());
        assertEquals("after", new String(((KinesisRecord) records.getFirst()).getData(), StandardCharsets.UTF_8));
    }

    @Test
    void latestIteratorContinuationPicksUpSubsequentWrites() {
        kinesisService.createStream("my-stream", 1, REGION);
        String shardId = kinesisService.describeStream("my-stream", REGION).getShards().getFirst().getShardId();
        String iterator = kinesisService.getShardIterator("my-stream", shardId, "LATEST", null, REGION);

        // First poll before any write: empty, but the continuation iterator must not skip ahead.
        Map<String, Object> first = kinesisService.getRecords(iterator, 10, REGION);
        assertTrue(((List<?>) first.get("Records")).isEmpty());

        kinesisService.putRecord("my-stream", "tailed".getBytes(StandardCharsets.UTF_8), "pk", REGION);

        Map<String, Object> second = kinesisService.getRecords((String) first.get("NextShardIterator"), 10, REGION);
        var records = (List<?>) second.get("Records");
        assertEquals(1, records.size());
        assertEquals("tailed", new String(((KinesisRecord) records.getFirst()).getData(), StandardCharsets.UTF_8));
    }

    @Test
    void getShardIteratorUnknownShardThrowsForAllIteratorTypes() {
        kinesisService.createStream("my-stream", 1, REGION);
        for (String type : List.of("LATEST", "TRIM_HORIZON", "AT_SEQUENCE_NUMBER", "AFTER_SEQUENCE_NUMBER", "AT_TIMESTAMP")) {
            AwsException ex = assertThrows(AwsException.class, () ->
                    kinesisService.getShardIterator("my-stream", "shardId-999999999999", type, "1", 0L, REGION));
            assertEquals("ResourceNotFoundException", ex.getErrorCode(), type);
        }
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
        KinesisShard parent = updated.getShards().getFirst();
        assertTrue(parent.isClosed());

        // Both children must get distinct shard ids. nextShardId is size-based, so computing both
        // ids before adding either child assigns the same id to both (regression guard).
        List<String> shardIds = updated.getShards().stream().map(KinesisShard::getShardId).toList();
        assertEquals(shardIds.size(), new HashSet<>(shardIds).size(), "shard ids must be unique after split");

        // The single atomic addAll must publish a COMPLETE topology: both children present, each
        // pointing at the parent, together tiling the parent's whole hash range with no gap/overlap.
        List<KinesisShard> children = updated.getShards().stream()
                .filter(s -> shardId.equals(s.getParentShardId()))
                .toList();
        assertEquals(2, children.size(), "a split must publish exactly two children");
        KinesisShard child1 = children.get(0);
        KinesisShard child2 = children.get(1);
        assertEquals(parent.getHashKeyRange().startingHashKey(), child1.getHashKeyRange().startingHashKey());
        assertEquals("170141183460469231731687303715884105727", child1.getHashKeyRange().endingHashKey());
        assertEquals("170141183460469231731687303715884105728", child2.getHashKeyRange().startingHashKey());
        assertEquals(parent.getHashKeyRange().endingHashKey(), child2.getHashKeyRange().endingHashKey());
    }

    /**
     * A lock-free reader must never observe a half-published split: a closed parent with only one
     * of its two children. splitShard publishes both children in one atomic CopyOnWriteArrayList.addAll,
     * so every snapshot holds either zero or both split-children of any given parent.
     *
     * <p>Fail-without-fix: reverting the addAll to two sequential {@code add()} calls opens a window
     * (between the two adds, widened by the child2 shard construction) in which the list holds child1
     * but not child2; the hammering reader below catches it and records a violation.
     */
    @RepeatedTest(3)
    void splitShard_concurrentReaderNeverSeesHalfPublishedTopology() throws Exception {
        kinesisService.createStream("my-stream", 1, REGION);
        KinesisStream stream = kinesisService.describeStream("my-stream", REGION);

        AtomicReference<String> violation = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean(false);

        Thread reader = new Thread(() -> {
            while (!done.get()) {
                // A fresh snapshot of the live list, exactly as a lock-free DescribeStream/ListShards sees it.
                List<KinesisShard> snapshot = new ArrayList<>(stream.getShards());
                Map<String, Integer> splitChildCount = new java.util.HashMap<>();
                for (KinesisShard s : snapshot) {
                    // Split children carry a parent but no adjacent-parent (that marks a merge child).
                    if (s.getParentShardId() != null && s.getAdjacentParentShardId() == null) {
                        splitChildCount.merge(s.getParentShardId(), 1, Integer::sum);
                    }
                }
                for (Map.Entry<String, Integer> e : splitChildCount.entrySet()) {
                    if (e.getValue() != 2) {
                        violation.compareAndSet(null,
                                "parent " + e.getKey() + " observed with " + e.getValue()
                                        + " split-children (expected 0 or 2)");
                        return;
                    }
                }
            }
        });
        reader.start();

        // Split an open shard repeatedly; each split is one control-plane resharding with its own
        // publish window. Always split an open shard so open shards remain available.
        for (int i = 0; i < 3000 && violation.get() == null; i++) {
            // Split the widest open shard so ranges never narrow to the point splitting is impossible;
            // this keeps every one of the 3000 iterations a real split with its own publish window.
            KinesisShard open = stream.getShards().stream()
                    .filter(s -> !s.isClosed())
                    .max(java.util.Comparator.comparing(s ->
                            new java.math.BigInteger(s.getHashKeyRange().endingHashKey())
                                    .subtract(new java.math.BigInteger(s.getHashKeyRange().startingHashKey()))))
                    .orElseThrow();
            java.math.BigInteger start = new java.math.BigInteger(open.getHashKeyRange().startingHashKey());
            java.math.BigInteger end = new java.math.BigInteger(open.getHashKeyRange().endingHashKey());
            java.math.BigInteger mid = start.add(end).divide(java.math.BigInteger.TWO);
            if (mid.compareTo(start) <= 0 || mid.compareTo(end) >= 0) {
                break; // range too narrow to split further
            }
            kinesisService.splitShard("my-stream", open.getShardId(), mid.toString(), REGION);
        }
        done.set(true);
        reader.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(reader.isAlive(), "reader thread did not terminate");

        assertNull(violation.get(), () -> "half-published split observed: " + violation.get());
    }

    @Test
    void getShards_readerMidIteration_survivesConcurrentSplit() {
        // Two shards so the snapshot the reader opens still has an element left to read AFTER the
        // concurrent split; a single-shard snapshot would be exhausted by the first next() and the
        // second next() would throw NoSuchElementException even under COWAL.
        kinesisService.createStream("my-stream", 2, REGION);
        // describeStream returns the same KinesisStream instance the store holds, so these iterators
        // are over the exact backing list splitShard mutates.
        KinesisStream stream = kinesisService.describeStream("my-stream", REGION);
        String shardId = stream.getShards().getFirst().getShardId();

        // A second iterator opened before the split, used to assert the pre-split snapshot is fixed.
        Iterator<KinesisShard> preSplitSnapshot = stream.getShards().iterator();
        Iterator<KinesisShard> reader = stream.getShards().iterator();
        reader.next();

        kinesisService.splitShard("my-stream", shardId, "170141183460469231731687303715884105728", REGION);

        // A plain ArrayList iterator throws ConcurrentModificationException here; COWAL keeps reading
        // its fixed pre-split snapshot.
        assertDoesNotThrow(reader::next);

        // The snapshot opened before the split still reflects exactly the original two shards,
        // regardless of the two children the split appended afterwards.
        int snapshotSize = 0;
        while (preSplitSnapshot.hasNext()) {
            preSplitSnapshot.next();
            snapshotSize++;
        }
        assertEquals(2, snapshotSize);

        // A fresh read observes the completed reshard: 2 original shards + 2 children.
        assertEquals(4, kinesisService.describeStream("my-stream", REGION).getShards().size());
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

    // ─── Concurrency regression coverage (issue #571 sibling: Kinesis append race) ──────

    /** Runs {@code op} {@code opsPerThread} times on each of {@code threadCount} threads,
     *  all released together, and returns any throwables observed. */
    private List<Throwable> runConcurrently(int threadCount, int opsPerThread, Runnable op)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int t = 0; t < threadCount; t++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        for (int i = 0; i < opsPerThread; i++) {
                            op.run();
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(60, TimeUnit.SECONDS),
                    "concurrent producers did not complete within 60s");
            return errors;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "thread pool did not terminate");
        }
    }

    /** Drains every record of a single-shard stream, following NextShardIterator to the end. */
    private List<KinesisRecord> drainShard(String streamName, String type, String seq) {
        String shardId = kinesisService.describeStream(streamName, REGION)
                .getShards().getFirst().getShardId();
        String iterator = kinesisService.getShardIterator(streamName, shardId, type, seq, REGION);
        return drainFrom(iterator);
    }

    @SuppressWarnings("unchecked")
    private List<KinesisRecord> drainFrom(String iterator) {
        List<KinesisRecord> all = new ArrayList<>();
        while (true) {
            Map<String, Object> page = kinesisService.getRecords(iterator, 1000, REGION);
            List<KinesisRecord> records = (List<KinesisRecord>) page.get("Records");
            if (records.isEmpty()) {
                break;
            }
            all.addAll(records);
            iterator = (String) page.get("NextShardIterator");
        }
        return all;
    }

    private static void assertStrictlyIncreasing(List<KinesisRecord> records) {
        long previous = Long.MIN_VALUE;
        for (KinesisRecord r : records) {
            long current = Long.parseLong(r.getSequenceNumber());
            assertTrue(current > previous,
                    () -> "sequence numbers must be strictly increasing in shard order");
            previous = current;
        }
    }

    @RepeatedTest(5)
    void concurrent_putRecord_preserves_every_record_and_ordering() throws InterruptedException {
        kinesisService.createStream("stress", 1, REGION);
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        int threads = 32;
        int opsPerThread = 50;
        int expected = threads * opsPerThread; // 1600

        List<Throwable> errors = runConcurrently(threads, opsPerThread,
                () -> kinesisService.putRecord("stress", data, "pk", REGION));
        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);

        List<KinesisRecord> all = drainShard("stress", "TRIM_HORIZON", null);
        assertEquals(expected, all.size(),
                "every concurrently appended record must survive");

        Set<String> distinct = new HashSet<>();
        all.forEach(r -> distinct.add(r.getSequenceNumber()));
        assertEquals(expected, distinct.size(), "every record must carry a distinct sequence number");

        assertStrictlyIncreasing(all);

        // AFTER_SEQUENCE_NUMBER continuation: everything strictly after position k, in order.
        int k = 799;
        String kSeq = all.get(k).getSequenceNumber();
        List<KinesisRecord> after = drainShard("stress", "AFTER_SEQUENCE_NUMBER", kSeq);
        List<String> expectedTail = new ArrayList<>();
        for (int i = k + 1; i < all.size(); i++) {
            expectedTail.add(all.get(i).getSequenceNumber());
        }
        List<String> actualTail = new ArrayList<>();
        after.forEach(r -> actualTail.add(r.getSequenceNumber()));
        assertEquals(expectedTail, actualTail,
                "AFTER_SEQUENCE_NUMBER must return exactly the records after position k, in order");
    }

    @Test
    void putRecord_appendCriticalSectionIsMutuallyExclusive() throws Exception {
        kinesisService.createStream("mx", 1, REGION);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean firstIn = new AtomicBoolean(true);
        // Only the first producer to enter the critical section parks; it holds the stream lock
        // until released, so any second producer on the same stream must block behind it.
        kinesisService.putRecordAppendHook = () -> {
            if (firstIn.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        // T2 signals the instant before it calls putRecord and publishes its thread, so we can
        // prove it was actually scheduled and reached the critical section (BLOCKED on the stream
        // monitor), not merely never scheduled, which would let a timeout pass vacuously on a
        // loaded worker even if the lock had been removed.
        CountDownLatch t2Started = new CountDownLatch(1);
        AtomicReference<Thread> t2Thread = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> t1 = pool.submit(() ->
                    kinesisService.putRecord("mx", "1".getBytes(StandardCharsets.UTF_8), "pk", REGION));
            assertTrue(entered.await(2, TimeUnit.SECONDS), "T1 must enter the append critical section");

            Future<?> t2 = pool.submit(() -> {
                t2Thread.set(Thread.currentThread());
                t2Started.countDown();
                return kinesisService.putRecord("mx", "2".getBytes(StandardCharsets.UTF_8), "pk", REGION);
            });

            assertTrue(t2Started.await(2, TimeUnit.SECONDS),
                    "T2 must be scheduled and reach the putRecord call");

            // Deterministic barrier: T2 must actually reach and block on the stream monitor while
            // T1 holds it. Spin until its thread is BLOCKED (entering the synchronized section);
            // if the lock were gone T2 would run to completion instead and never park here.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean blockedOnMonitor = false;
            while (System.nanoTime() < deadline) {
                Thread th = t2Thread.get();
                if (th != null && th.getState() == Thread.State.BLOCKED) {
                    blockedOnMonitor = true;
                    break;
                }
                if (t2.isDone()) {
                    break;
                }
                Thread.sleep(5);
            }
            assertTrue(blockedOnMonitor,
                    "T2 must block entering the append critical section while T1 holds the stream lock");

            // Mutual exclusion: T2 cannot complete while T1 holds the lock.
            assertThrows(TimeoutException.class, () -> t2.get(500, TimeUnit.MILLISECONDS),
                    "a second producer must block while the first holds the stream lock");

            release.countDown();
            t1.get(2, TimeUnit.SECONDS);
            t2.get(2, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<KinesisRecord> all = drainShard("mx", "TRIM_HORIZON", null);
        assertEquals(2, all.size(), "both records must be persisted");
        long firstSeq = Long.parseLong(all.get(0).getSequenceNumber());
        long secondSeq = Long.parseLong(all.get(1).getSequenceNumber());
        assertTrue(firstSeq < secondSeq,
                "T1 completed its append first, so its lower sequence must sort first");
    }

    /**
     * A deleteStream that overlaps an in-flight append must be serialized behind it and must NOT
     * be able to resurrect the stream. P1 parks inside the append critical section holding the
     * stream monitor; the deleter can only run once P1 releases it, so the delete always wins and
     * the stream stays gone. Fails on the pre-fix code where deleteStream neither took the monitor
     * nor left it in place: the deleter would run immediately and P1's trailing store.put would
     * re-persist (resurrect) the deleted instance.
     */
    @Test
    void deleteStream_serializesBehindInFlightAppend_andDoesNotResurrect() throws Exception {
        kinesisService.createStream("res", 1, REGION);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean firstIn = new AtomicBoolean(true);
        kinesisService.putRecordAppendHook = () -> {
            if (firstIn.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> producer = pool.submit(() ->
                    kinesisService.putRecord("res", "p1".getBytes(StandardCharsets.UTF_8), "pk", REGION));
            assertTrue(entered.await(2, TimeUnit.SECONDS), "producer must enter the append critical section");

            // The deleter shares the stream monitor the parked producer holds, so it must block.
            Future<?> deleter = pool.submit(() -> {
                kinesisService.deleteStream("res", REGION);
                return null;
            });
            assertThrows(TimeoutException.class, () -> deleter.get(500, TimeUnit.MILLISECONDS),
                    "deleteStream must block behind the in-flight append, not delete concurrently");

            release.countDown();
            producer.get(2, TimeUnit.SECONDS);
            deleter.get(2, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // The delete ran after the producer's persist, so the stream must be gone, not resurrected
        // by a stale trailing store.put.
        assertTrue(kinesisService.listStreams(REGION).isEmpty(),
                "a deleted stream must not be resurrected by an overlapping append");
        assertThrows(AwsException.class, () -> kinesisService.describeStream("res", REGION),
                "the resurrected stream must not be describable");

        // And a freshly recreated stream starts empty: no stale record leaked across the lifecycle.
        kinesisService.createStream("res", 1, REGION);
        assertTrue(drainShard("res", "TRIM_HORIZON", null).isEmpty(),
                "a recreated stream must not carry records from the deleted instance");
    }

    /**
     * If a stream is deleted in the window after a producer resolved it but before it acquires the
     * append lock, the producer must fail cleanly (ResourceNotFoundException) rather than append to
     * and re-persist the stale instance. Deterministic via the pre-lock hook: the producer parks
     * WITHOUT holding the monitor, so the deleter wins the lock first. Fails on code lacking the
     * in-lock re-resolve: the producer would resurrect the stream and throw nothing.
     */
    @Test
    void putRecord_failsCleanly_whenStreamDeletedBeforeLockAcquired() throws Exception {
        kinesisService.createStream("gone", 1, REGION);

        CountDownLatch preEntered = new CountDownLatch(1);
        CountDownLatch preRelease = new CountDownLatch(1);
        AtomicBoolean firstIn = new AtomicBoolean(true);
        kinesisService.putRecordBeforeLockHook = () -> {
            if (firstIn.compareAndSet(true, false)) {
                preEntered.countDown();
                try {
                    preRelease.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<?> producer = pool.submit(() -> {
                try {
                    kinesisService.putRecord("gone", "p1".getBytes(StandardCharsets.UTF_8), "pk", REGION);
                } catch (Throwable t) {
                    thrown.set(t);
                }
                return null;
            });

            assertTrue(preEntered.await(2, TimeUnit.SECONDS),
                    "producer must reach the pre-lock window without holding the monitor");

            // Producer is parked without the monitor, so the deleter wins the lock and completes.
            kinesisService.deleteStream("gone", REGION);

            preRelease.countDown();
            producer.get(2, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Throwable t = thrown.get();
        assertNotNull(t, "the append must fail once the stream is deleted, not silently resurrect it");
        assertInstanceOf(AwsException.class, t, () -> "unexpected failure: " + t);
        assertEquals("ResourceNotFoundException", ((AwsException) t).getErrorCode(),
                "a delete-before-append must surface the same missing-stream error as any absent stream");

        assertTrue(kinesisService.listStreams(REGION).isEmpty(),
                "the deleted stream must not be resurrected by the failed append");
    }

    private static final String META_STREAM = "meta";

    /**
     * A store that parks the first {@code get} after {@link #arm()} until released.
     *
     * <p>Every metadata write resolves its stream through exactly one {@code store.get}, and does
     * so while holding the stream monitor, so parking there wedges the writer inside its critical
     * section without needing a production test seam.
     */
    private static final class ParkOnResolveStore implements StorageBackend<String, KinesisStream> {
        private final StorageBackend<String, KinesisStream> delegate = new InMemoryStorage<>();
        private final AtomicBoolean armed = new AtomicBoolean();
        final CountDownLatch parked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        void arm() {
            armed.set(true);
        }

        @Override
        public Optional<KinesisStream> get(String key) {
            if (armed.compareAndSet(true, false)) {
                parked.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.get(key);
        }

        @Override
        public void put(String key, KinesisStream value) {
            delegate.put(key, value);
        }

        @Override
        public void delete(String key) {
            delegate.delete(key);
        }

        @Override
        public List<KinesisStream> scan(Predicate<String> keyFilter) {
            return delegate.scan(keyFilter);
        }

        @Override
        public Set<String> keys() {
            return delegate.keys();
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        @Override
        public void load() {
            delegate.load();
        }

        @Override
        public void clear() {
            delegate.clear();
        }
    }

    private static Arguments metadataWrite(String label, Consumer<KinesisService> setUp,
                                           Consumer<KinesisService> apply) {
        return Arguments.of(label, setUp, apply);
    }

    /**
     * Every KinesisService action that reads a stored stream, mutates it and persists it again.
     * {@code setUp} establishes any precondition the action needs to reach its persist rather than
     * short-circuit as a no-op, and runs before the store is armed.
     */
    static Stream<Arguments> metadataWrites() {
        return Stream.of(
                metadataWrite("updateStreamMode", s -> {},
                        s -> s.updateStreamMode(META_STREAM, "ON_DEMAND", REGION)),
                metadataWrite("addTagsToStream", s -> {},
                        s -> s.addTagsToStream(META_STREAM, Map.of("env", "test"), REGION)),
                metadataWrite("removeTagsFromStream",
                        s -> s.addTagsToStream(META_STREAM, Map.of("env", "test"), REGION),
                        s -> s.removeTagsFromStream(META_STREAM, List.of("env"), REGION)),
                metadataWrite("startStreamEncryption", s -> {},
                        s -> s.startStreamEncryption(META_STREAM, "KMS", "alias/aws/kinesis", REGION)),
                metadataWrite("stopStreamEncryption",
                        s -> s.startStreamEncryption(META_STREAM, "KMS", "alias/aws/kinesis", REGION),
                        s -> s.stopStreamEncryption(META_STREAM, REGION)),
                metadataWrite("increaseStreamRetentionPeriod", s -> {},
                        s -> s.increaseStreamRetentionPeriod(META_STREAM, 48, REGION)),
                metadataWrite("decreaseStreamRetentionPeriod",
                        s -> s.increaseStreamRetentionPeriod(META_STREAM, 48, REGION),
                        s -> s.decreaseStreamRetentionPeriod(META_STREAM, 24, REGION)),
                metadataWrite("enableEnhancedMonitoring", s -> {},
                        s -> s.enableEnhancedMonitoring(META_STREAM, List.of("IncomingBytes"), REGION)),
                metadataWrite("disableEnhancedMonitoring",
                        s -> s.enableEnhancedMonitoring(META_STREAM, List.of("IncomingBytes"), REGION),
                        s -> s.disableEnhancedMonitoring(META_STREAM, List.of("IncomingBytes"), REGION)),
                metadataWrite("updateMaxRecordSize", s -> {},
                        s -> s.updateMaxRecordSize(META_STREAM, 2048, REGION)));
    }

    /** Spins until {@code thread} is BLOCKED entering a monitor, or {@code task} completes. */
    private static boolean awaitBlockedOnMonitor(AtomicReference<Thread> thread, Future<?> task)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Thread t = thread.get();
            if (t != null && t.getState() == Thread.State.BLOCKED) {
                return true;
            }
            if (task.isDone()) {
                return false;
            }
            Thread.sleep(5);
        }
        return false;
    }

    /**
     * A metadata write that overlaps a deleteStream must not resurrect the stream.
     *
     * <p>The writer is wedged inside its own stream resolve, which it performs after taking the
     * per-stream monitor. A concurrent deleteStream shares that monitor, so it must block: the
     * delete therefore lands strictly after the writer's persist and the stream stays gone.
     *
     * <p>This fails on either half of the fix being absent. If a metadata path did not take the
     * monitor, or resolved the stream before taking it, the deleter would not block here, and the
     * writer's trailing {@code store.put} would re-insert the instance the delete had just removed.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("metadataWrites")
    void metadataWrite_cannotResurrectAConcurrentlyDeletedStream(
            String label, Consumer<KinesisService> setUp, Consumer<KinesisService> apply) throws Exception {
        ParkOnResolveStore store = new ParkOnResolveStore();
        KinesisService service = new KinesisService(store, new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"));
        service.createStream(META_STREAM, 1, REGION);
        setUp.accept(service);
        store.arm();

        CountDownLatch deleterStarted = new CountDownLatch(1);
        AtomicReference<Thread> deleterThread = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> {
                apply.accept(service);
                return null;
            });
            assertTrue(store.parked.await(2, TimeUnit.SECONDS),
                    () -> label + " must park inside the stream resolve it performs under the monitor");

            // The deleter publishes its thread and signals immediately before the call, so a
            // never-scheduled deleter cannot make the blocking assertions below pass vacuously.
            Future<?> deleter = pool.submit(() -> {
                deleterThread.set(Thread.currentThread());
                deleterStarted.countDown();
                service.deleteStream(META_STREAM, REGION);
                return null;
            });
            assertTrue(deleterStarted.await(2, TimeUnit.SECONDS),
                    "the deleter must be scheduled and reach the deleteStream call");
            assertTrue(awaitBlockedOnMonitor(deleterThread, deleter),
                    () -> "deleteStream must block on the stream monitor while " + label
                            + " holds it, which is what proves the resolve happens inside the lock");
            assertThrows(TimeoutException.class, () -> deleter.get(500, TimeUnit.MILLISECONDS),
                    () -> "deleteStream must not complete while " + label + " holds the stream monitor");

            store.release.countDown();
            // Neither may fail: the writer resolved a live stream, and the delete then removes it.
            writer.get(2, TimeUnit.SECONDS);
            deleter.get(2, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertTrue(service.listStreams(REGION).isEmpty(),
                () -> label + " must not resurrect a concurrently deleted stream");
        AwsException notFound = assertThrows(AwsException.class,
                () -> service.describeStream(META_STREAM, REGION),
                () -> "a stream resurrected by " + label + " would still be describable");
        assertEquals("ResourceNotFoundException", notFound.getErrorCode());

        // A stream recreated under the same name carries none of the deleted instance's metadata,
        // so nothing leaked across the lifecycle.
        KinesisStream recreated = service.createStream(META_STREAM, 1, REGION);
        assertTrue(recreated.getTags().isEmpty(), "a recreated stream must start untagged");
        assertEquals("PROVISIONED", recreated.getStreamMode(),
                "a recreated stream must start in the default stream mode");
        assertEquals(24, recreated.getRetentionPeriodHours(),
                "a recreated stream must start at the default retention period");
    }
}
