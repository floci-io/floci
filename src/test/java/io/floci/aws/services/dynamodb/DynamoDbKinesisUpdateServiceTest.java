package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.services.dynamodb.NativeDynamoDbTableService.CreateTableRequest;
import io.floci.aws.services.dynamodb.NativeDynamoDbTableService.TableSettings;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import io.floci.aws.services.dynamodb.model.KinesisStreamingDestination;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import io.floci.aws.services.kinesis.KinesisService;
import io.floci.aws.testing.StorageTestBackends;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DynamoDbKinesisUpdateServiceTest {

    private static final String REGION = "us-east-1";
    private static final String TABLE = "persistent-kinesis-update";
    private static final String STREAM = "arn:aws:kinesis:us-east-1:000000000000:stream/updates";
    private static final TypeReference<Map<String, TableDefinition>> TYPE = new TypeReference<>() { };

    enum Mode {
        MEMORY, PERSISTENT, HYBRID, WAL
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void updatedPrecisionAndOmittedUpdateSurviveTheStorageLifecycle(Mode mode, @TempDir Path directory) {
        StorageBackend<String, TableDefinition> storage = storage(mode, directory);
        try {
            DynamoDbStreamService streams = new DynamoDbStreamService(new ObjectMapper(), storage);
            DynamoDbService dynamoDb = new DynamoDbService(storage, null,
                    new RegionResolver(REGION, "000000000000"), streams, mock(KinesisStreamingForwarder.class));
            NativeDynamoDbTableService tables = new NativeDynamoDbTableService(
                    dynamoDb, streams, mock(KinesisService.class));
            TableDefinition created = tables.createTable(new CreateTableRequest(TABLE,
                    new ArrayList<>(List.of(new KeySchemaElement("id", "HASH"))),
                    new ArrayList<>(List.of(new AttributeDefinition("id", "S"))),
                    null, List.of(), List.of(), List.of(), Map.of(),
                    new TableSettings("PAY_PER_REQUEST", null, null, null, null, null, null, null, null), null),
                    "ACTIVE", REGION);
            tables.enableKinesisStreamingDestination(TABLE, STREAM, "MILLISECOND", REGION);
            storage.flush();
            tables.updateKinesisStreamingDestination(created.getTableArn(), STREAM, "MICROSECOND", REGION);
            tables.updateKinesisStreamingDestination(TABLE, STREAM, null, REGION);
            storage.flush();
            if (mode != Mode.MEMORY) {
                StorageTestBackends.close(storage);
                storage = storage(mode, directory);
            }
            TableDefinition reloaded = storage.get(REGION + "::" + TABLE).orElseThrow();
            assertEquals(1, reloaded.getKinesisStreamingDestinations().size());
            KinesisStreamingDestination destination = reloaded.findKinesisStreamingDestination(STREAM).orElseThrow();
            assertEquals("ACTIVE", destination.getDestinationStatus());
            assertEquals("MICROSECOND", destination.getApproximateCreationDateTimePrecision());
            assertFalse(reloaded.isStreamEnabled(), "changing Kinesis precision must not enable DynamoDB Streams");
        } finally {
            StorageTestBackends.close(storage);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"enable", "update", "disable"})
    void deletingDuringDestinationMutationCannotResurrectTheTable(String operation) throws Exception {
        PausingStorage storage = new PausingStorage();
        DynamoDbStreamService streams = new DynamoDbStreamService(new ObjectMapper(), storage);
        DynamoDbService dynamoDb = new DynamoDbService(storage, null,
                new RegionResolver(REGION, "000000000000"), streams, mock(KinesisStreamingForwarder.class));
        NativeDynamoDbTableService tables = new NativeDynamoDbTableService(
                dynamoDb, streams, mock(KinesisService.class));
        String tableArn = tables.createTable(new CreateTableRequest(TABLE,
                new ArrayList<>(List.of(new KeySchemaElement("id", "HASH"))),
                new ArrayList<>(List.of(new AttributeDefinition("id", "S"))),
                null, List.of(), List.of(), List.of(), Map.of(),
                new TableSettings("PAY_PER_REQUEST", null, null, null, null, null, null, null, null), null),
                "ACTIVE", REGION).getTableArn();
        if (!"enable".equals(operation)) {
            tables.enableKinesisStreamingDestination(TABLE, STREAM, "MILLISECOND", REGION);
        }
        storage.pauseWrites = true;
        AtomicReference<Thread> deletionThread = new AtomicReference<>();
        AtomicReference<Thread> mutationThread = new AtomicReference<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> mutation = executor.submit(() -> {
                mutationThread.set(Thread.currentThread());
                switch (operation) {
                    case "enable" -> tables.enableKinesisStreamingDestination(tableArn, STREAM, "MICROSECOND", REGION);
                    case "update" -> tables.updateKinesisStreamingDestination(tableArn, STREAM, "MICROSECOND", REGION);
                    case "disable" -> tables.disableKinesisStreamingDestination(tableArn, STREAM, REGION);
                    default -> throw new IllegalArgumentException(operation);
                }
            });
            try {
                assertTrue(storage.atWrite.await(5, TimeUnit.SECONDS), "mutation must reach its persistence point");
                Future<?> deletion = executor.submit(() -> {
                    deletionThread.set(Thread.currentThread());
                    dynamoDb.deleteTable(TABLE, REGION);
                });
                // Without the shared table lock, deletion completes here and the paused put restores
                // the deleted table. With it, deletion waits for the complete mutation before removing it.
                await().atMost(Duration.ofSeconds(5)).until(() -> {
                    if (deletion.isDone()) {
                        return true;
                    }
                    Thread deleting = deletionThread.get();
                    ThreadInfo info = deleting == null ? null
                            : ManagementFactory.getThreadMXBean().getThreadInfo(deleting.threadId());
                    return info != null && info.getLockOwnerId() == mutationThread.get().threadId();
                });
                storage.resume.countDown();
                mutation.get(5, TimeUnit.SECONDS);
                deletion.get(5, TimeUnit.SECONDS);
                assertTrue(dynamoDb.findTable(TABLE, REGION).isEmpty(), "a completed delete must leave no table");
            } finally {
                storage.resume.countDown();
            }
        }
    }

    private static final class PausingStorage extends InMemoryStorage<String, TableDefinition> {
        private final CountDownLatch atWrite = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private volatile boolean pauseWrites;

        @Override
        public void put(String key, TableDefinition value) {
            if (pauseWrites) {
                atWrite.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to resume the destination mutation");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            super.put(key, value);
        }
    }

    private static StorageBackend<String, TableDefinition> storage(Mode mode, Path directory) {
        StorageBackend<String, TableDefinition> storage = StorageTestBackends.create(
                mode.name().toLowerCase(Locale.ROOT), directory.resolve("tables.json"),
                directory.resolve("tables.wal"), TYPE, 60_000);
        storage.load();
        return storage;
    }
}
