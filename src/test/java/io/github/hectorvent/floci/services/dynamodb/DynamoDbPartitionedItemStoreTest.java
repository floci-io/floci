package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PartitionedFileStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** DynamoDB items on a store that persists one table file at a time and one item at a time. */
class DynamoDbPartitionedItemStoreTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "111111111111";
    private static final FileTime LONG_AGO = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<PartitionedFileStorage<JsonNode>> opened = new ArrayList<>();
    private final RegionResolver resolver = new RegionResolver(REGION, ACCOUNT);

    @TempDir
    Path tempDir;

    private StorageBackend<String, TableDefinition> tableStore;
    private DynamoDbService service;

    @BeforeEach
    void setUp() {
        tableStore = AccountAwareStorageBackend.inMemory(ACCOUNT);
        service = new DynamoDbService(tableStore, openItemStore(), resolver);
        createTable(service, "Logs");
        createTable(service, "Nodes");
    }

    @AfterEach
    void tearDown() {
        opened.forEach(PartitionedFileStorage::shutdown);
    }

    @Test
    void writesToOneTableLeaveAnotherTablesFileUntouched() throws Exception {
        for (int i = 0; i < 50; i++) {
            service.putItem("Logs", item("entry-" + i, "payload"), REGION);
        }
        Path logsFile = tableFile("Logs");
        Files.setLastModifiedTime(logsFile, LONG_AGO);

        service.putItem("Nodes", item("node-1", "beat-1"), REGION);
        service.updateItem("Nodes", key("node-1"), null, "SET v = :v", null,
                mapper.createObjectNode().set(":v", attribute("beat-2")), "NONE", REGION);
        service.putItem("Nodes", item("node-2", "beat-1"), REGION);
        service.deleteItem("Nodes", key("node-2"), REGION);

        assertEquals(LONG_AGO, Files.getLastModifiedTime(logsFile));
        DynamoDbService restarted = restart();
        assertEquals("beat-2", value(restarted.getItem("Nodes", key("node-1"), REGION)));
        assertNull(restarted.getItem("Nodes", key("node-2"), REGION));
        assertEquals("payload", value(restarted.getItem("Logs", key("entry-49"), REGION)));
    }

    @Test
    void batchAndTransactWritesSurviveARestart() {
        service.putItem("Nodes", item("gone", "x"), REGION);
        service.batchWriteItem(Map.of("Nodes", List.of(
                putRequest(item("batch-1", "b1")),
                deleteRequest(key("gone")))), REGION);
        service.transactWriteItems(List.of(
                transactPut("Logs", item("tx-1", "t1")),
                transactPut("Nodes", item("tx-2", "t2"))), REGION);

        DynamoDbService restarted = restart();

        assertEquals("b1", value(restarted.getItem("Nodes", key("batch-1"), REGION)));
        assertNull(restarted.getItem("Nodes", key("gone"), REGION));
        assertEquals("t1", value(restarted.getItem("Logs", key("tx-1"), REGION)));
        assertEquals("t2", value(restarted.getItem("Nodes", key("tx-2"), REGION)));
    }

    @Test
    void anItemStoredUnderAnOlderKeyStaysDeletedAfterARestart() {
        AccountAwareStorageBackend<Map<String, JsonNode>> itemStore = openItemStore();
        itemStore.put(REGION + "::Nodes", Map.of("legacy-address-of-node-9", item("node-9", "old")));
        DynamoDbService rekeyed = new DynamoDbService(tableStore, itemStore, resolver);

        rekeyed.deleteItem("Nodes", key("node-9"), REGION);

        assertNull(restart().getItem("Nodes", key("node-9"), REGION));
    }

    @Test
    void deletingATableRemovesItsFile() throws Exception {
        service.putItem("Nodes", item("node-1", "beat-1"), REGION);
        assertTrue(Files.exists(tableFile("Nodes")));

        service.deleteTable("Nodes", REGION);

        try (Stream<Path> files = Files.list(tempDir.resolve("items"))) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().contains("Nodes")));
        }
    }

    @Test
    void expiredItemsTheSweeperRemovesStayRemovedAfterARestart() {
        service.updateTimeToLive("Nodes", "expires", true, REGION);
        ObjectNode expired = item("node-1", "beat-1");
        expired.set("expires", mapper.createObjectNode().put("N", "1"));
        service.putItem("Nodes", expired, REGION);
        service.putItem("Nodes", item("node-2", "beat-1"), REGION);

        service.deleteScannedItems(service.scanExpiredItems());

        DynamoDbService restarted = restart();
        assertNull(restarted.getItem("Nodes", key("node-1"), REGION));
        assertEquals("beat-1", value(restarted.getItem("Nodes", key("node-2"), REGION)));
    }

    @Test
    void aWriteRightAfterTheSweeperRemovesAnItemSurvivesARestart() {
        DynamoDbStreamService streams = mock(DynamoDbStreamService.class);
        DynamoDbService swept = new DynamoDbService(tableStore, openItemStore(), resolver, streams, null);
        swept.updateTimeToLive("Nodes", "expires", true, REGION);
        ObjectNode expired = item("node-1", "beat-1");
        expired.set("expires", mapper.createObjectNode().put("N", "1"));
        swept.putItem("Nodes", expired, REGION);
        // The REMOVE event fires after the sweeper drops the item and releases its lock: the
        // moment a new write of the same key can land before the removal reaches disk.
        doAnswer(invocation -> {
            swept.putItem("Nodes", item("node-1", "beat-2"), REGION);
            return null;
        }).when(streams).captureEvent(eq("REMOVE"), any(), any(), any(), any(), eq(true));

        swept.deleteScannedItems(swept.scanExpiredItems());

        assertEquals("beat-2", value(swept.getItem("Nodes", key("node-1"), REGION)));
        assertEquals("beat-2", value(restart().getItem("Nodes", key("node-1"), REGION)));
    }

    @Test
    void eachBatchItemIsSavedInsideItsOwnWrite() {
        DynamoDbStreamService streams = mock(DynamoDbStreamService.class);
        DynamoDbService batching = new DynamoDbService(tableStore, openItemStore(), resolver, streams, null);
        List<JsonNode> onDiskWhenTheSecondItemWasWritten = new ArrayList<>();
        // A save deferred until after the batch releases its item locks could read an item, lose
        // the race to a concurrent write of the same key, then save the stale copy over it. So by
        // the time the batch writes its second item, the first must already be on disk.
        doAnswer(invocation -> {
            JsonNode written = invocation.getArgument(2);
            if ("node-b".equals(written.get("id").get("S").asText())) {
                onDiskWhenTheSecondItemWasWritten.add(restart().getItem("Nodes", key("node-a"), REGION));
            }
            return null;
        }).when(streams).captureEvent(eq("INSERT"), any(), any(), any(), any());

        batching.batchWriteItem(Map.of("Nodes", List.of(
                putRequest(item("node-a", "a")),
                putRequest(item("node-b", "b")))), REGION);

        assertEquals(1, onDiskWhenTheSecondItemWasWritten.size());
        assertEquals("a", value(onDiskWhenTheSecondItemWasWritten.getFirst()));
    }

    @Test
    void aWriteToAShardedTableRewritesOneOfItsFiles() throws Exception {
        Path directory = tempDir.resolve("sharded");
        DynamoDbService sharded = new DynamoDbService(tableStore, openItemStore(directory, 8), resolver);
        for (int i = 0; i < 200; i++) {
            sharded.putItem("Logs", item("entry-" + i, "payload"), REGION);
        }
        List<Path> files;
        try (Stream<Path> listed = Files.list(directory)) {
            files = listed.toList();
        }
        assertEquals(8, files.size());
        for (Path file : files) {
            Files.setLastModifiedTime(file, LONG_AGO);
        }

        sharded.putItem("Logs", item("entry-200", "payload"), REGION);

        assertEquals(1, files.stream().filter(f -> !isUntouched(f)).count());
        DynamoDbService restarted = new DynamoDbService(tableStore, openItemStore(directory, 8), resolver);
        assertEquals(201, restarted.scan("Logs", null, null, null, null, null, null, REGION).items().size());
    }

    @Test
    void failedConditionalWritesLeaveTheTableFileUntouched() throws Exception {
        service.putItem("Nodes", item("node-1", "beat-1"), REGION);
        Path nodesFile = tableFile("Nodes");
        Files.setLastModifiedTime(nodesFile, LONG_AGO);

        assertThrows(AwsException.class, () -> service.putItem("Nodes", item("node-1", "beat-2"),
                "attribute_not_exists(id)", null, null, REGION, "NONE"));
        assertThrows(AwsException.class, () -> service.deleteItem("Nodes", key("node-1"),
                "attribute_not_exists(id)", null, null, REGION, "NONE"));
        assertThrows(AwsException.class, () -> service.updateItem("Nodes", key("node-1"), null,
                "SET v = :v", null, mapper.createObjectNode().set(":v", attribute("beat-3")), "NONE",
                "attribute_not_exists(id)", REGION, "NONE"));

        assertEquals(LONG_AGO, Files.getLastModifiedTime(nodesFile));
        assertEquals("beat-1", value(restart().getItem("Nodes", key("node-1"), REGION)));
    }

    @Test
    void aCancelledTransactionWritesNothing() throws Exception {
        service.putItem("Nodes", item("node-1", "beat-1"), REGION);
        Path nodesFile = tableFile("Nodes");
        Files.setLastModifiedTime(nodesFile, LONG_AGO);

        assertThrows(TransactionCanceledException.class, () -> service.transactWriteItems(List.of(
                transactPut("Nodes", item("node-2", "new")),
                transactConditionCheck("Nodes", key("missing"), "attribute_exists(id)")), REGION));

        assertEquals(LONG_AGO, Files.getLastModifiedTime(nodesFile));
        assertNull(restart().getItem("Nodes", key("node-2"), REGION));
    }

    @Test
    void aBatchToAShardedTableRewritesOnlyTheShardsItTouches() throws Exception {
        Path directory = tempDir.resolve("sharded");
        DynamoDbService sharded = new DynamoDbService(tableStore, openItemStore(directory, 8), resolver);
        for (int i = 0; i < 200; i++) {
            sharded.putItem("Logs", item("entry-" + i, "payload"), REGION);
        }
        List<Path> files;
        try (Stream<Path> listed = Files.list(directory)) {
            files = listed.toList();
        }
        for (Path file : files) {
            Files.setLastModifiedTime(file, LONG_AGO);
        }

        sharded.batchWriteItem(Map.of("Logs", List.of(
                putRequest(item("entry-200", "payload")),
                deleteRequest(key("entry-0")))), REGION);

        long rewritten = files.stream().filter(f -> !isUntouched(f)).count();
        assertTrue(rewritten >= 1 && rewritten <= 2, "rewrote " + rewritten + " of 8 shard files");
        DynamoDbService restarted = new DynamoDbService(tableStore, openItemStore(directory, 8), resolver);
        assertNull(restarted.getItem("Logs", key("entry-0"), REGION));
        assertEquals("payload", value(restarted.getItem("Logs", key("entry-200"), REGION)));
        assertEquals(200, restarted.scan("Logs", null, null, null, null, null, null, REGION).items().size());
    }

    @Test
    void aRestoredTableSurvivesARestart() {
        createTable(service, "Restored");

        service.finishRestore("Restored", List.of(item("a", "1"), item("b", "2")), REGION);

        DynamoDbService restarted = restart();
        assertEquals("1", value(restarted.getItem("Restored", key("a"), REGION)));
        assertEquals("2", value(restarted.getItem("Restored", key("b"), REGION)));
    }

    @Test
    void anUnknownItemLayoutIsRejected() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().services().dynamodb().itemLayout()).thenReturn("per-item");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DynamoDbService.itemStore(mock(StorageFactory.class), config));
        assertTrue(error.getMessage().contains("per-item"), error.getMessage());
    }

    @Test
    void aShardCountBelowOneIsRejected() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage().persistentPath()).thenReturn(tempDir.toString());
        when(config.storage().services().dynamodb().itemLayout()).thenReturn("per-table");
        when(config.storage().services().dynamodb().itemShards()).thenReturn(0);
        ServiceConfigAccess serviceConfigAccess = mock(ServiceConfigAccess.class);
        when(serviceConfigAccess.storageMode(anyString())).thenReturn("persistent");
        StorageFactory factory = new StorageFactory(config, serviceConfigAccess);

        assertThrows(IllegalArgumentException.class, () -> DynamoDbService.itemStore(factory, config));
    }

    private static boolean isUntouched(Path file) {
        try {
            return LONG_AGO.equals(Files.getLastModifiedTime(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private AccountAwareStorageBackend<Map<String, JsonNode>> openItemStore() {
        return openItemStore(tempDir.resolve("items"), 1);
    }

    private AccountAwareStorageBackend<Map<String, JsonNode>> openItemStore(Path directory, int shards) {
        PartitionedFileStorage<JsonNode> inner = new PartitionedFileStorage<>(directory, (Path) null,
                JsonNode.class, shards, PartitionedFileStorage.Mode.SYNC, 60_000);
        inner.load();
        opened.add(inner);
        return new AccountAwareStorageBackend<>(inner, null, ACCOUNT);
    }

    private DynamoDbService restart() {
        return new DynamoDbService(tableStore, openItemStore(), resolver);
    }

    private Path tableFile(String table) throws Exception {
        try (Stream<Path> files = Files.list(tempDir.resolve("items"))) {
            return files.filter(f -> f.getFileName().toString().contains(table)).findFirst().orElseThrow();
        }
    }

    private void createTable(DynamoDbService target, String name) {
        target.createTable(name, List.of(new KeySchemaElement("id", "HASH")),
                List.of(new AttributeDefinition("id", "S")), 5L, 5L, REGION);
    }

    private ObjectNode key(String id) {
        ObjectNode key = mapper.createObjectNode();
        key.set("id", attribute(id));
        return key;
    }

    private ObjectNode item(String id, String value) {
        ObjectNode item = key(id);
        item.set("v", attribute(value));
        return item;
    }

    private ObjectNode attribute(String value) {
        return mapper.createObjectNode().put("S", value);
    }

    private ObjectNode putRequest(ObjectNode item) {
        ObjectNode request = mapper.createObjectNode();
        request.putObject("PutRequest").set("Item", item);
        return request;
    }

    private ObjectNode deleteRequest(ObjectNode key) {
        ObjectNode request = mapper.createObjectNode();
        request.putObject("DeleteRequest").set("Key", key);
        return request;
    }

    private ObjectNode transactPut(String table, ObjectNode item) {
        ObjectNode request = mapper.createObjectNode();
        ObjectNode put = request.putObject("Put");
        put.put("TableName", table);
        put.set("Item", item);
        return request;
    }

    private ObjectNode transactConditionCheck(String table, ObjectNode key, String condition) {
        ObjectNode request = mapper.createObjectNode();
        ObjectNode check = request.putObject("ConditionCheck");
        check.put("TableName", table);
        check.set("Key", key);
        check.put("ConditionExpression", condition);
        return request;
    }

    private static String value(JsonNode item) {
        assertNotNull(item);
        return item.path("v").path("S").asText();
    }
}
