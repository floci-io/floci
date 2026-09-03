package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.KinesisStreamingDestination;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.kinesis.model.KinesisRecord;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TTL sweep must forward a REMOVE per expired item to Kinesis (happy path) and must isolate
 * forwarding failures: a throwing forwarder cannot abort the sweep or block persistence.
 * See issue #571.
 */
class DynamoDbTtlForwardingTest {

    private static final String REGION = "us-east-1";

    private final ObjectMapper mapper = new ObjectMapper();

    private KinesisService realKinesis() {
        Map<String, AccountAwareStorageBackend<?>> backends = new HashMap<>();
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> backends.computeIfAbsent(
                        inv.getArgument(0) + "/" + inv.getArgument(1),
                        k -> AccountAwareStorageBackend.inMemory("000000000000")));
        return new KinesisService(factory, new RegionResolver("us-east-1", "000000000000"));
    }

    private ObjectNode stringAttr(String v) {
        ObjectNode n = mapper.createObjectNode();
        n.put("S", v);
        return n;
    }

    private ObjectNode numberAttr(long v) {
        ObjectNode n = mapper.createObjectNode();
        n.put("N", String.valueOf(v));
        return n;
    }

    private void seedTtlTable(DynamoDbService svc, int expiredCount) {
        svc.createTable("TtlTable",
                List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")),
                5L, 5L, REGION);
        svc.updateTimeToLive("TtlTable", "expireAt", true, REGION);
        long past = Instant.now().getEpochSecond() - 3600;
        for (int i = 0; i < expiredCount; i++) {
            ObjectNode item = mapper.createObjectNode();
            item.set("pk", stringAttr("row-" + i));
            item.set("expireAt", numberAttr(past));
            svc.putItem("TtlTable", item, REGION);
        }
    }

    @SuppressWarnings("unchecked")
    private List<KinesisRecord> drain(KinesisService kinesis, String streamName) {
        String shardId = kinesis.describeStream(streamName, REGION).getShards().getFirst().getShardId();
        String iterator = kinesis.getShardIterator(streamName, shardId, "TRIM_HORIZON", null, REGION);
        List<KinesisRecord> all = new ArrayList<>();
        while (true) {
            Map<String, Object> page = kinesis.getRecords(iterator, 1000, REGION);
            List<KinesisRecord> records = (List<KinesisRecord>) page.get("Records");
            if (records.isEmpty()) break;
            all.addAll(records);
            iterator = (String) page.get("NextShardIterator");
        }
        return all;
    }

    @Test
    void ttlSweep_forwards_one_remove_per_expired_item() throws Exception {
        StorageBackend<String, TableDefinition> tableStore = new InMemoryStorage<>();
        StorageBackend<String, Map<String, JsonNode>> itemStore = new InMemoryStorage<>();
        KinesisService kinesis = realKinesis();
        KinesisStreamingForwarder forwarder = new KinesisStreamingForwarder(kinesis, mapper);
        DynamoDbService svc = new DynamoDbService(
                tableStore, itemStore, new RegionResolver("us-east-1", "000000000000"), null, forwarder);

        KinesisStream stream = kinesis.createStream("ttl-stream", 1, REGION);
        int expired = 5;
        seedTtlTable(svc, expired);
        // Attach the destination after createTable (live stored ref).
        TableDefinition table = tableStore.get("us-east-1::TtlTable").orElseThrow();
        table.getKinesisStreamingDestinations().add(new KinesisStreamingDestination(stream.getStreamArn()));

        svc.deleteExpiredItems();

        List<KinesisRecord> drained = drain(kinesis, "ttl-stream");
        assertEquals(expired, drained.size(), "one Kinesis record per expired item");
        for (KinesisRecord record : drained) {
            JsonNode payload = mapper.readTree(record.getData());
            assertEquals("REMOVE", payload.get("eventName").asText(), "TTL deletions forward as REMOVE");
        }
        assertEquals(0L, forwarder.getForwardFailureCount(), "happy path drops nothing");

        for (int i = 0; i < expired; i++) {
            ObjectNode key = mapper.createObjectNode();
            key.set("pk", stringAttr("row-" + i));
            assertNull(svc.getItem("TtlTable", key, REGION), "expired item must be gone");
        }
    }

    @Test
    void ttlSweep_isolates_forwarding_failures_and_still_persists() {
        StorageBackend<String, TableDefinition> tableStore = new InMemoryStorage<>();
        StorageBackend<String, Map<String, JsonNode>> itemStore = new InMemoryStorage<>();

        KinesisService throwingKinesis = mock(KinesisService.class);
        when(throwingKinesis.putRecord(anyString(), any(byte[].class), anyString(), anyString()))
                .thenThrow(new RuntimeException("kinesis unavailable"));
        KinesisStreamingForwarder forwarder = new KinesisStreamingForwarder(throwingKinesis, mapper);
        DynamoDbService svc = new DynamoDbService(
                tableStore, itemStore, new RegionResolver("us-east-1", "000000000000"), null, forwarder);

        int expired = 5;
        seedTtlTable(svc, expired);
        TableDefinition table = tableStore.get("us-east-1::TtlTable").orElseThrow();
        table.getKinesisStreamingDestinations().add(new KinesisStreamingDestination(
                "arn:aws:kinesis:us-east-1:000000000000:stream/ttl-stream"));

        // A forwarding failure must not propagate out of the sweep.
        assertDoesNotThrow(svc::deleteExpiredItems);

        assertEquals(expired, forwarder.getForwardFailureCount(),
                "each failed forward must be counted, not swallowed silently");

        // Removed in-memory AND persisted: a fresh service over the same stores must not see them.
        for (int i = 0; i < expired; i++) {
            ObjectNode key = mapper.createObjectNode();
            key.set("pk", stringAttr("row-" + i));
            assertNull(svc.getItem("TtlTable", key, REGION), "expired item removed from live state");
        }

        // Direct proof of persistence: the sweep must have written the trimmed map back to the
        // underlying item store. Inspecting the stored map (rather than getItem) is what makes this
        // fail if persistItemsForAccount is dropped: getItem would still return null for expired
        // rows via isExpired even though the stale rows remained physically persisted. putItem
        // during seeding persisted 'expired' rows here, so the store must now hold none of them.
        Map<String, JsonNode> persisted = itemStore.get("us-east-1::TtlTable").orElseGet(Map::of);
        assertTrue(persisted.isEmpty(),
                () -> "TTL removals must be persisted to the item store; stale rows still present: "
                        + persisted.keySet());

        // Corroborate via a fresh service reload over the same stores.
        DynamoDbService reloaded = new DynamoDbService(
                tableStore, itemStore, new RegionResolver("us-east-1", "000000000000"));
        for (int i = 0; i < expired; i++) {
            ObjectNode key = mapper.createObjectNode();
            key.set("pk", stringAttr("row-" + i));
            assertNull(reloaded.getItem("TtlTable", key, REGION), "expired removal must be persisted");
        }
    }
}
