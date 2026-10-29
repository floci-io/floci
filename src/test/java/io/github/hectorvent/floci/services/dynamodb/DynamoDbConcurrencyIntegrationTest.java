package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.ConditionalCheckFailedException;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.KinesisStreamingDestination;
import io.github.hectorvent.floci.services.dynamodb.model.PointInTimeChange;
import io.github.hectorvent.floci.services.dynamodb.model.StreamDescription;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.kinesis.model.KinesisRecord;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import io.github.hectorvent.floci.testing.StartGate;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Concurrency compatibility suite for {@link DynamoDbService}.
 *
 * <p>All scenarios use a {@link StartGate} so threads release
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

    private TableDefinition createCounterTable(String region) {
        return service.createTable("Counters",
                List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")),
                5L, 5L, region);
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
        return StartGate.runConcurrently(threadCount, i -> {
            work.run();
            return null;
        }).errors();
    }

    @RepeatedTest(REPEATS)
    void concurrent_updateItem_arithmetic_is_atomic() throws InterruptedException {
        String region = "eu-west-1";
        createCounterTable(region);

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
                    null, exprValues, "ALL_NEW", region);
            JsonNode newItem = result.newItem();
            int value = Integer.parseInt(newItem.get("cnt").get("N").asText());
            observedValues.add(value);
        });

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);

        JsonNode stored = service.getItem("Counters", key, region);
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
        String region = "eu-west-1";
        createCounterTable(region);

        String pk = "counter-add";
        ObjectNode key = pkKey(pk);
        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":inc", numberAttr("1"));

        List<Throwable> errors = runConcurrently(OPS_PER_SCENARIO, () -> service.updateItem(
                "Counters", key, null,
                "ADD cnt :inc",
                null, exprValues, "NONE", region));

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);

        JsonNode stored = service.getItem("Counters", key, region);
        assertNotNull(stored);
        assertEquals(String.valueOf(OPS_PER_SCENARIO), stored.get("cnt").get("N").asText(),
                "ADD :inc with N=1 run 50 times must yield 50");
    }

    @RepeatedTest(REPEATS)
    void concurrent_putItem_with_attribute_not_exists_allows_exactly_one() throws InterruptedException {
        createCounterTable("us-east-1");

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
        createCounterTable("us-east-1");

        AtomicInteger idSource = new AtomicInteger();

        List<Throwable> errors = runConcurrently(OPS_PER_SCENARIO, () -> {
            int id = idSource.getAndIncrement();
            ObjectNode item = itemWithPk("distinct-" + id);
            item.set("val", numberAttr(String.valueOf(id)));
            service.putItem("Counters", item, "us-east-1");
        });

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);

        for (int i = 0; i < OPS_PER_SCENARIO; i++) {
            JsonNode stored = service.getItem("Counters", pkKey("distinct-" + i), "us-east-1");
            assertNotNull(stored, "distinct-" + i + " should exist — proves per-item locking, not table-wide");
        }
    }

    @RepeatedTest(REPEATS)
    void concurrent_updateItem_and_putItem_on_same_key_is_linearisable() throws InterruptedException {
        createCounterTable("us-east-1");

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
                        null, updateExprValues, "NONE", "us-east-1");
            } else {
                ObjectNode item = itemWithPk(pk);
                item.set("writer", stringAttr("put-" + id));
                service.putItem("Counters", item, "us-east-1");
            }
        });

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);

        JsonNode stored = service.getItem("Counters", key, "us-east-1");
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
        createCounterTable("us-east-1");

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
        assertNull(service.getItem("Counters", key, "us-east-1"), "item must be gone");
    }

    @RepeatedTest(REPEATS)
    void concurrent_transactWriteItems_all_or_nothing() throws InterruptedException {
        createCounterTable("us-east-1");

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
            JsonNode versionBefore = service.getItem("Counters", pkKey(pkA), "us-east-1").get("version");
            int currentVersion = Integer.parseInt(versionBefore.get("N").asText());
            int nextVersion = currentVersion + 1;

            ObjectNode exprValues = mapper.createObjectNode();
            exprValues.set(":v0", numberAttr(String.valueOf(currentVersion)));
            exprValues.set(":v1", numberAttr(String.valueOf(nextVersion)));

            ObjectNode tx1 = buildUpdateTx(pkA, exprValues);
            ObjectNode tx2 = buildUpdateTx(pkB, exprValues);

            try {
                service.transactWriteItems(List.of(tx1, tx2), "us-east-1", null, null);
                committed.incrementAndGet();
            } catch (TransactionCanceledException e) {
                cancelled.incrementAndGet();
            }
        });

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);

        JsonNode finalA = service.getItem("Counters", pkKey(pkA), "us-east-1");
        JsonNode finalB = service.getItem("Counters", pkKey(pkB), "us-east-1");
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
        createCounterTable("us-east-1");

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
            service.transactWriteItems(List.of(tx1, tx2), "us-east-1", null, null);
        });

        assertTrue(errors.isEmpty(),
                () -> "disjoint transactions must not deadlock or cancel: " + errors);

        for (int i = 0; i < OPS_PER_SCENARIO * 2; i++) {
            JsonNode stored = service.getItem("Counters", pkKey("disjoint-" + i), "us-east-1");
            assertEquals("1", stored.get("version").get("N").asText(),
                    "disjoint-" + i + " should be committed");
        }
    }

    @RepeatedTest(REPEATS)
    void concurrent_batchWriteItem_accumulates_without_lost_writes() throws InterruptedException {
        createCounterTable("us-east-1");

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
            assertNotNull(service.getItem("Counters", pkKey(itemId), "us-east-1"),
                    "every batch-submitted item must land: " + itemId);
        }
    }

    @RepeatedTest(REPEATS)
    void concurrent_updateItem_preserves_stream_order() throws InterruptedException {
        TableDefinition table = createCounterTable("us-east-1");
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
                null, exprValues, "NONE", "us-east-1"));

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);

        String iterator = streamService.getShardIterator(sd.getStreamArn(),
                DynamoDbStreamService.SHARD_ID, "TRIM_HORIZON", null);
        DynamoDbStreamService.GetRecordsResult pulled = streamService.getRecords(iterator, 1000);

        assertEquals(ops, pulled.records().size(),
                "stream must emit one record per mutation");

        // The NEW_IMAGE.cnt values, read in stream order, must be strictly increasing.
        int previous = 0;
        for (DynamoDbStreamRecord record : pulled.records()) {
            JsonNode newImage = record.getNewImage();
            assertNotNull(newImage, "NEW_IMAGE view type must populate newImage");
            int current = Integer.parseInt(newImage.get("cnt").get("N").asText());
            assertEquals(previous + 1, current,
                    "stream events must be emitted in the order the mutations landed");
            previous = current;
        }
        assertEquals(ops, previous, "final event must reflect final counter value");
    }

    /**
     * End-to-end CDC wiring: concurrent putItem must forward EVERY record to the attached Kinesis
     * destination, with no loss and per-shard sequence order preserved. This is the gap the suite's
     * production wiring left uncovered (the shared {@code service} field wires {@code forwarder=null}).
     * See issue #571 (Kinesis append race).
     */
    @RepeatedTest(REPEATS)
    void concurrent_putItem_forwards_every_cdc_record_to_kinesis() throws Exception {
        String region = "us-east-1";
        StorageBackend<String, TableDefinition> tableStore = new InMemoryStorage<>();
        // Public KinesisService ctor via a mocked StorageFactory that hands out in-memory
        // backends keyed by file name (the FirehoseServiceTest pattern), because the
        // package-private ctor is not visible from this package.
        Map<String, AccountAwareStorageBackend<?>> backends = new HashMap<>();
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> backends.computeIfAbsent(
                        inv.getArgument(0) + "/" + inv.getArgument(1),
                        k -> AccountAwareStorageBackend.inMemory("000000000000")));
        KinesisService kinesis = new KinesisService(storageFactory,
                new RegionResolver("us-east-1", "000000000000"));
        KinesisStreamingForwarder forwarder = new KinesisStreamingForwarder(kinesis, mapper);
        DynamoDbService svc = new DynamoDbService(
                tableStore, new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"), null, forwarder);

        KinesisStream stream = kinesis.createStream("cdc-stream", 1, region);
        TableDefinition table = svc.createTable("Events",
                List.of(new KeySchemaElement("pk", "HASH"), new KeySchemaElement("sk", "RANGE")),
                List.of(new AttributeDefinition("pk", "S"), new AttributeDefinition("sk", "S")),
                5L, 5L, region);
        table.getKinesisStreamingDestinations().add(
                new KinesisStreamingDestination(stream.getStreamArn()));

        int threads = 16;
        int opsPerThread = 25;
        int expected = threads * opsPerThread; // 400
        AtomicInteger idSource = new AtomicInteger();

        List<Throwable> errors = runConcurrently(threads, () -> {
            for (int i = 0; i < opsPerThread; i++) {
                int id = idSource.getAndIncrement();
                ObjectNode item = mapper.createObjectNode();
                item.set("pk", stringAttr("fixed"));
                item.set("sk", stringAttr("t-" + id));
                svc.putItem("Events", item, region);
            }
        });
        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);

        // CDC delivery is asynchronous (bounded best-effort retry queue): wait for the background drain to
        // flush every buffered record before reading the shard, otherwise this reads a partial stream.
        assertTrue(forwarder.awaitIdle(Duration.ofSeconds(10)),
                "the CDC drain must flush all buffered records");

        // Paginated drain of the single shard.
        String shardId = kinesis.describeStream("cdc-stream", region).getShards().getFirst().getShardId();
        String iterator = kinesis.getShardIterator("cdc-stream", shardId, "TRIM_HORIZON", null, region);
        List<KinesisRecord> drained = new ArrayList<>();
        while (true) {
            Map<String, Object> page = kinesis.getRecords(iterator, 1000, region);
            @SuppressWarnings("unchecked")
            List<KinesisRecord> records = (List<KinesisRecord>) page.get("Records");
            if (records.isEmpty()) break;
            drained.addAll(records);
            iterator = (String) page.get("NextShardIterator");
        }

        assertEquals(expected, drained.size(), "every CDC event must reach Kinesis");

        Set<String> sortKeys = new HashSet<>();
        long previousSeq = Long.MIN_VALUE;
        for (KinesisRecord record : drained) {
            JsonNode payload = mapper.readTree(record.getData());
            sortKeys.add(payload.get("dynamodb").get("Keys").get("sk").get("S").asText());
            long seq = Long.parseLong(record.getSequenceNumber());
            assertTrue(seq > previousSeq, "per-shard sequence numbers must be strictly increasing");
            previousSeq = seq;
        }

        Set<String> expectedKeys = new HashSet<>();
        for (int i = 0; i < expected; i++) {
            expectedKeys.add("t-" + i);
        }
        assertEquals(expectedKeys, sortKeys, "the forwarded sk set must match exactly, with no gaps");
        assertEquals(0L, forwarder.getForwardFailureCount(), "no CDC event may be dropped");
    }

    // While armed, every GetItem holds its result until its peer has read too, so racing requests
    // that read the item before writing it all read the same state before any of them writes.
    private final AtomicReference<CyclicBarrier> readGate = new AtomicReference<>();

    private NativeDynamoDbJsonHandler handlerWithGatedReads() {
        service = new DynamoDbService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000")) {
            @Override
            public JsonNode getItem(String tableName, JsonNode key, String region) {
                JsonNode item = super.getItem(tableName, key, region);
                CyclicBarrier gate = readGate.get();
                if (gate != null) {
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (BrokenBarrierException | TimeoutException expected) {
                        // A read with no peer to meet just goes ahead on its own.
                    }
                }
                return item;
            }
        };
        return new NativeDynamoDbJsonHandler(service, null, null, mapper);
    }

    private JsonNode json(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException(body, e);
        }
    }

    private JsonNode responseBody(Response response) {
        return mapper.convertValue(response.getEntity(), JsonNode.class);
    }

    private static Response call(NativeDynamoDbJsonHandler handler, String action, JsonNode request) {
        try {
            return handler.handle(action, request, "us-east-1");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(action + " failed", e);
        }
    }

    private List<Throwable> runRacingRequests(int requests, Runnable work) throws InterruptedException {
        readGate.set(new CyclicBarrier(requests));
        try {
            return runConcurrently(requests, work);
        } finally {
            readGate.set(null);
        }
    }

    @RepeatedTest(REPEATS)
    void legacyExpected_putItem_existsFalse_isCheckedAgainstTheItemUnderTheLock() throws InterruptedException {
        NativeDynamoDbJsonHandler handler = handlerWithGatedReads();
        createCounterTable("us-east-1");
        AtomicInteger writerId = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conditionalFailures = new AtomicInteger();

        List<Throwable> errors = runRacingRequests(2, () -> {
            JsonNode request = json("""
                    {"TableName":"Counters","Item":{"pk":{"S":"unique"},"writer":{"S":"w-%d"}},
                     "Expected":{"pk":{"Exists":false}}}
                    """.formatted(writerId.getAndIncrement()));
            try {
                call(handler, "PutItem", request);
                successes.incrementAndGet();
            } catch (ConditionalCheckFailedException e) {
                conditionalFailures.incrementAndGet();
            }
        });

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);
        assertEquals(1, successes.get(), "only one PutItem may create the item when Expected says it is absent");
        assertEquals(1, conditionalFailures.get());
    }

    @RepeatedTest(REPEATS)
    void legacyExpected_deleteItem_isCheckedAgainstTheItemUnderTheLock() throws InterruptedException {
        NativeDynamoDbJsonHandler handler = handlerWithGatedReads();
        createCounterTable("us-east-1");
        ObjectNode seed = itemWithPk("to-delete");
        seed.set("marker", stringAttr("present"));
        service.putItem("Counters", seed, "us-east-1");
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conditionalFailures = new AtomicInteger();

        List<Throwable> errors = runRacingRequests(2, () -> {
            JsonNode request = json("""
                    {"TableName":"Counters","Key":{"pk":{"S":"to-delete"}},
                     "Expected":{"marker":{"Value":{"S":"present"}}}}
                    """);
            try {
                call(handler, "DeleteItem", request);
                successes.incrementAndGet();
            } catch (ConditionalCheckFailedException e) {
                conditionalFailures.incrementAndGet();
            }
        });

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);
        assertEquals(1, successes.get(), "the second DeleteItem must see the item already gone");
        assertEquals(1, conditionalFailures.get());
    }

    @RepeatedTest(REPEATS)
    void legacyExpected_updateItem_versionCheck_isCheckedAgainstTheItemUnderTheLock() throws InterruptedException {
        NativeDynamoDbJsonHandler handler = handlerWithGatedReads();
        createCounterTable("us-east-1");
        ObjectNode seed = itemWithPk("versioned");
        seed.set("version", numberAttr("1"));
        service.putItem("Counters", seed, "us-east-1");
        AtomicInteger writerId = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conditionalFailures = new AtomicInteger();

        List<Throwable> errors = runRacingRequests(2, () -> {
            JsonNode request = json("""
                    {"TableName":"Counters","Key":{"pk":{"S":"versioned"}},
                     "Expected":{"version":{"Value":{"N":"1"}}},
                     "AttributeUpdates":{"version":{"Action":"PUT","Value":{"N":"2"}},
                                         "writer":{"Action":"PUT","Value":{"S":"w-%d"}}}}
                    """.formatted(writerId.getAndIncrement()));
            try {
                call(handler, "UpdateItem", request);
                successes.incrementAndGet();
            } catch (ConditionalCheckFailedException e) {
                conditionalFailures.incrementAndGet();
            }
        });

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);
        assertEquals(1, successes.get(), "only one writer may move version 1 to 2");
        assertEquals(1, conditionalFailures.get());
    }

    @RepeatedTest(REPEATS)
    void putItem_returnValuesAllOld_returnsTheItemTheWriteReplaced() throws InterruptedException {
        NativeDynamoDbJsonHandler handler = handlerWithGatedReads();
        createCounterTable("us-east-1");
        ObjectNode seed = itemWithPk("swap");
        seed.set("writer", stringAttr("seed"));
        service.putItem("Counters", seed, "us-east-1");
        AtomicInteger writerId = new AtomicInteger();
        Map<String, String> replacedBy = new ConcurrentHashMap<>();

        List<Throwable> errors = runRacingRequests(2, () -> {
            String writer = "w-" + writerId.getAndIncrement();
            JsonNode request = json("""
                    {"TableName":"Counters","Item":{"pk":{"S":"swap"},"writer":{"S":"%s"}},
                     "ReturnValues":"ALL_OLD"}
                    """.formatted(writer));
            JsonNode body = responseBody(call(handler, "PutItem", request));
            replacedBy.put(writer, body.path("Attributes").path("writer").path("S").asText());
        });

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);
        List<String> firstWriters = replacedBy.entrySet().stream()
                .filter(entry -> "seed".equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        assertEquals(1, firstWriters.size(), () -> "exactly one PutItem replaced the seed: " + replacedBy);
        String secondWriter = "w-0".equals(firstWriters.get(0)) ? "w-1" : "w-0";
        assertEquals(firstWriters.get(0), replacedBy.get(secondWriter),
                () -> "the second PutItem must return the item the first one wrote: " + replacedBy);
    }

    @RepeatedTest(REPEATS)
    void concurrent_putItem_with_legacy_expected_exists_false_allows_exactly_one() throws InterruptedException {
        createCounterTable("us-east-1");
        NativeDynamoDbJsonHandler handler = new NativeDynamoDbJsonHandler(service, null, null, mapper);
        AtomicInteger writerId = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conditionalFailures = new AtomicInteger();

        List<Throwable> errors = runConcurrently(OPS_PER_SCENARIO, () -> {
            JsonNode request = json("""
                    {"TableName":"Counters","Item":{"pk":{"S":"legacy-unique"},"writer":{"S":"w-%d"}},
                     "Expected":{"pk":{"Exists":false}}}
                    """.formatted(writerId.getAndIncrement()));
            try {
                call(handler, "PutItem", request);
                successes.incrementAndGet();
            } catch (ConditionalCheckFailedException e) {
                conditionalFailures.incrementAndGet();
            }
        });

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);
        assertEquals(1, successes.get(),
                "exactly one PutItem with Expected Exists:false should succeed under contention");
        assertEquals(OPS_PER_SCENARIO - 1, conditionalFailures.get());
    }

    private ObjectNode getTx(String pk) {
        ObjectNode get = mapper.createObjectNode();
        get.put("TableName", "Counters");
        get.set("Key", pkKey(pk));
        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("Get", get);
        return wrapper;
    }

    private ObjectNode setVersionTx(String pk, ObjectNode exprValues) {
        ObjectNode update = mapper.createObjectNode();
        update.put("TableName", "Counters");
        update.set("Key", pkKey(pk));
        update.put("UpdateExpression", "SET version = :v");
        update.set("ExpressionAttributeValues", exprValues);
        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("Update", update);
        return wrapper;
    }

    private void seedVersionedPair() {
        for (String pk : List.of("txA", "txB")) {
            ObjectNode seed = itemWithPk(pk);
            seed.set("version", numberAttr("0"));
            service.putItem("Counters", seed, "us-east-1");
        }
    }

    private static String versionOf(JsonNode item) {
        return item.path("version").path("N").asText();
    }

    private static void awaitDoneOrParked(Future<?> task, Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!task.isDone() && thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
    }

    @RepeatedTest(REPEATS)
    void transactGetItems_doesNotSeeATransactWriteHalfApplied() throws Exception {
        // Point-in-time recovery records each changed participant just before the commit writes
        // it, so parking that record for txB holds the writer after txA is written and before
        // txB is.
        CountDownLatch writerPaused = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicReference<String> pauseBeforeRecording = new AtomicReference<>();
        StorageBackend<String, TableDefinition> tableStore = new InMemoryStorage<>();
        StorageBackend<String, PointInTimeChange> changes = new InMemoryStorage<>() {
            @Override
            public void put(String key, PointInTimeChange change) {
                if (change.getItemKey().equals(pauseBeforeRecording.get())) {
                    writerPaused.countDown();
                    try {
                        releaseWriter.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                super.put(key, change);
            }
        };
        service = new DynamoDbService(tableStore, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), changes,
                new RegionResolver("us-east-1", "000000000000"), null, null, null, mapper, 0, 0, 0,
                Clock.systemUTC());
        createCounterTable("us-east-1");
        seedVersionedPair();
        TableDefinition table = tableStore.get("us-east-1::Counters").orElseThrow();
        table.setPointInTimeRecoveryEnabled(true);
        table.setPointInTimeRecoveryRecoveryPeriodInDays(35);
        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":v", numberAttr("1"));
        pauseBeforeRecording.set("txB");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> service.transactWriteItems(
                    List.of(setVersionTx("txA", exprValues), setVersionTx("txB", exprValues)),
                    "us-east-1", null, null));
            assertTrue(writerPaused.await(10, TimeUnit.SECONDS), "the writer never reached its second participant");

            CountDownLatch readerStarted = new CountDownLatch(1);
            AtomicReference<Thread> readerThread = new AtomicReference<>();
            Future<DynamoDbService.TransactGetResult> reader = pool.submit(() -> {
                readerThread.set(Thread.currentThread());
                readerStarted.countDown();
                return service.transactGetItems(List.of(getTx("txA"), getTx("txB")), "us-east-1");
            });
            assertTrue(readerStarted.await(10, TimeUnit.SECONDS));
            awaitDoneOrParked(reader, readerThread.get());
            releaseWriter.countDown();
            writer.get(10, TimeUnit.SECONDS);

            List<JsonNode> items = reader.get(10, TimeUnit.SECONDS).items();
            assertEquals(versionOf(items.get(0)), versionOf(items.get(1)),
                    () -> "TransactGetItems saw a TransactWriteItems half applied: " + items);
            assertEquals("1", versionOf(items.get(0)));
        } finally {
            releaseWriter.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "thread pool did not terminate");
        }
    }

    @RepeatedTest(REPEATS)
    void concurrent_transactGetItems_sees_transactWriteItems_all_or_nothing() throws InterruptedException {
        createCounterTable("us-east-1");
        seedVersionedPair();
        AtomicInteger threadId = new AtomicInteger();
        AtomicInteger versionSource = new AtomicInteger();
        List<String> tornReads = Collections.synchronizedList(new ArrayList<>());

        List<Throwable> errors = runConcurrently(THREADS, () -> {
            boolean writer = threadId.getAndIncrement() % 2 == 0;
            for (int i = 0; i < OPS_PER_SCENARIO; i++) {
                if (writer) {
                    ObjectNode exprValues = mapper.createObjectNode();
                    exprValues.set(":v", numberAttr(String.valueOf(versionSource.incrementAndGet())));
                    service.transactWriteItems(
                            List.of(setVersionTx("txA", exprValues), setVersionTx("txB", exprValues)),
                            "us-east-1", null, null);
                } else {
                    List<JsonNode> items = service.transactGetItems(
                            List.of(getTx("txA"), getTx("txB")), "us-east-1").items();
                    if (!versionOf(items.get(0)).equals(versionOf(items.get(1)))) {
                        tornReads.add(versionOf(items.get(0)) + "/" + versionOf(items.get(1)));
                    }
                }
            }
        });

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);
        assertTrue(tornReads.isEmpty(),
                () -> "TransactGetItems saw half-applied transactions (txA/txB versions): " + tornReads);
    }

    /** An item store whose next put, once armed, parks with its table snapshot until released. */
    private static final class ParkingItemStore extends InMemoryStorage<String, Map<String, JsonNode>> {
        private final AtomicBoolean parkNextPut = new AtomicBoolean();
        private final CountDownLatch parked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void put(String key, Map<String, JsonNode> items) {
            if (parkNextPut.compareAndSet(true, false)) {
                parked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            super.put(key, items);
        }
    }

    private static void awaitDoneOrStuck(Future<?> task, Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!task.isDone() && System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.BLOCKED || state == Thread.State.WAITING) {
                return;
            }
            Thread.sleep(1);
        }
    }

    /** Runs {@code work} on {@code pool} and returns once it has finished or is stuck behind a lock. */
    private static Future<?> startAndAwaitDoneOrStuck(ExecutorService pool, Runnable work) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Thread> thread = new AtomicReference<>();
        Future<?> task = pool.submit(() -> {
            thread.set(Thread.currentThread());
            started.countDown();
            work.run();
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));
        awaitDoneOrStuck(task, thread.get());
        return task;
    }

    @RepeatedTest(REPEATS)
    void concurrent_putItem_persists_the_latest_table_snapshot() throws Exception {
        StorageBackend<String, TableDefinition> tableStore = new InMemoryStorage<>();
        ParkingItemStore itemStore = new ParkingItemStore();
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        service = new DynamoDbService(tableStore, itemStore, regionResolver);
        createCounterTable("us-east-1");
        itemStore.parkNextPut.set(true);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> service.putItem("Counters", itemWithPk("a"), "us-east-1"));
            assertTrue(itemStore.parked.await(10, TimeUnit.SECONDS), "the first write never persisted");
            Future<?> second = startAndAwaitDoneOrStuck(pool,
                    () -> service.putItem("Counters", itemWithPk("b"), "us-east-1"));
            itemStore.release.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            itemStore.release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "thread pool did not terminate");
        }

        assertEquals(2, itemStore.get("us-east-1::Counters").orElseThrow().size(),
                () -> "the stored table must hold both items: " + itemStore.get("us-east-1::Counters"));
        DynamoDbService restarted = new DynamoDbService(tableStore, itemStore, regionResolver);
        assertNotNull(restarted.getItem("Counters", pkKey("a"), "us-east-1"));
        assertNotNull(restarted.getItem("Counters", pkKey("b"), "us-east-1"),
                "a write that finished must survive a restart");
    }

    @RepeatedTest(REPEATS)
    void concurrent_putItem_store_holds_every_acknowledged_write() throws Exception {
        StorageBackend<String, TableDefinition> tableStore = new InMemoryStorage<>();
        StorageBackend<String, Map<String, JsonNode>> itemStore = new InMemoryStorage<>();
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        service = new DynamoDbService(tableStore, itemStore, regionResolver);
        createCounterTable("us-east-1");
        AtomicInteger idSource = new AtomicInteger();
        List<String> missingAfterAck = Collections.synchronizedList(new ArrayList<>());

        List<Throwable> errors = runConcurrently(THREADS, () -> {
            for (int i = 0; i < OPS_PER_SCENARIO; i++) {
                String pk = "acked-" + idSource.getAndIncrement();
                service.putItem("Counters", itemWithPk(pk), "us-east-1");
                // Once the write has returned, every table copy stored from then on must hold it.
                if (!itemStore.get("us-east-1::Counters").orElseThrow().containsKey(pk)) {
                    missingAfterAck.add(pk);
                }
            }
        });

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);
        assertTrue(missingAfterAck.isEmpty(),
                () -> "an older copy of the table was stored over a newer one, dropping: " + missingAfterAck);
        assertEquals(THREADS * OPS_PER_SCENARIO, itemStore.get("us-east-1::Counters").orElseThrow().size());
        DynamoDbService restarted = new DynamoDbService(tableStore, itemStore, regionResolver);
        for (int i = 0; i < THREADS * OPS_PER_SCENARIO; i++) {
            assertNotNull(restarted.getItem("Counters", pkKey("acked-" + i), "us-east-1"), "acked-" + i);
        }
    }

    /** A table store whose next get, once armed, reads the table and then parks until released. */
    private static final class ParkingTableStore extends InMemoryStorage<String, TableDefinition> {
        private final AtomicBoolean parkNextGet = new AtomicBoolean();
        private final CountDownLatch parked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Optional<TableDefinition> get(String key) {
            Optional<TableDefinition> table = super.get(key);
            if (parkNextGet.compareAndSet(true, false)) {
                parked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return table;
        }
    }

    @RepeatedTest(REPEATS)
    void write_that_passed_the_table_check_before_deleteTable_is_not_stored() throws Exception {
        ParkingTableStore tableStore = new ParkingTableStore();
        StorageBackend<String, Map<String, JsonNode>> itemStore = new InMemoryStorage<>();
        service = new DynamoDbService(tableStore, itemStore, new RegionResolver("us-east-1", "000000000000"));
        createCounterTable("us-east-1");
        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":v", numberAttr("1"));
        tableStore.parkNextGet.set(true);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> write = pool.submit(() -> service.updateItem("Counters", pkKey("late"), null,
                    "SET lateAttr = :v", null, exprValues, "NONE", "us-east-1"));
            assertTrue(tableStore.parked.await(10, TimeUnit.SECONDS), "the write never looked up its table");
            service.deleteTable("Counters", "us-east-1");
            tableStore.release.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> write.get(10, TimeUnit.SECONDS));
            AwsException cause = assertInstanceOf(AwsException.class, failure.getCause());
            assertEquals("ResourceNotFoundException", cause.getErrorCode());
        } finally {
            tableStore.release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "thread pool did not terminate");
        }

        assertTrue(itemStore.get("us-east-1::Counters").isEmpty(),
                () -> "a deleted table's items must not be stored: " + itemStore.get("us-east-1::Counters"));
    }

    /**
     * Parks {@code write} right after its first table lookup, deletes the table and creates it again
     * under the same name, then lets the write finish. Returns what the write threw, or null.
     */
    private Throwable failureOfAWriteOvertakenByARecreate(ParkingTableStore tableStore, Runnable write)
            throws Exception {
        tableStore.parkNextGet.set(true);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> pending = pool.submit(write);
            assertTrue(tableStore.parked.await(10, TimeUnit.SECONDS), "the write never looked up its table");
            service.deleteTable("Counters", "us-east-1");
            createCounterTable("us-east-1");
            tableStore.release.countDown();
            try {
                pending.get(10, TimeUnit.SECONDS);
                return null;
            } catch (ExecutionException e) {
                return e.getCause();
            }
        } finally {
            tableStore.release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "thread pool did not terminate");
        }
    }

    private void assertTheRecreatedTableHasNoLateItem(Throwable writeFailure,
                                                      StorageBackend<String, Map<String, JsonNode>> itemStore) {
        assertNull(service.getItem("Counters", pkKey("late"), "us-east-1"),
                "a write that looked up the deleted table must not reach the table created after it");
        Map<String, JsonNode> stored = itemStore.get("us-east-1::Counters").orElse(Map.of());
        assertTrue(stored.isEmpty(), () -> "the recreated table must not store the earlier write: " + stored);
        AwsException failure = assertInstanceOf(AwsException.class, writeFailure,
                "the write must fail as a write to a deleted table does");
        assertEquals("ResourceNotFoundException", failure.getErrorCode());
    }

    @RepeatedTest(REPEATS)
    void updateItem_that_looked_up_a_table_since_deleted_and_recreated_does_not_reach_the_new_table()
            throws Exception {
        ParkingTableStore tableStore = new ParkingTableStore();
        StorageBackend<String, Map<String, JsonNode>> itemStore = new InMemoryStorage<>();
        service = new DynamoDbService(tableStore, itemStore, new RegionResolver("us-east-1", "000000000000"));
        createCounterTable("us-east-1");
        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":v", numberAttr("1"));

        Throwable failure = failureOfAWriteOvertakenByARecreate(tableStore, () -> service.updateItem("Counters",
                pkKey("late"), null, "SET lateAttr = :v", null, exprValues, "NONE", "us-east-1"));

        assertTheRecreatedTableHasNoLateItem(failure, itemStore);
    }

    @RepeatedTest(REPEATS)
    void transactWriteItems_that_looked_up_a_table_since_deleted_and_recreated_does_not_reach_the_new_table()
            throws Exception {
        ParkingTableStore tableStore = new ParkingTableStore();
        StorageBackend<String, Map<String, JsonNode>> itemStore = new InMemoryStorage<>();
        service = new DynamoDbService(tableStore, itemStore, new RegionResolver("us-east-1", "000000000000"));
        createCounterTable("us-east-1");
        ObjectNode put = mapper.createObjectNode();
        put.put("TableName", "Counters");
        put.set("Item", pkKey("late"));
        ObjectNode member = mapper.createObjectNode();
        member.set("Put", put);

        Throwable failure = failureOfAWriteOvertakenByARecreate(tableStore,
                () -> service.transactWriteItems(List.of(member), "us-east-1", null, null));

        assertTheRecreatedTableHasNoLateItem(failure, itemStore);
    }

    @RepeatedTest(REPEATS)
    void putItem_persist_racing_deleteTable_does_not_bring_the_items_back() throws Exception {
        StorageBackend<String, TableDefinition> tableStore = new InMemoryStorage<>();
        ParkingItemStore itemStore = new ParkingItemStore();
        service = new DynamoDbService(tableStore, itemStore, new RegionResolver("us-east-1", "000000000000"));
        createCounterTable("us-east-1");
        itemStore.parkNextPut.set(true);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> write = pool.submit(() -> service.putItem("Counters", itemWithPk("a"), "us-east-1"));
            assertTrue(itemStore.parked.await(10, TimeUnit.SECONDS), "the write never persisted");
            Future<?> delete = startAndAwaitDoneOrStuck(pool, () -> service.deleteTable("Counters", "us-east-1"));
            itemStore.release.countDown();
            write.get(10, TimeUnit.SECONDS);
            delete.get(10, TimeUnit.SECONDS);
        } finally {
            itemStore.release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "thread pool did not terminate");
        }

        assertTrue(itemStore.get("us-east-1::Counters").isEmpty(),
                () -> "a deleted table's items must not be stored again: " + itemStore.get("us-east-1::Counters"));
    }

    @Test
    void baseline_single_threaded_still_works() {
        String region = "eu-west-1";
        createCounterTable(region);

        ObjectNode key = pkKey("single");
        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.set(":start", numberAttr("0"));
        exprValues.set(":inc", numberAttr("1"));

        for (int i = 1; i <= 10; i++) {
            service.updateItem("Counters", key, null,
                    "SET cnt = if_not_exists(cnt, :start) + :inc",
                    null, exprValues, "ALL_NEW", region);
        }

        JsonNode stored = service.getItem("Counters", key, region);
        assertNotNull(stored);
        assertEquals("10", stored.get("cnt").get("N").asText());
    }
}
