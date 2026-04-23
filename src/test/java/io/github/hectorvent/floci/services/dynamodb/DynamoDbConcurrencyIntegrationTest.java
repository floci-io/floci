package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.ConditionalCheckFailedException;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.StreamDescription;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency compatibility suite for {@link DynamoDbService}.
 *
 * <p>All scenarios use a {@link CountDownLatch} starting gate so threads release
 * simultaneously, maximising contention. Wrapped in {@code @RepeatedTest(5)} so a
 * regression that surfaces intermittently has a real chance of being observed in a
 * single CI run.
 *
 * <p>See issue #571.
 */
class DynamoDbConcurrencyIntegrationTest {

    private static final int REPEATS = 5;
    private static final int THREADS = 32;
    private static final int OPS_PER_SCENARIO = 50;

    private DynamoDbService service;
    private DynamoDbStreamService streamService;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        StorageBackend<String, TableDefinition> tableStore = new InMemoryStorage<>();
        streamService = new DynamoDbStreamService(mapper, tableStore);
        service = new DynamoDbService(
                tableStore,
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"),
                streamService,
                null);
    }

    private TableDefinition createCounterTable() {
        return service.createTable("Counters",
                List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")),
                5L, 5L);
    }

    private ObjectNode stringAttr(String value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("S", value);
        return node;
    }

    private ObjectNode numberAttr(String value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("N", value);
        return node;
    }

    private ObjectNode pkKey(String value) {
        ObjectNode key = mapper.createObjectNode();
        key.set("pk", stringAttr(value));
        return key;
    }

    private ObjectNode itemWithPk(String pkValue) {
        return pkKey(pkValue);
    }

    /** Run {@code work} in {@code threadCount} threads, released together. */
    private List<Throwable> runConcurrently(int threadCount, Runnable work) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        work.run();
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(30, TimeUnit.SECONDS),
                    "concurrent tasks did not complete within 30s");
            return errors;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS),
                    "thread pool did not terminate");
        }
    }

    @RepeatedTest(REPEATS)
    void concurrent_updateItem_arithmetic_is_atomic() throws InterruptedException {
        createCounterTable();

        String pk = "counter-1";
        ObjectNode key = pkKey(pk);
        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":start", numberAttr("0"));
        exprValues.set(":inc", numberAttr("1"));

        List<Integer> observedValues = Collections.synchronizedList(new ArrayList<>());

        List<Throwable> errors = runConcurrently(OPS_PER_SCENARIO, () -> {
            DynamoDbService.UpdateResult result = service.updateItem(
                    "Counters", key, null,
                    "SET cnt = if_not_exists(cnt, :start) + :inc",
                    null, exprValues, "ALL_NEW");
            JsonNode newItem = result.newItem();
            int value = Integer.parseInt(newItem.get("cnt").get("N").asText());
            observedValues.add(value);
        });

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);

        JsonNode stored = service.getItem("Counters", key);
        assertNotNull(stored, "counter item must exist after updates");
        assertEquals(String.valueOf(OPS_PER_SCENARIO), stored.get("cnt").get("N").asText(),
                "final counter must equal number of increments");

        Set<Integer> distinct = new HashSet<>(observedValues);
        assertEquals(OPS_PER_SCENARIO, distinct.size(),
                () -> "each returned cnt must be distinct, got: " + observedValues);

        List<Integer> sorted = new ArrayList<>(distinct);
        Collections.sort(sorted);
        assertEquals(1, sorted.get(0));
        assertEquals(OPS_PER_SCENARIO, sorted.get(sorted.size() - 1));
    }

    @RepeatedTest(REPEATS)
    void concurrent_updateItem_ADD_action_is_atomic() throws InterruptedException {
        createCounterTable();

        String pk = "counter-add";
        ObjectNode key = pkKey(pk);
        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":inc", numberAttr("1"));

        List<Throwable> errors = runConcurrently(OPS_PER_SCENARIO, () -> service.updateItem(
                "Counters", key, null,
                "ADD cnt :inc",
                null, exprValues, "NONE"));

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);

        JsonNode stored = service.getItem("Counters", key);
        assertNotNull(stored);
        assertEquals(String.valueOf(OPS_PER_SCENARIO), stored.get("cnt").get("N").asText(),
                "ADD :inc with N=1 run 50 times must yield 50");
    }

    @RepeatedTest(REPEATS)
    void concurrent_putItem_with_attribute_not_exists_allows_exactly_one() throws InterruptedException {
        createCounterTable();

        String pk = "unique-row";
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conditionalFailures = new AtomicInteger();

        List<Throwable> errors = runConcurrently(OPS_PER_SCENARIO, () -> {
            ObjectNode item = itemWithPk(pk);
            item.set("stamp", numberAttr(String.valueOf(System.nanoTime())));
            try {
                service.putItem("Counters", item, "attribute_not_exists(pk)",
                        null, null, "us-east-1", "NONE");
                successes.incrementAndGet();
            } catch (ConditionalCheckFailedException e) {
                conditionalFailures.incrementAndGet();
            }
        });

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);
        assertEquals(1, successes.get(),
                "exactly one putItem(attribute_not_exists) should succeed under contention");
        assertEquals(OPS_PER_SCENARIO - 1, conditionalFailures.get(),
                "the remaining attempts should raise ConditionalCheckFailedException");
    }

    @RepeatedTest(REPEATS)
    void concurrent_putItem_with_distinct_keys_all_succeed() throws InterruptedException {
        createCounterTable();

        AtomicInteger idSource = new AtomicInteger();

        List<Throwable> errors = runConcurrently(OPS_PER_SCENARIO, () -> {
            int id = idSource.getAndIncrement();
            ObjectNode item = itemWithPk("distinct-" + id);
            item.set("val", numberAttr(String.valueOf(id)));
            service.putItem("Counters", item, "us-east-1");
        });

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);

        for (int i = 0; i < OPS_PER_SCENARIO; i++) {
            JsonNode stored = service.getItem("Counters", pkKey("distinct-" + i));
            assertNotNull(stored, "distinct-" + i + " should exist — proves per-item locking, not table-wide");
        }
    }

    @RepeatedTest(REPEATS)
    void concurrent_updateItem_and_putItem_on_same_key_is_linearisable() throws InterruptedException {
        createCounterTable();

        String pk = "mixed";
        ObjectNode key = pkKey(pk);

        ObjectNode updateExprValues = mapper.createObjectNode();
        updateExprValues.set(":start", numberAttr("0"));
        updateExprValues.set(":inc", numberAttr("1"));

        AtomicInteger idSource = new AtomicInteger();

        List<Throwable> errors = runConcurrently(OPS_PER_SCENARIO, () -> {
            int id = idSource.getAndIncrement();
            if (id % 2 == 0) {
                service.updateItem("Counters", key, null,
                        "SET cnt = if_not_exists(cnt, :start) + :inc",
                        null, updateExprValues, "NONE");
            } else {
                ObjectNode item = itemWithPk(pk);
                item.set("writer", stringAttr("put-" + id));
                service.putItem("Counters", item, "us-east-1");
            }
        });

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);

        JsonNode stored = service.getItem("Counters", key);
        assertNotNull(stored, "item must still exist");
        // Must be a well-formed single record; no half-updated state.
        assertEquals("S", stored.get("pk").fieldNames().next());
        assertEquals(pk, stored.get("pk").get("S").asText());
        // Exactly one of writer/cnt must be the 'last write' — we can't assert which
        // without a happens-before record; just require no null DynamoDB attribute values.
        stored.fields().forEachRemaining(entry ->
                assertNotNull(entry.getValue(), "attribute " + entry.getKey() + " must not be null"));
    }

    @RepeatedTest(REPEATS)
    void concurrent_deleteItem_with_condition_only_one_succeeds() throws InterruptedException {
        createCounterTable();

        String pk = "to-delete";
        ObjectNode seed = itemWithPk(pk);
        seed.set("marker", stringAttr("present"));
        service.putItem("Counters", seed, "us-east-1");

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conditionalFailures = new AtomicInteger();

        ObjectNode key = pkKey(pk);

        List<Throwable> errors = runConcurrently(OPS_PER_SCENARIO, () -> {
            try {
                service.deleteItem("Counters", key,
                        "attribute_exists(pk)", null, null, "us-east-1", "NONE");
                successes.incrementAndGet();
            } catch (ConditionalCheckFailedException e) {
                conditionalFailures.incrementAndGet();
            }
        });

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);
        assertEquals(1, successes.get(),
                "exactly one conditional delete should succeed");
        assertEquals(OPS_PER_SCENARIO - 1, conditionalFailures.get(),
                "the rest must raise ConditionalCheckFailedException");
        assertNull(service.getItem("Counters", key), "item must be gone");
    }

    @RepeatedTest(REPEATS)
    void concurrent_transactWriteItems_all_or_nothing() throws InterruptedException {
        createCounterTable();

        String pkA = "txA";
        String pkB = "txB";

        // Seed both keys with version=0 so we can write a conflicting conditional increment.
        ObjectNode seedA = itemWithPk(pkA);
        seedA.set("version", numberAttr("0"));
        ObjectNode seedB = itemWithPk(pkB);
        seedB.set("version", numberAttr("0"));
        service.putItem("Counters", seedA, "us-east-1");
        service.putItem("Counters", seedB, "us-east-1");

        AtomicInteger committed = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();

        // Each transaction updates both keys with a condition "version = :v0" and
        // sets version = :v1. Concurrent attempts must serialise: one wins, others cancel.
        List<Throwable> errors = runConcurrently(OPS_PER_SCENARIO, () -> {
            JsonNode versionBefore = service.getItem("Counters", pkKey(pkA)).get("version");
            int currentVersion = Integer.parseInt(versionBefore.get("N").asText());
            int nextVersion = currentVersion + 1;

            ObjectNode exprValues = mapper.createObjectNode();
            exprValues.set(":v0", numberAttr(String.valueOf(currentVersion)));
            exprValues.set(":v1", numberAttr(String.valueOf(nextVersion)));

            ObjectNode tx1 = buildUpdateTx(pkA, exprValues);
            ObjectNode tx2 = buildUpdateTx(pkB, exprValues);

            try {
                service.transactWriteItems(List.of(tx1, tx2), "us-east-1");
                committed.incrementAndGet();
            } catch (TransactionCanceledException e) {
                cancelled.incrementAndGet();
            }
        });

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);

        JsonNode finalA = service.getItem("Counters", pkKey(pkA));
        JsonNode finalB = service.getItem("Counters", pkKey(pkB));
        int versionA = Integer.parseInt(finalA.get("version").get("N").asText());
        int versionB = Integer.parseInt(finalB.get("version").get("N").asText());
        assertEquals(versionA, versionB,
                "both keys must end on the same version — proves transactional atomicity");
        assertEquals(committed.get(), versionA,
                "commit count must match version progress");
        assertTrue(committed.get() + cancelled.get() >= OPS_PER_SCENARIO,
                "every attempt must either commit or cancel");
    }

    private ObjectNode buildUpdateTx(String pk, ObjectNode exprValues) {
        ObjectNode update = mapper.createObjectNode();
        update.put("TableName", "Counters");
        update.set("Key", pkKey(pk));
        update.put("UpdateExpression", "SET version = :v1");
        update.put("ConditionExpression", "version = :v0");
        update.set("ExpressionAttributeValues", exprValues);
        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("Update", update);
        return wrapper;
    }

    @RepeatedTest(REPEATS)
    void concurrent_transactWriteItems_disjoint_commute() throws InterruptedException {
        createCounterTable();

        // Seed 2 * OPS_PER_SCENARIO keys. Each transaction touches two disjoint keys.
        for (int i = 0; i < OPS_PER_SCENARIO * 2; i++) {
            ObjectNode seed = itemWithPk("disjoint-" + i);
            seed.set("version", numberAttr("0"));
            service.putItem("Counters", seed, "us-east-1");
        }

        AtomicInteger txId = new AtomicInteger();

        List<Throwable> errors = runConcurrently(OPS_PER_SCENARIO, () -> {
            int id = txId.getAndIncrement();
            String keyA = "disjoint-" + (id * 2);
            String keyB = "disjoint-" + (id * 2 + 1);

            ObjectNode exprValues = mapper.createObjectNode();
            exprValues.set(":v0", numberAttr("0"));
            exprValues.set(":v1", numberAttr("1"));

            ObjectNode tx1 = buildUpdateTx(keyA, exprValues);
            ObjectNode tx2 = buildUpdateTx(keyB, exprValues);
            service.transactWriteItems(List.of(tx1, tx2), "us-east-1");
        });

        assertTrue(errors.isEmpty(),
                () -> "disjoint transactions must not deadlock or cancel: " + errors);

        for (int i = 0; i < OPS_PER_SCENARIO * 2; i++) {
            JsonNode stored = service.getItem("Counters", pkKey("disjoint-" + i));
            assertEquals("1", stored.get("version").get("N").asText(),
                    "disjoint-" + i + " should be committed");
        }
    }

    @RepeatedTest(REPEATS)
    void concurrent_batchWriteItem_accumulates_without_lost_writes() throws InterruptedException {
        createCounterTable();

        AtomicInteger idSource = new AtomicInteger();
        ConcurrentHashMap<String, Boolean> submittedIds = new ConcurrentHashMap<>();

        List<Throwable> errors = runConcurrently(OPS_PER_SCENARIO, () -> {
            int id = idSource.getAndIncrement();
            List<JsonNode> writeRequests = new ArrayList<>();
            for (int j = 0; j < 5; j++) {
                String itemId = "batch-" + id + "-" + j;
                submittedIds.put(itemId, true);
                ObjectNode item = itemWithPk(itemId);
                item.set("batch", numberAttr(String.valueOf(id)));

                ObjectNode putRequest = mapper.createObjectNode();
                putRequest.set("Item", item);
                ObjectNode req = mapper.createObjectNode();
                req.set("PutRequest", putRequest);
                writeRequests.add(req);
            }
            service.batchWriteItem(Map.of("Counters", writeRequests), "us-east-1");
        });

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);

        for (String itemId : submittedIds.keySet()) {
            assertNotNull(service.getItem("Counters", pkKey(itemId)),
                    "every batch-submitted item must land: " + itemId);
        }
    }

    @RepeatedTest(REPEATS)
    void concurrent_updateItem_preserves_stream_order() throws InterruptedException {
        TableDefinition table = createCounterTable();
        StreamDescription sd = streamService.enableStream(
                table.getTableName(), table.getTableArn(), "NEW_IMAGE", "us-east-1");

        String pk = "streamed";
        ObjectNode key = pkKey(pk);
        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":start", numberAttr("0"));
        exprValues.set(":inc", numberAttr("1"));

        int ops = 20;

        List<Throwable> errors = runConcurrently(ops, () -> service.updateItem(
                "Counters", key, null,
                "SET cnt = if_not_exists(cnt, :start) + :inc",
                null, exprValues, "NONE"));

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);

        String iterator = streamService.getShardIterator(sd.getStreamArn(),
                DynamoDbStreamService.SHARD_ID, "TRIM_HORIZON", null);
        DynamoDbStreamService.GetRecordsResult pulled = streamService.getRecords(iterator, 1000);

        assertEquals(ops, pulled.records().size(),
                "stream must emit one record per mutation");

        // The NEW_IMAGE.cnt values, read in stream order, must be strictly increasing.
        int previous = 0;
        for (var record : pulled.records()) {
            JsonNode newImage = record.getNewImage();
            assertNotNull(newImage, "NEW_IMAGE view type must populate newImage");
            int current = Integer.parseInt(newImage.get("cnt").get("N").asText());
            assertEquals(previous + 1, current,
                    "stream events must be emitted in the order the mutations landed");
            previous = current;
        }
        assertEquals(ops, previous, "final event must reflect final counter value");
    }

    @Test
    void baseline_single_threaded_still_works() {
        createCounterTable();

        ObjectNode key = pkKey("single");
        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":start", numberAttr("0"));
        exprValues.set(":inc", numberAttr("1"));

        for (int i = 1; i <= 10; i++) {
            service.updateItem("Counters", key, null,
                    "SET cnt = if_not_exists(cnt, :start) + :inc",
                    null, exprValues, "ALL_NEW");
        }

        JsonNode stored = service.getItem("Counters", key);
        assertNotNull(stored);
        assertEquals("10", stored.get("cnt").get("N").asText());
    }
}
