package io.github.hectorvent.floci.services.kinesis;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.kinesis.model.KinesisRecord;
import io.github.hectorvent.floci.services.kinesis.model.KinesisShard;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class KinesisTerminalReadServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String PARENT = "shardId-000000000000";
    private final AccountAwareStorageBackend<KinesisStream> streams = AccountAwareStorageBackend.inMemory(ACCOUNT);
    private final KinesisService service = new KinesisService(streams, AccountAwareStorageBackend.inMemory(ACCOUNT),
            new RegionResolver(REGION, ACCOUNT));

    private String iterator(String type, String sequence, Long timestamp) {
        return service.getShardIterator("s", PARENT, type, sequence, timestamp, REGION);
    }

    private Map<String, Object> read(boolean account, String iterator, int limit) {
        return account ? service.getRecordsForAccount(ACCOUNT, iterator, limit, REGION)
                : service.getRecords(iterator, limit, REGION);
    }

    private String put(String value) {
        return service.putRecord("s", value.getBytes(StandardCharsets.UTF_8), "pk", REGION);
    }

    private void split() {
        service.splitShard("s", PARENT, "170141183460469231731687303715884105728", REGION);
    }

    @SuppressWarnings("unchecked")
    private List<KinesisService.ChildShard> children(Map<String, Object> page) {
        return (List<KinesisService.ChildShard>) page.get("ChildShards");
    }

    @Test
    void atSequenceNumberOnClosedShardIncludesTheRequestedRecord() {
        service.createStream("s", 1, REGION);
        put("first");
        String last = put("last");
        split();
        Map<String, Object> page = service.getRecords(iterator("AT_SEQUENCE_NUMBER", last, null), 10, REGION);
        assertEquals(1, ((List<?>) page.get("Records")).size());
        assertNull(page.get("NextShardIterator"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void finalNonemptyPageTerminatesAndOnlyTerminalPagesHaveChildren(boolean account) {
        service.createStream("s", 1, REGION);
        put("first");
        put("last");
        split();
        Map<String, Object> first = read(account, iterator("TRIM_HORIZON", null, null), 1);
        assertEquals(1, ((List<?>) first.get("Records")).size());
        assertNotNull(first.get("NextShardIterator"));
        assertFalse(first.containsKey("ChildShards"));
        Map<String, Object> last = read(account, (String) first.get("NextShardIterator"), 1);
        assertEquals(1, ((List<?>) last.get("Records")).size());
        assertNull(last.get("NextShardIterator"));
        assertEquals(0L, last.get("MillisBehindLatest"));
        assertEquals(List.of("shardId-000000000001", "shardId-000000000002"),
                children(last).stream().map(KinesisService.ChildShard::shardId).toList());
        assertEquals(List.of(PARENT), children(last).getFirst().parentShards());
        assertEquals(new KinesisShard.HashKeyRange("0", "170141183460469231731687303715884105727"),
                children(last).getFirst().hashKeyRange());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void caughtUpOpenShardRemainsReadableAndClosedPositionsTerminate(boolean account) {
        service.createStream("s", 1, REGION);
        String sequence = put("last");
        String latest = iterator("LATEST", null, null);
        Map<String, Object> open = read(account, latest, 10);
        assertTrue(((List<?>) open.get("Records")).isEmpty());
        assertNotNull(open.get("NextShardIterator"));
        assertFalse(open.containsKey("ChildShards"));
        split();
        for (String cursor : List.of(latest, iterator("AFTER_SEQUENCE_NUMBER", sequence, null),
                iterator("AT_TIMESTAMP", null, Instant.now().plusSeconds(60).toEpochMilli()))) {
            Map<String, Object> closed = read(account, cursor, 10);
            assertTrue(((List<?>) closed.get("Records")).isEmpty());
            assertNull(closed.get("NextShardIterator"));
            assertEquals(2, children(closed).size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyOrRetentionPrunedParentsTerminateWithDirectChildren(boolean account) {
        KinesisStream stream = service.createStream("s", 1, REGION);
        String cursor = iterator("TRIM_HORIZON", null, null);
        split();
        assertNull(read(account, cursor, 10).get("NextShardIterator"));
        stream.getShards().getFirst().addRecord(new KinesisRecord(new byte[]{1}, "pk", "1",
                Instant.now().minusSeconds(172800)));
        service.splitShard("s", "shardId-000000000001", "85070591730234615865843651857942052864", REGION);
        Map<String, Object> page = read(account, cursor, 10);
        assertTrue(((List<?>) page.get("Records")).isEmpty());
        assertNull(page.get("NextShardIterator"));
        assertEquals(List.of("shardId-000000000001", "shardId-000000000002"),
                children(page).stream().map(KinesisService.ChildShard::shardId).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothMergeParentsReportBothParentIds(boolean account) {
        service.createStream("s", 2, REGION);
        String adjacent = "shardId-000000000001";
        service.mergeShards("s", PARENT, adjacent, REGION);
        for (String parent : List.of(PARENT, adjacent)) {
            String cursor = service.getShardIterator("s", parent, "TRIM_HORIZON", null, null, REGION);
            Map<String, Object> page = read(account, cursor, 10);
            assertNull(page.get("NextShardIterator"));
            assertEquals(List.of(new KinesisService.ChildShard("shardId-000000000002", List.of(PARENT, adjacent),
                    new KinesisShard.HashKeyRange("0", "340282366920938463463374607431768211455"))), children(page));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void snapshotSerializesWithExplicitAccountAppendsAndResharding(boolean account) throws Exception {
        KinesisStream stream = service.createStream("s", 1, REGION);
        KinesisShard parent = spy(stream.getShards().getFirst());
        stream.getShards().set(0, parent);
        put("first");
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            KinesisShard.RecordsSnapshot snapshot = (KinesisShard.RecordsSnapshot) invocation.callRealMethod();
            captured.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return snapshot;
        }).when(parent).snapshotRecords();
        String cursor = iterator("TRIM_HORIZON", null, null);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Map<String, Object>> reading = pool.submit(() -> read(account, cursor, 10));
            try {
                assertTrue(captured.await(5, TimeUnit.SECONDS));
                CountDownLatch writing = new CountDownLatch(1);
                Future<?> mutation = pool.submit(() -> {
                    writing.countDown();
                    service.putRecordForAccount(ACCOUNT, "s", new byte[]{2}, "pk", REGION);
                    split();
                });
                assertTrue(writing.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> mutation.get(150, TimeUnit.MILLISECONDS));
                release.countDown();
                Map<String, Object> open = reading.get(5, TimeUnit.SECONDS);
                assertNotNull(open.get("NextShardIterator"));
                assertEquals(1, ((List<?>) open.get("Records")).size());
                mutation.get(5, TimeUnit.SECONDS);
                Map<String, Object> terminal = read(account, (String) open.get("NextShardIterator"), 10);
                assertEquals(1, ((List<?>) terminal.get("Records")).size());
                assertNull(terminal.get("NextShardIterator"));
                assertEquals(2, children(terminal).size());
            } finally {
                release.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void accountAndRegionResolveTheirOwnTerminalState(boolean closedOther) {
        service.createStream("s", 1, REGION);
        split();
        KinesisStream other = new KinesisStream("s", "arn:aws:kinesis:us-east-1:111111111111:stream/s");
        KinesisShard shard = new KinesisShard(PARENT, "0", "100", "0");
        shard.setClosed(closedOther);
        other.getShards().add(shard);
        streams.putForAccount("111111111111", REGION + "::s", other);
        String cursor = iterator("TRIM_HORIZON", null, null);
        Map<String, Object> page = service.getRecordsForAccount("111111111111", cursor, 10, REGION);
        assertEquals(closedOther, page.get("NextShardIterator") == null);
        assertNull(service.getRecords(cursor, 10, REGION).get("NextShardIterator"));
        service.createStream("s", 1, "eu-west-1");
        assertNotNull(service.getRecords(cursor, 10, "eu-west-1").get("NextShardIterator"));
    }
}
