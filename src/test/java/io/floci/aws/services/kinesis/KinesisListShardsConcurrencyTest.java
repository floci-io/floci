package io.floci.aws.services.kinesis;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.kinesis.model.KinesisShard;
import io.floci.aws.services.kinesis.model.KinesisStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for the ListShards {@code CopyOnWriteArrayList} sub-list hazard.
 *
 * <p>Exercises {@link KinesisJsonHandler#paginateShards(List, int)} directly. That is the exact
 * production step ListShards uses to take a {@code MaxResults} page, so the test needs no JAX-RS
 * runtime (absent from the sandbox launcher) and no timing window.
 */
class KinesisListShardsConcurrencyTest {

    private static final String REGION = "us-east-1";

    private KinesisService service;

    @BeforeEach
    void setUp() {
        service = new KinesisService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver(REGION, "123456789012"));
    }

    /**
     * A ListShards page must survive a concurrent split of the underlying stream.
     *
     * <p>ListShards takes {@code MaxResults < shard count}, so a sub-list page is taken; then a split
     * appends two children to the live backing list. Pre-fix ({@code shards.subList(...)} over the live
     * CopyOnWriteArrayList) reading the page throws {@link java.util.ConcurrentModificationException};
     * post-fix (page taken from a {@code List.copyOf} snapshot) the page reflects the pre-split shards
     * and reads cleanly.
     */
    @Test
    void listShardsPageSurvivesConcurrentSplit() {
        service.createStream("reshard-stream", 3, REGION);
        KinesisStream stream = service.describeStream("reshard-stream", REGION);

        // The page ListShards would build for MaxResults=2 against the live shard list.
        List<KinesisShard> page = KinesisJsonHandler.paginateShards(stream.getShards(), 2);

        // A concurrent split closes the parent and atomically appends two children, mutating the
        // live backing list the pre-fix sub-list page still points at.
        String firstShardId = stream.getShards().getFirst().getShardId();
        KinesisShard.HashKeyRange firstRange = stream.getShards().getFirst().getHashKeyRange();
        String splitPoint = new BigInteger(firstRange.startingHashKey())
                .add(new BigInteger(firstRange.endingHashKey())).add(BigInteger.ONE)
                .divide(BigInteger.TWO).toString();
        service.splitShard("reshard-stream", firstShardId,
                splitPoint, REGION);

        // Pre-fix: iterating/sizing the live sub-list here throws ConcurrentModificationException.
        assertDoesNotThrow(() -> {
            int seen = 0;
            for (KinesisShard shard : page) {
                shard.getShardId();
                seen++;
            }
            assertEquals(2, seen);
        });

        // A consistent page: exactly the first two shards of the pre-split snapshot, in order.
        assertEquals(2, page.size());
        assertEquals("shardId-000000000000", page.get(0).getShardId());
        assertEquals("shardId-000000000001", page.get(1).getShardId());

        // The split itself completed on the underlying stream: 3 original + 2 children.
        assertEquals(5, service.describeStream("reshard-stream", REGION).getShards().size());
    }
}
