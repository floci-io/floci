package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.dynamodb.DynamoDbStreamService.Settings;
import io.floci.aws.services.dynamodb.DynamoDbStreamService.ShardDescription;
import io.floci.aws.services.dynamodb.backend.DynamoDbApiStreamReader;
import io.floci.aws.services.dynamodb.backend.DynamoDbOperations;
import io.floci.aws.services.dynamodb.backend.DynamoDbStreamReader;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.DynamoDbStreamRecord;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Iterator expiry, time-based retention, several shards and shard lineage in the native stream engine. */
class DynamoDbStreamLifecycleTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final Settings EXPIRY = new Settings(1, null, true, false);
    private static final Settings RETENTION = new Settings(1, null, false, true);

    private final ObjectMapper mapper = new ObjectMapper();
    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-17T12:00:00Z"));
    private final TableDefinition table = new TableDefinition("Orders",
            List.of(new KeySchemaElement("pk", "HASH")), List.of(new AttributeDefinition("pk", "S")), REGION, ACCOUNT);

    @Test
    void byDefaultIteratorsNeverExpireRecordsNeverAgeOutAndAnySequenceNumberIsAccepted() {
        DynamoDbStreamService streams = streams(Settings.DEFAULTS);
        String streamArn = enable(streams);
        put(streams, "a");
        String iterator = streams.getShardIterator(streamArn, DynamoDbStreamService.SHARD_ID, "TRIM_HORIZON", null);

        clock.advance(Duration.ofHours(25));

        assertEquals(1, streams.getRecords(iterator, 10).records().size());
        assertNotNull(streams.getShardIterator(streamArn, DynamoDbStreamService.SHARD_ID, "AT_SEQUENCE_NUMBER",
                "000000000000000000000"));
        assertEquals(List.of(new ShardDescription(DynamoDbStreamService.SHARD_ID, null, "000000000000000000001", null)),
                streams.describeShards(streamArn, null, null).shards());
    }

    @Test
    void anIteratorExpiresFifteenMinutesAfterItIsReturned() {
        DynamoDbStreamService streams = streams(EXPIRY);
        String streamArn = enable(streams);
        put(streams, "a");
        String iterator = streams.getShardIterator(streamArn, DynamoDbStreamService.SHARD_ID, "TRIM_HORIZON", null);

        clock.advance(Duration.ofMinutes(15));
        DynamoDbStreamService.GetRecordsResult read = streams.getRecords(iterator, 10);
        assertEquals(1, read.records().size());

        clock.advance(Duration.ofSeconds(1));
        AwsException expired = assertThrows(AwsException.class, () -> streams.getRecords(iterator, 10));
        assertEquals("ExpiredIteratorException", expired.getErrorCode());
        assertEquals("The provided iterator exceeds the maximum age allowed.", expired.getMessage());
        // The iterator a read returns gets 15 minutes of its own.
        assertEquals(0, streams.getRecords(read.nextShardIterator(), 10).records().size());
    }

    @Test
    void recordsAreTrimmedTwentyFourHoursAfterTheyAreWritten() {
        DynamoDbStreamService streams = streams(RETENTION);
        String streamArn = enable(streams);
        put(streams, "first");
        String openedEarly = streams.getShardIterator(streamArn, DynamoDbStreamService.SHARD_ID, "TRIM_HORIZON", null);
        clock.advance(Duration.ofHours(12));
        put(streams, "second");

        clock.advance(Duration.ofHours(12).plusSeconds(1));

        AwsException trimmed = assertThrows(AwsException.class, () -> streams.getRecords(openedEarly, 10));
        assertEquals("TrimmedDataAccessException", trimmed.getErrorCode());
        assertEquals("The data you are trying to access has been trimmed.", trimmed.getMessage());
        String horizon = streams.getShardIterator(streamArn, DynamoDbStreamService.SHARD_ID, "TRIM_HORIZON", null);
        assertEquals(List.of("second"), keys(streams.getRecords(horizon, 10).records()));
    }

    @Test
    void anIteratorOpenedOnAShardThatTrimmingEmptiedReadsWhatComesNext() {
        DynamoDbStreamService streams = streams(RETENTION);
        String streamArn = enable(streams);
        put(streams, "gone");
        clock.advance(Duration.ofHours(25));

        String horizon = streams.getShardIterator(streamArn, DynamoDbStreamService.SHARD_ID, "TRIM_HORIZON", null);
        DynamoDbStreamService.GetRecordsResult empty = streams.getRecords(horizon, 10);
        put(streams, "next");

        assertEquals(List.of(), empty.records());
        assertEquals(List.of("next"), keys(streams.getRecords(empty.nextShardIterator(), 10).records()));
    }

    @Test
    void aTrimmedSequenceNumberIsRefusedWhenTheIteratorIsRequested() {
        DynamoDbStreamService streams = streams(RETENTION);
        String streamArn = enable(streams);
        put(streams, "first");
        put(streams, "second");

        clock.advance(Duration.ofHours(24).plusSeconds(1));
        put(streams, "third");

        AwsException trimmed = assertThrows(AwsException.class, () -> streams.getShardIterator(streamArn,
                DynamoDbStreamService.SHARD_ID, "AFTER_SEQUENCE_NUMBER", "000000000000000000001"));
        assertEquals("TrimmedDataAccessException", trimmed.getErrorCode());
        assertNotNull(streams.getShardIterator(streamArn, DynamoDbStreamService.SHARD_ID, "AT_SEQUENCE_NUMBER",
                "000000000000000000003"));
    }

    @Test
    void aSequenceNumberTheShardNeverHeldIsRefusedWhenRetentionIsOn() {
        DynamoDbStreamService streams = streams(new Settings(2, null, false, true));
        String streamArn = enable(streams);
        Map<String, List<String>> sequencesByShard = new HashMap<>();
        for (String key : List.of("a", "b", "c", "d", "e", "f")) {
            put(streams, key);
        }
        for (ShardDescription shard : streams.describeShards(streamArn, null, null).shards()) {
            sequencesByShard.put(shard.shardId(), readAll(streams, streamArn, shard.shardId()).stream()
                    .map(DynamoDbStreamRecord::getSequenceNumber).toList());
        }
        // Both shards start at the stream's first sequence number, but only one holds that record.
        String holder = sequencesByShard.entrySet().stream()
                .filter(entry -> entry.getValue().contains("000000000000000000001")).findFirst().orElseThrow().getKey();
        String other = sequencesByShard.keySet().stream().filter(shard -> !shard.equals(holder)).findFirst()
                .orElseThrow();
        assertTrue(!sequencesByShard.get(other).isEmpty(), "both shards hold records: " + sequencesByShard);

        for (String foreign : List.of("000000000000000000000", "000000000000000000099", "000000000000000000001",
                sequencesByShard.get(holder).getLast())) {
            AwsException invalid = assertThrows(AwsException.class,
                    () -> streams.getShardIterator(streamArn, other, "AT_SEQUENCE_NUMBER", foreign), foreign);
            assertEquals("ValidationException", invalid.getErrorCode());
            assertEquals("Invalid SequenceNumber for the shard", invalid.getMessage());
        }
        for (String own : List.of(sequencesByShard.get(other).getFirst(), sequencesByShard.get(other).getLast())) {
            String at = streams.getShardIterator(streamArn, other, "AT_SEQUENCE_NUMBER", own);
            assertEquals(own, streams.getRecords(at, 1).records().getFirst().getSequenceNumber());
        }
        String startOfHolder = streams.getShardIterator(streamArn, holder, "AT_SEQUENCE_NUMBER",
                "000000000000000000001");
        assertEquals("000000000000000000001",
                streams.getRecords(startOfHolder, 1).records().getFirst().getSequenceNumber());
    }

    @Test
    void aLimitBelowOneIsRefusedAsAwsDoes() {
        DynamoDbStreamService streams = streams(Settings.DEFAULTS);
        String streamArn = enable(streams);
        String iterator = streams.getShardIterator(streamArn, DynamoDbStreamService.SHARD_ID, "TRIM_HORIZON", null);
        NativeDynamoDbStreamsJsonHandler handler = handler(streams);

        for (int limit : new int[]{0, -1}) {
            for (Runnable call : List.<Runnable>of(
                    () -> streams.describeShards(streamArn, null, limit),
                    () -> streams.getRecords(iterator, limit),
                    () -> handler.handle("DescribeStream",
                            mapper.createObjectNode().put("StreamArn", streamArn).put("Limit", limit), REGION))) {
                AwsException invalid = assertThrows(AwsException.class, call::run);
                assertEquals("ValidationException", invalid.getErrorCode());
                assertEquals("1 validation error detected: Value at 'limit' failed to satisfy constraint: "
                        + "Member must have value greater than or equal to 1", invalid.getMessage());
            }
        }
    }

    @Test
    void aSequenceIteratorWithoutASequenceNumberIsRefusedAsAwsDoes() {
        DynamoDbStreamService streams = streams(Settings.DEFAULTS);
        String streamArn = enable(streams);

        AwsException missing = assertThrows(AwsException.class, () -> streams.getShardIterator(streamArn,
                DynamoDbStreamService.SHARD_ID, "AT_SEQUENCE_NUMBER", null));

        assertEquals("ValidationException", missing.getErrorCode());
        assertEquals("One or more parameter values were invalid: SequenceNumber cannot be null", missing.getMessage());
    }

    @Test
    void anItemsChangesStayInOrderOnTheShardItsPartitionKeyPicks() {
        DynamoDbStreamService streams = streams(new Settings(4, null, false, false));
        String streamArn = enable(streams);
        for (int round = 0; round < 3; round++) {
            for (int key = 0; key < 12; key++) {
                put(streams, "item-" + key);
            }
        }

        List<ShardDescription> shards = streams.describeShards(streamArn, null, null).shards();
        assertEquals(List.of("shardId-0000000001-00000000001", "shardId-0000000001-00000000002",
                "shardId-0000000001-00000000003", "shardId-0000000001-00000000004"),
                shards.stream().map(ShardDescription::shardId).toList());
        Map<String, String> shardOfKey = new HashMap<>();
        Set<String> used = new HashSet<>();
        for (ShardDescription shard : shards) {
            List<DynamoDbStreamRecord> records = readAll(streams, streamArn, shard.shardId());
            for (int i = 1; i < records.size(); i++) {
                assertTrue(records.get(i - 1).getSequenceNumber().compareTo(records.get(i).getSequenceNumber()) < 0);
            }
            for (String key : keys(records)) {
                String earlier = shardOfKey.put(key, shard.shardId());
                assertTrue(earlier == null || earlier.equals(shard.shardId()), key + " is on two shards");
                used.add(shard.shardId());
            }
        }
        assertEquals(12, shardOfKey.size());
        assertTrue(used.size() > 1, "the items spread over the shards: " + shardOfKey);
    }

    @Test
    void aShardTheStreamDoesNotHaveIsNotFound() {
        DynamoDbStreamService streams = streams(Settings.DEFAULTS);
        String streamArn = enable(streams);

        AwsException missing = assertThrows(AwsException.class, () -> streams.getShardIterator(streamArn,
                "shardId-0000000009-00000000001", "TRIM_HORIZON", null));

        assertEquals("ResourceNotFoundException", missing.getErrorCode());
        assertEquals("Requested resource not found: Shard does not exist", missing.getMessage());
    }

    @Test
    void anOpenShardRollsOverToAChildThatNamesItAsItsParent() {
        DynamoDbStreamService streams = streams(new Settings(1, Duration.ofHours(1), false, false));
        String streamArn = enable(streams);
        put(streams, "before");
        String parentIterator = streams.getShardIterator(streamArn, DynamoDbStreamService.SHARD_ID,
                "TRIM_HORIZON", null);

        clock.advance(Duration.ofHours(1));
        put(streams, "after");

        String child = "shardId-0000000002-00000000001";
        assertEquals(List.of(
                new ShardDescription(DynamoDbStreamService.SHARD_ID, null, "000000000000000000001",
                        "000000000000000000001"),
                new ShardDescription(child, DynamoDbStreamService.SHARD_ID, "000000000000000000002", null)),
                streams.describeShards(streamArn, null, null).shards());
        DynamoDbStreamService.GetRecordsResult parentPage = streams.getRecords(parentIterator, 10);
        assertEquals(List.of("before"), keys(parentPage.records()));
        DynamoDbStreamService.GetRecordsResult end = streams.getRecords(parentPage.nextShardIterator(), 10);
        assertEquals(List.of(), end.records());
        assertNull(end.nextShardIterator(), "a closed shard read to its end has no next iterator");
        assertEquals(List.of("after"), keys(readAll(streams, streamArn, child)));
    }

    @Test
    void disablingAStreamClosesItsShardsWhichStayReadable() {
        DynamoDbStreamService streams = streams(Settings.DEFAULTS);
        String streamArn = enable(streams);
        put(streams, "a");
        put(streams, "b");

        streams.disableStream(table.getTableArn());

        assertEquals(List.of(new ShardDescription(DynamoDbStreamService.SHARD_ID, null, "000000000000000000001",
                "000000000000000000002")), streams.describeShards(streamArn, null, null).shards());
        assertEquals(List.of("a", "b"), keys(readAll(streams, streamArn, DynamoDbStreamService.SHARD_ID)));
    }

    @Test
    void aClosedShardIsDroppedTwentyFourHoursAfterItClosesWhenRetentionIsOn() {
        DynamoDbStreamService streams = streams(new Settings(1, Duration.ofHours(1), false, true));
        String streamArn = enable(streams);
        put(streams, "a");
        clock.advance(Duration.ofHours(1));
        put(streams, "b");

        clock.advance(Duration.ofMinutes(30));
        assertEquals(2, streams.describeShards(streamArn, null, null).shards().size());
        clock.advance(Duration.ofHours(24));

        List<ShardDescription> shards = streams.describeShards(streamArn, null, null).shards();
        assertEquals(DynamoDbStreamService.SHARD_ID, shards.getFirst().parentShardId(),
                "the child keeps naming the parent it replaced");
        assertTrue(shards.stream().noneMatch(shard -> DynamoDbStreamService.SHARD_ID.equals(shard.shardId())));
    }

    @Test
    void anIdleStreamThatRollsOverKeepsTwoGenerationsOfShards() {
        DynamoDbStreamService streams = streams(new Settings(1, Duration.ofHours(1), false, false));
        String streamArn = enable(streams);

        for (int hour = 1; hour <= 5; hour++) {
            clock.advance(Duration.ofHours(1));
            streams.describeShards(streamArn, null, null);
        }

        assertEquals(List.of(
                new ShardDescription("shardId-0000000005-00000000001", "shardId-0000000004-00000000001",
                        "000000000000000000001", "000000000000000000001"),
                new ShardDescription("shardId-0000000006-00000000001", "shardId-0000000005-00000000001",
                        "000000000000000000001", null)),
                streams.describeShards(streamArn, null, null).shards());
    }

    @Test
    void aClosedShardStaysListedWhileItHoldsRecords() {
        DynamoDbStreamService streams = streams(new Settings(1, Duration.ofHours(1), false, false));
        String streamArn = enable(streams);
        put(streams, "kept");
        for (int hour = 1; hour <= 3; hour++) {
            clock.advance(Duration.ofHours(1));
            streams.describeShards(streamArn, null, null);
        }

        List<ShardDescription> shards = streams.describeShards(streamArn, null, null).shards();
        assertEquals(DynamoDbStreamService.SHARD_ID, shards.getFirst().shardId());
        assertEquals(List.of("kept"), keys(readAll(streams, streamArn, DynamoDbStreamService.SHARD_ID)));

        for (int i = 0; i < DynamoDbStreamService.MAX_RECORDS; i++) {
            put(streams, "newer-" + i);
        }
        assertTrue(streams.describeShards(streamArn, null, null).shards().stream()
                .noneMatch(shard -> DynamoDbStreamService.SHARD_ID.equals(shard.shardId())),
                "once trimming empties it, the closed shard goes");
    }

    @Test
    void theRecordCapTrimsTheStreamsOldestRecordWhicheverShardHoldsIt() {
        DynamoDbStreamService streams = streams(new Settings(2, null, false, false));
        String streamArn = enable(streams);
        put(streams, "first");
        String holder = null;
        Map<String, String> horizons = new HashMap<>();
        for (ShardDescription shard : streams.describeShards(streamArn, null, null).shards()) {
            horizons.put(shard.shardId(), streams.getShardIterator(streamArn, shard.shardId(), "TRIM_HORIZON", null));
            if (!readAll(streams, streamArn, shard.shardId()).isEmpty()) {
                holder = shard.shardId();
            }
        }

        for (int i = 0; i < DynamoDbStreamService.MAX_RECORDS; i++) {
            put(streams, "next-" + i);
        }

        int retained = 0;
        for (Map.Entry<String, String> horizon : horizons.entrySet()) {
            if (horizon.getKey().equals(holder)) {
                AwsException trimmed = assertThrows(AwsException.class, () -> streams.getRecords(horizon.getValue(),
                        10));
                assertEquals("TrimmedDataAccessException", trimmed.getErrorCode());
            } else {
                assertEquals(10, streams.getRecords(horizon.getValue(), 10).records().size(),
                        "the shard that lost nothing reads from its start");
            }
            retained += readAll(streams, streamArn, horizon.getKey()).size();
        }
        assertEquals(DynamoDbStreamService.MAX_RECORDS, retained);
    }

    @Test
    void describeStreamPagesThroughTheShards() {
        DynamoDbStreamService streams = streams(new Settings(3, null, false, false));
        String streamArn = enable(streams);

        DynamoDbStreamService.ShardsPage first = streams.describeShards(streamArn, null, 2);
        DynamoDbStreamService.ShardsPage second = streams.describeShards(streamArn, first.lastEvaluatedShardId(), 2);

        assertEquals(List.of("shardId-0000000001-00000000001", "shardId-0000000001-00000000002"),
                first.shards().stream().map(ShardDescription::shardId).toList());
        assertEquals("shardId-0000000001-00000000002", first.lastEvaluatedShardId());
        assertEquals(List.of("shardId-0000000001-00000000003"),
                second.shards().stream().map(ShardDescription::shardId).toList());
        assertNull(second.lastEvaluatedShardId());
    }

    @Test
    void flocisOwnStreamReaderReadsTheParentToItsEndAndThenTheChild() throws Exception {
        DynamoDbStreamService streams = streams(new Settings(1, Duration.ofHours(1), false, false));
        String streamArn = enable(streams);
        NativeDynamoDbStreamsJsonHandler handler = handler(streams);
        DynamoDbStreamReader reader = new DynamoDbApiStreamReader(call -> {
            Response response = handler.handle(call.action(), call.body(), call.scope().region());
            return new DynamoDbOperations.Reply(response.getStatus(), (JsonNode) response.getEntity(), Map.of());
        }, DynamoDbStreamReader.CheckpointLifetime.PROCESS);
        DynamoDbStreamReader.Stream stream = DynamoDbStreamReader.Stream.of(streamArn);
        put(streams, "parent-1");
        put(streams, "parent-2");
        clock.advance(Duration.ofHours(1));
        put(streams, "child-1");

        List<DynamoDbStreamReader.Shard> shards = reader.shards(stream);
        assertEquals(DynamoDbStreamService.SHARD_ID, shards.get(1).parentShardId());
        assertNotNull(shards.get(0).endingSequenceNumber());
        assertEquals(List.of(shards.get(0)), DynamoDbStreamReader.readable(shards, Set.of()),
                "the child waits for its parent");

        DynamoDbStreamReader.RecordsPage parent = reader.readAfter(stream, shards.get(0).shardId(), null, 10);
        assertEquals(2, parent.records().size());
        DynamoDbStreamReader.RecordsPage end = reader.readAfter(stream, shards.get(0).shardId(),
                parent.records().getLast().sequenceNumber(), 10);
        assertTrue(end.closed(), "the parent reads as finished");
        assertEquals(List.of(shards.get(1)), DynamoDbStreamReader.readable(shards, Set.of(shards.get(0).shardId())));
        assertEquals(1, reader.readAfter(stream, shards.get(1).shardId(), null, 10).records().size());
    }

    private DynamoDbStreamService streams(Settings settings) {
        return new DynamoDbStreamService(mapper, new InMemoryStorage<>(), settings, clock);
    }

    private NativeDynamoDbStreamsJsonHandler handler(DynamoDbStreamService streams) {
        DynamoDbService tables = mock(DynamoDbService.class);
        when(tables.describeTable(any(), any())).thenReturn(table);
        return new NativeDynamoDbStreamsJsonHandler(streams, tables, new RegionResolver(REGION, ACCOUNT), mapper);
    }

    private String enable(DynamoDbStreamService streams) {
        return streams.enableStream(table.getTableName(), table.getTableArn(), "KEYS_ONLY", REGION).getStreamArn();
    }

    private void put(DynamoDbStreamService streams, String key) {
        streams.captureEvent("INSERT", null, mapper.createObjectNode().set("pk", mapper.createObjectNode().put("S", key)),
                table, REGION);
    }

    /** Every record the shard holds, from its trim horizon to its tip. */
    private static List<DynamoDbStreamRecord> readAll(DynamoDbStreamService streams, String streamArn,
                                                      String shardId) {
        List<DynamoDbStreamRecord> records = new ArrayList<>();
        String iterator = streams.getShardIterator(streamArn, shardId, "TRIM_HORIZON", null);
        while (iterator != null) {
            DynamoDbStreamService.GetRecordsResult page = streams.getRecords(iterator, 100);
            if (page.records().isEmpty()) {
                break;
            }
            records.addAll(page.records());
            iterator = page.nextShardIterator();
        }
        return records;
    }

    private static List<String> keys(List<DynamoDbStreamRecord> records) {
        return records.stream().map(record -> record.getKeys().path("pk").path("S").asText()).toList();
    }

    /** A clock the test moves by hand. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
