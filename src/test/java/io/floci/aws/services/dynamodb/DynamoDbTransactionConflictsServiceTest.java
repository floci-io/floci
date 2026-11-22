package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The opt-in transaction conflict emulation at the service: what a write transaction's busy items reject
 * while it holds them, and that nothing is held once it answers or when it fails.
 */
class DynamoDbTransactionConflictsServiceTest {

    private static final String REGION = "us-east-1";
    private static final String TABLE = "Counters";

    private final ObjectMapper mapper = new ObjectMapper();
    // The marks never expire on their own here: a transaction's hold ends when the test releases it.
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final AtomicInteger holds = new AtomicInteger();
    private final CountDownLatch firstHolding = new CountDownLatch(1);
    private final CountDownLatch releaseFirst = new CountDownLatch(1);
    private DynamoDbService service;

    @BeforeEach
    void setUp() {
        service = new DynamoDbService(new InMemoryStorage<>());
        service.createTable(TABLE, List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")), 5L, 5L, REGION);
        // The first committed transaction stays inside its hold until the test lets it go; later ones answer at once.
        service.useTransactionConflicts(new DynamoDbTransactionConflicts(true, Duration.ofMillis(10), clock::get,
                (deadline, nanoTime) -> {
                    if (holds.incrementAndGet() == 1) {
                        firstHolding.countDown();
                        awaitQuietly(releaseFirst);
                    }
                }));
    }

    // Catches: requests that overlap a write transaction on its items waiting for it, as they do by default,
    // instead of failing the way AWS fails them, so a caller's TransactionConflict handling never runs.
    @Test
    void requestsOnTheItemsOfATransactionInFlightAreRejectedAndTheRestGoThrough() throws Exception {
        service.putItem(TABLE, item("hot", 0), REGION);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        Thread first = Thread.ofPlatform().start(() -> {
            try {
                service.transactWriteItems(List.of(increment("hot")), REGION);
            } catch (Throwable t) {
                firstFailure.set(t);
            }
        });
        assertTrue(firstHolding.await(10, TimeUnit.SECONDS), "the first transaction never reached its hold");

        TransactionCanceledException write = assertThrows(TransactionCanceledException.class,
                () -> service.transactWriteItems(List.of(put("other"), increment("hot")), REGION));
        assertEquals(List.of("", "TransactionConflict"), codes(write));
        assertEquals("Transaction is ongoing for the item", write.getCancellationReasons().get(1).message());
        assertEquals("Transaction cancelled, please refer cancellation reasons for specific reasons "
                + "[None, TransactionConflict]", write.getMessage());
        TransactionCanceledException read = assertThrows(TransactionCanceledException.class,
                () -> service.transactGetItems(List.of(get("other"), get("hot")), REGION));
        assertEquals(List.of("", "TransactionConflict"), codes(read));
        NativeDynamoDbJsonHandler handler = new NativeDynamoDbJsonHandler(service, null, null, mapper);
        Response wire = handler.handle("TransactWriteItems", json("{\"TransactItems\":[" + increment("hot") + "]}"), REGION);
        assertEquals(400, wire.getStatus());
        JsonNode reason = mapper.convertValue(wire.getEntity(), JsonNode.class).path("CancellationReasons").get(0);
        assertEquals("TransactionConflict", reason.path("Code").asText());
        assertEquals("Transaction is ongoing for the item", reason.path("Message").asText());
        Response wireRead = handler.handle("TransactGetItems", json("{\"TransactItems\":[" + get("hot") + "]}"), REGION);
        JsonNode readReason = mapper.convertValue(wireRead.getEntity(), JsonNode.class).path("CancellationReasons").get(0);
        assertEquals("TransactionConflict", readReason.path("Code").asText());
        assertEquals("Transaction is ongoing for the item", readReason.path("Message").asText());
        assertEquals("TransactionConflictException", assertThrows(AwsException.class, () -> handler.handle(
                "ExecuteStatement", json("{\"Statement\":\"UPDATE \\\"Counters\\\" SET n = 9 WHERE pk = 'hot'\"}"), REGION))
                .getErrorCode());
        assertEquals("TransactionConflictException", assertThrows(AwsException.class,
                () -> service.putItem(TABLE, item("hot", 9), REGION)).getErrorCode());
        // The conflict is reported before a condition that would fail too.
        assertEquals("TransactionConflictException", assertThrows(AwsException.class,
                () -> service.updateItem(TABLE, key("hot"), null, "SET n = :n", null, json("{\":n\":{\"N\":\"9\"}}"),
                        "NONE", "attribute_not_exists(pk)", REGION, "NONE")).getErrorCode());
        assertEquals("TransactionConflictException", assertThrows(AwsException.class,
                () -> service.deleteItem(TABLE, key("hot"), REGION)).getErrorCode());
        // A GetItem is not affected, and items the transaction does not hold are not either.
        assertNotNull(service.getItem(TABLE, key("hot"), REGION));
        service.putItem(TABLE, item("cold", 1), REGION);
        service.transactWriteItems(List.of(put("cold-too")), REGION);

        releaseFirst.countDown();
        first.join(TimeUnit.SECONDS.toMillis(10));
        assertNull(firstFailure.get());
        // What was rejected changed nothing; the transaction that held the item went through once.
        assertEquals("1", counter("hot"));
        assertNull(service.getItem(TABLE, key("other"), REGION));
    }

    // Catches: a caller that waits for each transaction before it sends the next being told of a conflict
    // with its own earlier, finished transaction.
    @Test
    void aTransactionSentAfterTheLastOneAnsweredGoesThrough() {
        service.putItem(TABLE, item("hot", 0), REGION);
        releaseFirst.countDown();

        service.transactWriteItems(List.of(increment("hot")), REGION);
        service.transactWriteItems(List.of(increment("hot")), REGION);
        service.putItem(TABLE, item("hot", 5), REGION);
        service.transactGetItems(List.of(get("hot")), REGION);

        assertEquals("5", counter("hot"));
    }

    // Catches: a transaction cancelled by its own condition keeping its items busy, so it blocks requests
    // although it changed nothing.
    @Test
    void aTransactionThatFailsHoldsNothing() {
        service.putItem(TABLE, item("hot", 0), REGION);

        assertThrows(TransactionCanceledException.class, () -> service.transactWriteItems(List.of(
                json("{\"Put\":{\"TableName\":\"Counters\",\"Item\":{\"pk\":{\"S\":\"hot\"}},"
                        + "\"ConditionExpression\":\"attribute_not_exists(pk)\"}}")), REGION));

        service.putItem(TABLE, item("hot", 1), REGION);
        assertEquals(0, holds.get());
    }

    // Catches: the issue's contention, many callers adding to one counter in transactions, losing or repeating
    // an increment, or failing in some way other than the TransactionConflict cancellation.
    @Test
    void contendingTransactionsEitherCommitOnceOrAreCancelledWithTransactionConflict() throws Exception {
        service.useTransactionConflicts(DynamoDbTransactionConflicts.of(true));
        service.putItem(TABLE, item("hot", 0), REGION);
        int threads = 12;
        int callsEach = 15;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        List<Future<?>> callers = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                callers.add(pool.submit(() -> {
                    awaitQuietly(start);
                    for (int call = 0; call < callsEach; call++) {
                        try {
                            service.transactWriteItems(List.of(increment("hot"), put(UUID.randomUUID().toString())), REGION);
                            committed.incrementAndGet();
                        } catch (TransactionCanceledException e) {
                            assertEquals("TransactionConflict", e.getCancellationReasons().get(0).code());
                            conflicted.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> caller : callers) {
                caller.get(60, TimeUnit.SECONDS);
            }
        }

        assertEquals(threads * callsEach, committed.get() + conflicted.get());
        assertEquals(String.valueOf(committed.get()), counter("hot"));
    }

    private static List<String> codes(TransactionCanceledException e) {
        return e.getCancellationReasons().stream().map(TransactionCanceledException.CancellationReason::code).toList();
    }

    private String counter(String pk) {
        return service.getItem(TABLE, key(pk), REGION).path("n").path("N").asText();
    }

    private JsonNode item(String pk, int n) {
        return json("{\"pk\":{\"S\":\"" + pk + "\"},\"n\":{\"N\":\"" + n + "\"}}");
    }

    private JsonNode key(String pk) {
        return json("{\"pk\":{\"S\":\"" + pk + "\"}}");
    }

    private JsonNode increment(String pk) {
        return json("{\"Update\":{\"TableName\":\"Counters\",\"Key\":{\"pk\":{\"S\":\"" + pk + "\"}},"
                + "\"UpdateExpression\":\"SET n = n + :one\",\"ExpressionAttributeValues\":{\":one\":{\"N\":\"1\"}}}}");
    }

    private JsonNode put(String pk) {
        return json("{\"Put\":{\"TableName\":\"Counters\",\"Item\":{\"pk\":{\"S\":\"" + pk + "\"}}}}");
    }

    private JsonNode get(String pk) {
        return json("{\"Get\":{\"TableName\":\"Counters\",\"Key\":{\"pk\":{\"S\":\"" + pk + "\"}}}}");
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch never opened");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
