package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPointInTimeRecovery.ItemChange;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamoDbPointInTimeRecoveryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";
    private static final String TABLE_KEY = "us-east-1::Items";

    private final MutableClock clock = new MutableClock();
    private final DynamoDbPointInTimeRecovery recovery =
            new DynamoDbPointInTimeRecovery(new InMemoryStorage<>(), ACCOUNT, Duration.ofMinutes(5), clock);
    private final TableDefinition table = new TableDefinition("Items",
            List.of(new KeySchemaElement("pk", "HASH")), List.of(new AttributeDefinition("pk", "S")));
    private final Map<String, JsonNode> live = new ConcurrentHashMap<>();

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

    private static JsonNode item(String value) {
        ObjectNode item = MAPPER.createObjectNode();
        item.putObject("pk").put("S", "k");
        item.putObject("v").put("S", value);
        return item;
    }
}
