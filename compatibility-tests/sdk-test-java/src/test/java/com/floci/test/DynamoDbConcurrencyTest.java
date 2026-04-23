package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end concurrency tests against a running Floci instance.
 *
 * <p>Covers the four scenarios that must match real DynamoDB's per-item
 * linearisability and transaction atomicity — see issue #571.
 *
 * <p>Each scenario uses a shared {@link CountDownLatch} starting gate so
 * client threads dispatch simultaneously, maximising contention at the server.
 */
@DisplayName("DynamoDB - Concurrency")
class DynamoDbConcurrencyTest {

    private static final String TABLE_NAME = "sdk-test-concurrency-table";
    private static final int THREADS = 20;

    private static DynamoDbClient ddb;

    @BeforeAll
    static void setup() {
        ddb = TestFixtures.dynamoDbClient();
        ddb.createTable(CreateTableRequest.builder()
                .tableName(TABLE_NAME)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .provisionedThroughput(ProvisionedThroughput.builder()
                        .readCapacityUnits(5L).writeCapacityUnits(5L).build())
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (ddb != null) {
            try {
                ddb.deleteTable(DeleteTableRequest.builder().tableName(TABLE_NAME).build());
            } catch (Exception ignored) {}
            ddb.close();
        }
    }

    @Test
    @DisplayName("UpdateItem arithmetic — 20 concurrent increments yield 20 distinct results")
    void concurrentUpdateItemArithmetic() throws InterruptedException {
        String pk = "arith-" + System.nanoTime();
        Set<Integer> observed = Collections.synchronizedSet(new HashSet<>());

        runConcurrently(THREADS, () -> {
            UpdateItemResponse response = ddb.updateItem(UpdateItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .key(Map.of("pk", AttributeValue.builder().s(pk).build()))
                    .updateExpression("SET cnt = if_not_exists(cnt, :start) + :inc")
                    .expressionAttributeValues(Map.of(
                            ":start", AttributeValue.builder().n("0").build(),
                            ":inc", AttributeValue.builder().n("1").build()))
                    .returnValues(ReturnValue.ALL_NEW)
                    .build());
            observed.add(Integer.parseInt(response.attributes().get("cnt").n()));
        });

        assertThat(observed)
                .as("each UpdateItem should return a distinct cnt under contention")
                .hasSize(THREADS);

        int finalCnt = Integer.parseInt(ddb.getItem(GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of("pk", AttributeValue.builder().s(pk).build()))
                .consistentRead(true)
                .build())
                .item().get("cnt").n());
        assertThat(finalCnt).isEqualTo(THREADS);
    }

    @Test
    @DisplayName("PutItem attribute_not_exists — exactly one of N concurrent attempts succeeds")
    void concurrentPutItemAttributeNotExists() throws InterruptedException {
        String pk = "unique-" + System.nanoTime();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conditionalFailures = new AtomicInteger();

        runConcurrently(THREADS, () -> {
            try {
                ddb.putItem(PutItemRequest.builder()
                        .tableName(TABLE_NAME)
                        .item(Map.of("pk", AttributeValue.builder().s(pk).build()))
                        .conditionExpression("attribute_not_exists(pk)")
                        .build());
                successes.incrementAndGet();
            } catch (ConditionalCheckFailedException e) {
                conditionalFailures.incrementAndGet();
            }
        });

        assertThat(successes.get())
                .as("exactly one concurrent PutItem(attribute_not_exists) must succeed")
                .isEqualTo(1);
        assertThat(conditionalFailures.get()).isEqualTo(THREADS - 1);
    }

