package io.github.hectorvent.floci.services.kinesis;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.kinesis.model.KinesisShard;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class KinesisClosedShardRoutingServiceTest {

    private static final String REGION = "us-east-1";
    private static final String STREAM = "routing";
    private static final byte[] DATA = {1};

    private KinesisService service;

    @BeforeEach
    void setUp() {
        service = new KinesisService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"));
    }

    @Test
    void putRecordFollowsAnOpenSplitChild() {
        KinesisStream stream = service.createStream(STREAM, 1, REGION);
        service.putRecord(STREAM, DATA, "c", REGION);
        split(stream.getShards().getFirst());

        assertOpenDestinationAndUnchangedClosedRecords(stream, "c", stream.getShards().get(1));
    }

    @Test
    void putRecordTraversesClosedSplitChildren() {
        KinesisStream stream = splitTwoGenerations();

        // "b" hashes to 98, selecting the original parent at index zero modulo seven.
        assertOpenDestinationAndUnchangedClosedRecords(stream, "b", stream.getShards().get(3));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void putRecordPrefersAnOpenDirectChildOverADeeperDescendant(int closedChildIndex) {
        KinesisStream stream = service.createStream(STREAM, 1, REGION);
        split(stream.getShards().getFirst());
        split(stream.getShards().get(closedChildIndex));

        // "d" selects the root modulo five, with one direct child still open.
        assertOpenDestinationAndUnchangedClosedRecords(stream, "d", stream.getShards().get(3 - closedChildIndex));
    }

    @ParameterizedTest
    @ValueSource(strings = {"d", "e"})
    void putRecordTraversesEitherMergeParentThenAClosedChild(String partitionKey) {
        KinesisStream stream = service.createStream(STREAM, 2, REGION);
        service.mergeShards(STREAM, stream.getShards().get(0).getShardId(),
                stream.getShards().get(1).getShardId(), REGION);
        split(stream.getShards().get(2));

        // With five stored shards, these keys select each original merge parent.
        assertOpenDestinationAndUnchangedClosedRecords(stream, partitionKey, stream.getShards().get(3));
    }

    @Test
    void putRecordTraversesSplitThenMergeThenSplit() {
        KinesisStream stream = service.createStream(STREAM, 1, REGION);
        split(stream.getShards().getFirst());
        service.mergeShards(STREAM, stream.getShards().get(1).getShardId(),
                stream.getShards().get(2).getShardId(), REGION);
        split(stream.getShards().get(3));

        assertOpenDestinationAndUnchangedClosedRecords(stream, "f", stream.getShards().get(4));
    }

    @Test
    void putRecordUsesAReachableChildInsteadOfAnEarlierUnrelatedOpenShard() {
        KinesisStream stream = service.createStream(STREAM, 2, REGION);
        service.putRecordWithShardId(STREAM, DATA, "d", "0", REGION);
        split(stream.getShards().getFirst());

        // "d" selects the closed parent modulo four; shard one is unrelated and open.
        assertOpenDestinationAndUnchangedClosedRecords(stream, "d", stream.getShards().get(2));

        assertEquals(1, stream.getShards().get(0).recordCount());
        assertEquals(0, stream.getShards().get(1).recordCount());
        assertEquals(1, stream.getShards().get(2).recordCount());
    }

    @Test
    void putRecordRejectsAClosedShardWithoutDescendants() {
        KinesisStream stream = service.createStream(STREAM, 1, REGION);
        stream.getShards().getFirst().setClosed(true);

        assertNoDestination(stream, "b");
    }

    @Test
    void putRecordDoesNotFallBackToAnUnrelatedOpenShard() {
        KinesisStream stream = service.createStream(STREAM, 2, REGION);
        stream.getShards().getFirst().setClosed(true);

        assertNoDestination(stream, "b");
    }

    @Test
    void putRecordRejectsACycleWithoutHangingOrAppending() {
        KinesisStream stream = service.createStream(STREAM, 2, REGION);
        KinesisShard first = stream.getShards().get(0);
        KinesisShard second = stream.getShards().get(1);
        first.setClosed(true);
        second.setClosed(true);
        first.setParentShardId(second.getShardId());
        second.setAdjacentParentShardId(first.getShardId());

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertNoDestination(stream, "b"));
    }

    @Test
    void explicitHashKeysKeepRoutingToOpenRangeBoundariesAfterRepeatedSplits() {
        KinesisStream stream = splitTwoGenerations();
        List<KinesisShard> openShards = stream.getShards().stream().filter(shard -> !shard.isClosed()).toList();

        for (KinesisShard shard : openShards) {
            for (String hash : List.of(shard.getHashKeyRange().startingHashKey(),
                    shard.getHashKeyRange().endingHashKey())) {
                KinesisService.PutRecordResult result = service.putRecordWithShardId(STREAM, DATA, "b", hash, REGION);
                assertEquals(shard.getShardId(), result.shardId());
            }
        }
        assertTrue(stream.getShards().stream().filter(KinesisShard::isClosed)
                .allMatch(shard -> shard.recordCount() == 0));
    }

    @Test
    void explicitAccountProducerAlsoTraversesClosedDescendants() {
        KinesisStream stream = splitTwoGenerations();

        String sequence = service.putRecordForAccount("000000000000", STREAM, DATA, "b", REGION);

        assertTrue(stream.getShards().stream().filter(KinesisShard::isClosed)
                .allMatch(shard -> shard.recordCount() == 0));
        assertTrue(stream.getShards().stream().filter(shard -> !shard.isClosed())
                .flatMap(shard -> shard.getRecords().stream())
                .anyMatch(record -> sequence.equals(record.getSequenceNumber())));
    }

    private KinesisStream splitTwoGenerations() {
        KinesisStream stream = service.createStream(STREAM, 1, REGION);
        split(stream.getShards().getFirst());
        List<KinesisShard> children = stream.getShards().stream().filter(shard -> !shard.isClosed()).toList();
        children.forEach(this::split);
        return stream;
    }

    private void split(KinesisShard shard) {
        BigInteger start = new BigInteger(shard.getHashKeyRange().startingHashKey());
        BigInteger end = new BigInteger(shard.getHashKeyRange().endingHashKey());
        service.splitShard(STREAM, shard.getShardId(), start.add(end).add(BigInteger.ONE)
                .divide(BigInteger.TWO).toString(), REGION);
    }

    private void assertOpenDestinationAndUnchangedClosedRecords(KinesisStream stream, String partitionKey,
                                                               KinesisShard expectedDestination) {
        Map<String, Integer> closedCounts = stream.getShards().stream().filter(KinesisShard::isClosed)
                .collect(Collectors.toMap(KinesisShard::getShardId, KinesisShard::recordCount));
        KinesisService.PutRecordResult result = service.putRecordWithShardId(STREAM, DATA, partitionKey, REGION);
        KinesisShard destination = stream.getShards().stream()
                .filter(shard -> result.shardId().equals(shard.getShardId())).findFirst().orElseThrow();

        assertFalse(destination.isClosed());
        assertEquals(expectedDestination.getShardId(), result.shardId());
        assertEquals(result.sequenceNumber(), destination.getRecords().getLast().getSequenceNumber());
        stream.getShards().stream().filter(KinesisShard::isClosed)
                .forEach(shard -> assertEquals(closedCounts.get(shard.getShardId()).intValue(), shard.recordCount()));
    }

    private void assertNoDestination(KinesisStream stream, String partitionKey) {
        AwsException error = assertThrows(AwsException.class,
                () -> service.putRecordWithShardId(STREAM, DATA, partitionKey, REGION));
        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertTrue(stream.getShards().stream().allMatch(shard -> shard.recordCount() == 0));
    }
}
