package io.floci.aws.services.kinesis;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.kinesis.model.KinesisShard;
import io.floci.aws.services.kinesis.model.KinesisStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
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
    void putRecordSelectsAnOpenSplitChildByHash() {
        KinesisStream stream = service.createStream(STREAM, 1, REGION);
        service.putRecord(STREAM, DATA, "c", REGION);
        split(stream.getShards().getFirst());

        assertOpenDestinationAndUnchangedClosedRecords(stream, "c", stream.getShards().get(1));
    }

    @Test
    void splitMigratesLegacyRangesBeforeDerivingChildRanges() {
        KinesisStream stream = service.createStream(STREAM, 2, REGION);
        BigInteger half = BigInteger.ONE.shiftLeft(127);
        BigInteger quarter = BigInteger.ONE.shiftLeft(126);
        String max = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE).toString();
        stream.getShards().forEach(shard ->
                shard.setHashKeyRange(new KinesisShard.HashKeyRange("0", max)));

        service.splitShard(STREAM, stream.getShards().getFirst().getShardId(), quarter.toString(), REGION);

        assertEquals(new KinesisShard.HashKeyRange("0", half.subtract(BigInteger.ONE).toString()),
                stream.getShards().get(0).getHashKeyRange());
        assertEquals(new KinesisShard.HashKeyRange(half.toString(), max),
                stream.getShards().get(1).getHashKeyRange());
        assertEquals(new KinesisShard.HashKeyRange("0", quarter.subtract(BigInteger.ONE).toString()),
                stream.getShards().get(2).getHashKeyRange());
        assertEquals(new KinesisShard.HashKeyRange(quarter.toString(), half.subtract(BigInteger.ONE).toString()),
                stream.getShards().get(3).getHashKeyRange());
        assertOpenDestinationAndUnchangedClosedRecords(stream, "c", stream.getShards().get(3));
    }

    @Test
    void invalidLegacySplitDoesNotChangeShardRanges() {
        KinesisStream stream = service.createStream(STREAM, 2, REGION);
        String max = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE).toString();
        String outsideNormalizedParent = BigInteger.ONE.shiftLeft(127)
                .add(BigInteger.ONE.shiftLeft(126)).toString();
        stream.getShards().forEach(shard ->
                shard.setHashKeyRange(new KinesisShard.HashKeyRange("0", max)));

        AwsException error = assertThrows(AwsException.class,
                () -> service.splitShard(STREAM, stream.getShards().getFirst().getShardId(),
                        outsideNormalizedParent, REGION));

        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals(2, stream.getShards().size());
        assertTrue(stream.getShards().stream().allMatch(shard ->
                new KinesisShard.HashKeyRange("0", max).equals(shard.getHashKeyRange())));
    }

    @Test
    void mergeMigratesLegacyRangesBeforeDerivingChildRange() {
        KinesisStream stream = service.createStream(STREAM, 2, REGION);
        BigInteger half = BigInteger.ONE.shiftLeft(127);
        String max = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE).toString();
        stream.getShards().forEach(shard ->
                shard.setHashKeyRange(new KinesisShard.HashKeyRange("0", max)));

        service.mergeShards(STREAM, stream.getShards().get(0).getShardId(),
                stream.getShards().get(1).getShardId(), REGION);

        assertEquals(new KinesisShard.HashKeyRange("0", half.subtract(BigInteger.ONE).toString()),
                stream.getShards().get(0).getHashKeyRange());
        assertEquals(new KinesisShard.HashKeyRange(half.toString(), max),
                stream.getShards().get(1).getHashKeyRange());
        assertEquals(new KinesisShard.HashKeyRange("0", max), stream.getShards().get(2).getHashKeyRange());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nonAdjacentMergeDoesNotChangeShardTopology(boolean legacyRanges) {
        KinesisStream stream = service.createStream(STREAM, 3, REGION);
        if (legacyRanges) {
            String max = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE).toString();
            stream.getShards().forEach(shard ->
                    shard.setHashKeyRange(new KinesisShard.HashKeyRange("0", max)));
        }
        List<KinesisShard.HashKeyRange> before = stream.getShards().stream()
                .map(KinesisShard::getHashKeyRange).toList();

        AwsException error = assertThrows(AwsException.class,
                () -> service.mergeShards(STREAM, stream.getShards().get(0).getShardId(),
                        stream.getShards().get(2).getShardId(), REGION));

        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals(3, stream.getShards().size());
        assertTrue(stream.getShards().stream().noneMatch(KinesisShard::isClosed));
        assertEquals(before, stream.getShards().stream().map(KinesisShard::getHashKeyRange).toList());
        service.putRecordWithShardId(STREAM, DATA, "c", REGION);
    }

    @Test
    void updateShardCountMigratesLegacyRangesBeforeChoosingSplits() {
        KinesisStream stream = service.createStream(STREAM, 2, REGION);
        String max = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE).toString();
        stream.getShards().forEach(shard ->
                shard.setHashKeyRange(new KinesisShard.HashKeyRange("0", max)));

        service.updateShardCount(STREAM, 4, "UNIFORM_SCALING", REGION);

        List<KinesisShard> open = stream.getShards().stream().filter(shard -> !shard.isClosed())
                .sorted((left, right) -> new BigInteger(left.getHashKeyRange().startingHashKey())
                        .compareTo(new BigInteger(right.getHashKeyRange().startingHashKey())))
                .toList();
        assertEquals(4, open.size());
        assertEquals("0", open.getFirst().getHashKeyRange().startingHashKey());
        assertEquals(max, open.getLast().getHashKeyRange().endingHashKey());
        for (int i = 1; i < open.size(); i++) {
            assertEquals(new BigInteger(open.get(i - 1).getHashKeyRange().endingHashKey()).add(BigInteger.ONE),
                    new BigInteger(open.get(i).getHashKeyRange().startingHashKey()));
        }
    }

    @Test
    void updateShardCountLimitDoesNotNormalizeLegacyRanges() {
        KinesisStream stream = service.createStream(STREAM, 2, REGION);
        String max = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE).toString();
        stream.getShards().forEach(shard ->
                shard.setHashKeyRange(new KinesisShard.HashKeyRange("0", max)));
        List<Instant> history = Collections.nCopies(10, Instant.now());
        stream.setShardCountUpdateTimestamps(history);

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateShardCount(STREAM, 4, "UNIFORM_SCALING", REGION));

        assertEquals("LimitExceededException", error.getErrorCode());
        assertTrue(stream.getShards().stream().allMatch(shard ->
                new KinesisShard.HashKeyRange("0", max).equals(shard.getHashKeyRange())));
        assertEquals(history, stream.getShardCountUpdateTimestamps());
    }

    @Test
    void alreadyReshardedLegacyOverlapDoesNotRewriteChildRanges() {
        KinesisStream stream = service.createStream(STREAM, 2, REGION);
        String max = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE).toString();
        String quarter = BigInteger.ONE.shiftLeft(126).toString();
        service.splitShard(STREAM, stream.getShards().getFirst().getShardId(), quarter, REGION);
        stream.getShards().get(0).setHashKeyRange(new KinesisShard.HashKeyRange("0", max));
        stream.getShards().get(1).setHashKeyRange(new KinesisShard.HashKeyRange("0", max));
        stream.getShards().get(3).setHashKeyRange(new KinesisShard.HashKeyRange(quarter, max));
        List<KinesisShard.HashKeyRange> before = stream.getShards().stream()
                .map(KinesisShard::getHashKeyRange).toList();

        AwsException error = assertThrows(AwsException.class,
                () -> service.putRecordWithShardId(STREAM, DATA, "c", REGION));

        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals(before, stream.getShards().stream().map(KinesisShard::getHashKeyRange).toList());
        assertTrue(stream.getShards().stream().allMatch(shard -> shard.recordCount() == 0));

        AwsException scalingError = assertThrows(AwsException.class,
                () -> service.updateShardCount(STREAM, 4, "UNIFORM_SCALING", REGION));
        assertEquals("InvalidArgumentException", scalingError.getErrorCode());
        assertTrue(stream.getShardCountUpdateTimestamps().isEmpty());
    }

    @Test
    void partiallyOverlappingRootRangesAreNotRepartitioned() {
        KinesisStream stream = service.createStream(STREAM, 2, REGION);
        String max = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE).toString();
        stream.getShards().get(1).setHashKeyRange(new KinesisShard.HashKeyRange("0", max));
        List<KinesisShard.HashKeyRange> before = stream.getShards().stream()
                .map(KinesisShard::getHashKeyRange).toList();

        AwsException error = assertThrows(AwsException.class,
                () -> service.putRecordWithShardId(STREAM, DATA, "c", REGION));

        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals(before, stream.getShards().stream().map(KinesisShard::getHashKeyRange).toList());
        assertTrue(stream.getShards().stream().allMatch(shard -> shard.recordCount() == 0));
    }

    @Test
    void putRecordRoutesToOpenRangeAfterRepeatedSplits() {
        KinesisStream stream = splitTwoGenerations();

        assertOpenDestinationAndUnchangedClosedRecords(stream, "b", stream.getShards().get(5));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void putRecordFindsHashRangeAcrossUnevenSplitDepth(int closedChildIndex) {
        KinesisStream stream = service.createStream(STREAM, 1, REGION);
        split(stream.getShards().getFirst());
        split(stream.getShards().get(closedChildIndex));

        KinesisShard expected = stream.getShards().get(closedChildIndex == 1 ? 2 : 3);
        assertOpenDestinationAndUnchangedClosedRecords(stream, "d", expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "b"})
    void putRecordRoutesAcrossMergedThenSplitRange(String partitionKey) {
        KinesisStream stream = service.createStream(STREAM, 2, REGION);
        service.mergeShards(STREAM, stream.getShards().get(0).getShardId(),
                stream.getShards().get(1).getShardId(), REGION);
        split(stream.getShards().get(2));

        KinesisShard expected = stream.getShards().get("a".equals(partitionKey) ? 3 : 4);
        assertOpenDestinationAndUnchangedClosedRecords(stream, partitionKey, expected);
    }

    @Test
    void cachedRoutingRebuildsAfterMerge() {
        KinesisStream stream = service.createStream(STREAM, 2, REGION);
        service.putRecordWithShardId(STREAM, DATA, "a", "0", REGION);
        service.mergeShards(STREAM, stream.getShards().get(0).getShardId(),
                stream.getShards().get(1).getShardId(), REGION);

        KinesisService.PutRecordResult result = service.putRecordWithShardId(STREAM, DATA, "b", "0", REGION);

        assertEquals(stream.getShards().get(2).getShardId(), result.shardId());
        assertEquals(1, stream.getShards().get(0).recordCount());
        assertEquals(1, stream.getShards().get(2).recordCount());
    }

    @Test
    void putRecordRoutesAcrossSplitThenMergeThenSplit() {
        KinesisStream stream = service.createStream(STREAM, 1, REGION);
        split(stream.getShards().getFirst());
        service.mergeShards(STREAM, stream.getShards().get(1).getShardId(),
                stream.getShards().get(2).getShardId(), REGION);
        split(stream.getShards().get(3));

        assertOpenDestinationAndUnchangedClosedRecords(stream, "f", stream.getShards().get(5));
    }

    @Test
    void putRecordSelectsSplitChildInsteadOfUnrelatedOpenShard() {
        KinesisStream stream = service.createStream(STREAM, 2, REGION);
        service.putRecordWithShardId(STREAM, DATA, "d", "0", REGION);
        split(stream.getShards().getFirst());

        assertOpenDestinationAndUnchangedClosedRecords(stream, "a", stream.getShards().get(2));

        assertEquals(1, stream.getShards().get(0).recordCount());
        assertEquals(0, stream.getShards().get(1).recordCount());
        assertEquals(1, stream.getShards().get(2).recordCount());
    }

    @Test
    void putRecordRejectsWhenNoOpenShardCoversHash() {
        KinesisStream stream = service.createStream(STREAM, 1, REGION);
        stream.getShards().getFirst().setClosed(true);

        assertNoDestination(stream, "a");
    }

    @Test
    void putRecordDoesNotFallBackToAnUnrelatedOpenShard() {
        KinesisStream stream = service.createStream(STREAM, 2, REGION);
        stream.getShards().getFirst().setClosed(true);

        assertNoDestination(stream, "a");
    }

    @Test
    void putRecordRejectsNoOpenShardDespiteLineageCycle() {
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
    void explicitAccountProducerAlsoRoutesToAnOpenHashRange() {
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