    @Test
    @DisplayName("TransactWriteItems overlapping — winners see consistent state across items")
    void concurrentTransactWriteItemsOverlapping() throws InterruptedException {
        String pkA = "txA-" + System.nanoTime();
        String pkB = "txB-" + System.nanoTime();

        // Seed both keys at version=0.
        for (String pk : List.of(pkA, pkB)) {
            ddb.putItem(PutItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .item(Map.of(
                            "pk", AttributeValue.builder().s(pk).build(),
                            "version", AttributeValue.builder().n("0").build()))
                    .build());
        }

        AtomicInteger committed = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();

        runConcurrently(THREADS, () -> {
            int currentVersion = Integer.parseInt(ddb.getItem(GetItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .key(Map.of("pk", AttributeValue.builder().s(pkA).build()))
                    .consistentRead(true)
                    .build())
                    .item().get("version").n());
            int nextVersion = currentVersion + 1;

            Map<String, AttributeValue> exprValues = Map.of(
                    ":v0", AttributeValue.builder().n(String.valueOf(currentVersion)).build(),
                    ":v1", AttributeValue.builder().n(String.valueOf(nextVersion)).build());

            try {
                ddb.transactWriteItems(TransactWriteItemsRequest.builder()
                        .transactItems(
                                buildVersionUpdate(pkA, exprValues),
                                buildVersionUpdate(pkB, exprValues))
                        .build());
                committed.incrementAndGet();
            } catch (TransactionCanceledException e) {
                cancelled.incrementAndGet();
            }
        });

        int versionA = Integer.parseInt(ddb.getItem(GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of("pk", AttributeValue.builder().s(pkA).build()))
                .consistentRead(true)
                .build())
                .item().get("version").n());
        int versionB = Integer.parseInt(ddb.getItem(GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of("pk", AttributeValue.builder().s(pkB).build()))
                .consistentRead(true)
                .build())
                .item().get("version").n());

        assertThat(versionA)
                .as("pkA and pkB must end on the same version — transaction atomicity")
                .isEqualTo(versionB);
        assertThat(committed.get())
                .as("commit count must match observed version progress")
                .isEqualTo(versionA);
        assertThat(committed.get() + cancelled.get()).isEqualTo(THREADS);
    }

    private static TransactWriteItem buildVersionUpdate(String pk, Map<String, AttributeValue> exprValues) {
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(TABLE_NAME)
                        .key(Map.of("pk", AttributeValue.builder().s(pk).build()))
                        .updateExpression("SET version = :v1")
                        .conditionExpression("version = :v0")
                        .expressionAttributeValues(exprValues)
                        .build())
                .build();
    }

    @Test
    @DisplayName("UpdateItem + PutItem on same key — linearisable, no half-updated state")
    void concurrentMixedUpdateAndPut() throws InterruptedException {
        String pk = "mixed-" + System.nanoTime();
        AtomicInteger idSource = new AtomicInteger();

        runConcurrently(THREADS, () -> {
            int id = idSource.getAndIncrement();
            if (id % 2 == 0) {
                ddb.updateItem(UpdateItemRequest.builder()
                        .tableName(TABLE_NAME)
                        .key(Map.of("pk", AttributeValue.builder().s(pk).build()))
                        .updateExpression("SET cnt = if_not_exists(cnt, :start) + :inc")
                        .expressionAttributeValues(Map.of(
                                ":start", AttributeValue.builder().n("0").build(),
                                ":inc", AttributeValue.builder().n("1").build()))
                        .build());
            } else {
                ddb.putItem(PutItemRequest.builder()
                        .tableName(TABLE_NAME)
                        .item(Map.of(
                                "pk", AttributeValue.builder().s(pk).build(),
                                "writer", AttributeValue.builder().s("put-" + id).build()))
                        .build());
            }
        });

        Map<String, AttributeValue> finalItem = ddb.getItem(GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of("pk", AttributeValue.builder().s(pk).build()))
                .consistentRead(true)
                .build())
                .item();
        assertThat(finalItem).isNotNull();
        assertThat(finalItem.get("pk").s()).isEqualTo(pk);
        finalItem.forEach((name, value) -> assertThat(value)
                .as("attribute %s must not be null in final item", name)
                .isNotNull());
    }

    private static void runConcurrently(int threadCount, Runnable work) throws InterruptedException {
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
            assertThat(doneGate.await(60, TimeUnit.SECONDS))
                    .as("concurrent work did not complete within 60s")
                    .isTrue();
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertThat(errors)
                .as("no unexpected errors should be thrown")
                .isEmpty();
    }
}
