package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPointInTimeRecovery.ItemChange;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.PointInTimeChange;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamoDbPointInTimeRecoveryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";
    private static final String TABLE_KEY = "us-east-1::Items";

    private final MutableClock clock = new MutableClock();
    private final StorageBackend<String, PointInTimeChange> store = new InMemoryStorage<>();
    private final DynamoDbPointInTimeRecovery recovery =
            new DynamoDbPointInTimeRecovery(store, ACCOUNT, Duration.ofMinutes(5), clock);
    private final TableDefinition table = new TableDefinition("Items",
            List.of(new KeySchemaElement("pk", "HASH")), List.of(new AttributeDefinition("pk", "S")));
    private final Map<String, JsonNode> live = new ConcurrentHashMap<>();

    @Test
    void recordItemDropsTheChangesThatLeftTheRecoveryPeriod() {
        table.setPointInTimeRecoveryEnabled(true);
        table.setPointInTimeRecoveryRecoveryPeriodInDays(1);
        for (String value : List.of("A", "B", "C")) {
            recovery.recordItem(ACCOUNT, TABLE_KEY, table, "k", item(value));
            clock.advance(Duration.ofHours(12));
        }

        assertEquals(Set.of(TABLE_KEY + "#1", TABLE_KEY + "#2"), store.keys());
    }

    @Test
    void changesBetweenWaitsForAWriteThatIsRecordedButNotYetWritten() {
        table.setPointInTimeRecoveryEnabled(true);
        Instant from = clock.instant();
        live.put("k", item("A"));
        recovery.recordItem(ACCOUNT, TABLE_KEY, table, "k", item("A"));

        List<ItemChange> changes = recovery.changesBetween(ACCOUNT, TABLE_KEY, live, from, from.plusSeconds(60),
                (itemKey, read) -> {
                    live.put(itemKey, item("B"));
                    return read.get();
                });

        assertEquals(1, changes.size());
        assertEquals(item("A"), changes.getFirst().oldItem());
        assertEquals(item("B"), changes.getFirst().newItem());
    }

    @Test
    void changesBetweenLeavesOutAWriteAfterTheEndThatFinishesBeforeTheItemIsRead() {
        table.setPointInTimeRecoveryEnabled(true);
        Instant from = clock.instant();
        recovery.recordItem(ACCOUNT, TABLE_KEY, table, "k", item("A"));
        live.put("k", item("B"));

        List<ItemChange> changes = recovery.changesBetween(ACCOUNT, TABLE_KEY, live, from, from.plusSeconds(60),
                (itemKey, read) -> {
                    clock.advance(Duration.ofMinutes(2));
                    recovery.recordItem(ACCOUNT, TABLE_KEY, table, itemKey, live.get(itemKey));
                    live.put(itemKey, item("C"));
                    return read.get();
                });

        assertEquals(1, changes.size());
        assertEquals(item("B"), changes.getFirst().newItem());
    }

    @Test
    void itemsAtWaitsForAWriteThatIsRecordedButNotYetWritten() {
        table.setPointInTimeRecoveryEnabled(true);
        live.put("k", item("A"));
        recovery.recordItem(ACCOUNT, TABLE_KEY, table, "k", item("A"));

        List<JsonNode> items = recovery.itemsAt(ACCOUNT, TABLE_KEY, live, clock.instant(),
                (itemKey, read) -> {
                    live.put(itemKey, item("B"));
                    return read.get();
                });

        assertEquals(List.of(item("B")), items);
    }

    @Test
    void itemsAtWaitsForADeleteThatIsRecordedButNotYetDone() {
        table.setPointInTimeRecoveryEnabled(true);
        live.put("k", item("A"));
        recovery.recordItem(ACCOUNT, TABLE_KEY, table, "k", item("A"));

        List<JsonNode> items = recovery.itemsAt(ACCOUNT, TABLE_KEY, live, clock.instant(),
                (itemKey, read) -> {
                    live.remove(itemKey);
                    return read.get();
                });

        assertEquals(List.of(), items);
    }

    @Test
    void itemsAtLeavesOutAWriteAfterTheTimeThatFinishesBeforeTheItemIsRead() {
        table.setPointInTimeRecoveryEnabled(true);
        recovery.recordItem(ACCOUNT, TABLE_KEY, table, "k", null);
        live.put("k", item("A"));
        Instant time = clock.instant();

        List<JsonNode> items = recovery.itemsAt(ACCOUNT, TABLE_KEY, live, time,
                (itemKey, read) -> {
                    clock.advance(Duration.ofSeconds(1));
                    recovery.recordItem(ACCOUNT, TABLE_KEY, table, itemKey, live.get(itemKey));
                    live.put(itemKey, item("B"));
                    return read.get();
                });

        assertEquals(List.of(item("A")), items);
    }

    @Test
    void itemsAtLeavesOutAWriteAfterTheTimeToAnItemThatItReadsLater() {
        table.setPointInTimeRecoveryEnabled(true);
        for (String itemKey : List.of("k1", "k2")) {
            recovery.recordItem(ACCOUNT, TABLE_KEY, table, itemKey, null);
            live.put(itemKey, item("A" + itemKey));
        }
        Instant time = clock.instant();
        Set<String> reads = new HashSet<>();

        List<JsonNode> items = recovery.itemsAt(ACCOUNT, TABLE_KEY, live, time,
                (itemKey, read) -> {
                    if (reads.isEmpty()) {
                        String other = "k1".equals(itemKey) ? "k2" : "k1";
                        clock.advance(Duration.ofSeconds(1));
                        recovery.recordItem(ACCOUNT, TABLE_KEY, table, other, live.get(other));
                        live.put(other, item("B"));
                    }
                    reads.add(itemKey);
                    return read.get();
                });

        assertEquals(Set.of("k1", "k2"), reads);
        assertEquals(Set.of(item("Ak1"), item("Ak2")), Set.copyOf(items));
    }

    @Test
    void itemsAtGivesTheSameItemsLaterWhenAWriteFollowsInTheSameMillisecond() {
        table.setPointInTimeRecoveryEnabled(true);
        // MutableClock moves on 1 ms at each read, so the write below reads this time.
        Instant time = clock.instant().plusMillis(1);

        List<JsonNode> first = recovery.itemsAt(ACCOUNT, TABLE_KEY, live, time, (itemKey, read) -> read.get());
        recovery.recordItem(ACCOUNT, TABLE_KEY, table, "k", null);
        live.put("k", item("A"));
        List<JsonNode> later = recovery.itemsAt(ACCOUNT, TABLE_KEY, live, time, (itemKey, read) -> read.get());

        assertEquals(List.of(), first);
        assertEquals(first, later);
    }

    private static JsonNode item(String value) {
        ObjectNode item = MAPPER.createObjectNode();
        item.putObject("pk").put("S", "k");
        item.putObject("v").put("S", value);
        return item;
    }
}
