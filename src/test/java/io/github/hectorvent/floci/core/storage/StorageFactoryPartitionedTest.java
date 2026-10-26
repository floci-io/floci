package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.testing.FileSystemAssumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** How {@link StorageFactory#createPartitioned} maps each storage mode onto a backend. */
class StorageFactoryPartitionedTest {

    private static final TypeReference<Map<String, Map<String, String>>> TYPE = new TypeReference<>() {};

    @TempDir
    Path tempDir;

    @Test
    void persistentModeWritesOneFilePerKeyAsItChanges() {
        StorageFactory factory = newFactory("persistent");
        AccountAwareStorageBackend<Map<String, String>> store =
                factory.createPartitioned("svc", "svc-items.json", TYPE, String.class, 1);

        assertTrue(store.writesEntries());
        store.putEntry("table-a", "k", "v");
        store.putEntry("table-b", "k", "v");

        assertEquals(2, fileCount(tempDir.resolve("svc-items")));
        assertFalse(Files.exists(tempDir.resolve("svc-items.json")));
        factory.shutdownAll();
    }

    @Test
    void hybridModeWritesDirtyKeysOnFlushAndShutdown() {
        StorageFactory factory = newFactory("hybrid");
        AccountAwareStorageBackend<Map<String, String>> store =
                factory.createPartitioned("svc", "svc-items.json", TYPE, String.class, 1);

        store.putEntry("table-a", "k", "v");
        assertEquals(0, fileCount(tempDir.resolve("svc-items")));

        factory.shutdownAll();
        assertEquals(1, fileCount(tempDir.resolve("svc-items")));
        AccountAwareStorageBackend<Map<String, String>> reopened = newFactory("hybrid")
                .createPartitioned("svc", "svc-items.json", TYPE, String.class, 1);
        assertEquals(Map.of("k", "v"), reopened.get("table-a").orElseThrow());
    }

    @Test
    void anExistingSingleFileIsMigratedOnFirstUse() throws Exception {
        new ObjectMapper().writeValue(tempDir.resolve("svc-items.json").toFile(),
                Map.of("000000000000/table-a", Map.of("k", "v")));

        AccountAwareStorageBackend<Map<String, String>> store = newFactory("persistent")
                .createPartitioned("svc", "svc-items.json", TYPE, String.class, 1);

        assertEquals(Map.of("k", "v"), store.get("table-a").orElseThrow());
        assertTrue(Files.exists(tempDir.resolve("svc-items.json.migrated")));
    }

    @Test
    void walModeJournalsChangesAndCompactsOnShutdown() {
        StorageFactory factory = newFactory("wal");
        AccountAwareStorageBackend<Map<String, String>> store =
                factory.createPartitioned("svc", "svc-items.json", TYPE, String.class, 1);

        store.putEntry("table-a", "k", "v");
        assertEquals(0, fileCount(tempDir.resolve("svc-items")));
        assertTrue(Files.exists(tempDir.resolve("svc-items").resolve(PartitionedFileStorage.JOURNAL_FILE)));

        factory.shutdownAll();
        assertEquals(1, fileCount(tempDir.resolve("svc-items")));
        assertEquals(Map.of("k", "v"), newFactory("wal")
                .createPartitioned("svc", "svc-items.json", TYPE, String.class, 1).get("table-a").orElseThrow());
    }

    @Test
    void walModeMigratesALegacyWalStoreIncludingItsUncompactedLog() {
        WalStorage<String, Map<String, String>> legacy = new WalStorage<>(tempDir.resolve("svc-items-snapshot.json"),
                tempDir.resolve("svc-items.wal"), TYPE, Long.MAX_VALUE);
        legacy.load();
        legacy.put("000000000000/table-a", Map.of("k", "v"));
        legacy.flush();
        legacy.put("000000000000/table-b", Map.of("k", "only-in-the-log"));

        AccountAwareStorageBackend<Map<String, String>> store = newFactory("wal")
                .createPartitioned("svc", "svc-items.json", TYPE, String.class, 1);

        assertEquals(Map.of("k", "v"), store.get("table-a").orElseThrow());
        assertEquals(Map.of("k", "only-in-the-log"), store.get("table-b").orElseThrow());
        assertFalse(Files.exists(tempDir.resolve("svc-items.wal")));
        assertTrue(Files.exists(tempDir.resolve("svc-items-snapshot.json.migrated")));
    }

    @Test
    void hybridModeMigratesALegacyWalStoreIncludingItsUncompactedLog() {
        WalStorage<String, Map<String, String>> legacy = new WalStorage<>(tempDir.resolve("svc-items-snapshot.json"),
                tempDir.resolve("svc-items.wal"), TYPE, Long.MAX_VALUE);
        legacy.load();
        legacy.put("000000000000/table-a", Map.of("k", "v"));
        legacy.flush();
        legacy.put("000000000000/table-b", Map.of("k", "only-in-the-log"));

        AccountAwareStorageBackend<Map<String, String>> store = newFactory("hybrid")
                .createPartitioned("svc", "svc-items.json", TYPE, String.class, 1);

        assertEquals(Map.of("k", "v"), store.get("table-a").orElseThrow());
        assertEquals(Map.of("k", "only-in-the-log"), store.get("table-b").orElseThrow());
        assertTrue(Files.exists(tempDir.resolve("svc-items.wal.migrated")));
    }

    @Test
    void anEmptyDirectoryLeftBehindStillMigratesALegacyWalStore() throws Exception {
        WalStorage<String, Map<String, String>> legacy = new WalStorage<>(tempDir.resolve("svc-items-snapshot.json"),
                tempDir.resolve("svc-items.wal"), TYPE, Long.MAX_VALUE);
        legacy.load();
        legacy.put("000000000000/table-a", Map.of("k", "v"));
        legacy.shutdown();
        // What a crash right after the directory was created leaves behind.
        Files.createDirectories(tempDir.resolve("svc-items"));

        AccountAwareStorageBackend<Map<String, String>> store = newFactory("wal")
                .createPartitioned("svc", "svc-items.json", TYPE, String.class, 1);

        assertEquals(Map.of("k", "v"), store.get("table-a").orElseThrow());
    }

    @Test
    void theLegacyStoreWrittenLastIsTheOneMigrated() throws Exception {
        WalStorage<String, Map<String, String>> legacy = new WalStorage<>(tempDir.resolve("svc-items-snapshot.json"),
                tempDir.resolve("svc-items.wal"), TYPE, Long.MAX_VALUE);
        legacy.load();
        legacy.put("000000000000/table-a", Map.of("k", "from-wal"));
        legacy.shutdown();
        Path plain = tempDir.resolve("svc-items.json");
        new ObjectMapper().writeValue(plain.toFile(), Map.of("000000000000/table-a", Map.of("k", "from-plain")));
        FileTime older = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));
        Files.setLastModifiedTime(tempDir.resolve("svc-items-snapshot.json"), older);
        Files.setLastModifiedTime(tempDir.resolve("svc-items.wal"), older);

        AccountAwareStorageBackend<Map<String, String>> store = newFactory("wal")
                .createPartitioned("svc", "svc-items.json", TYPE, String.class, 1);

        assertEquals(Map.of("k", "from-plain"), store.get("table-a").orElseThrow());
        assertTrue(Files.exists(tempDir.resolve("svc-items-snapshot.json")), "the older store is left alone");
    }

    @Test
    void aMigrationThatCannotWriteLeavesTheLegacyLogInPlace() throws Exception {
        FileSystemAssumptions.assumePosixPermissions(tempDir);
        WalStorage<String, Map<String, String>> legacy = new WalStorage<>(tempDir.resolve("svc-items-snapshot.json"),
                tempDir.resolve("svc-items.wal"), TYPE, Long.MAX_VALUE);
        legacy.load();
        legacy.put("000000000000/table-a", Map.of("k", "only-in-the-log"));
        Path log = tempDir.resolve("svc-items.wal");
        byte[] logged = Files.readAllBytes(log);
        // A non-empty file where the staging directory goes makes the migration's first write fail.
        Path staging = tempDir.resolve("svc-items" + PartitionedFileStorage.MIGRATING_SUFFIX);
        Files.createDirectories(staging.resolve("blocker"));
        Files.setPosixFilePermissions(staging, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeFalse(Files.isWritable(staging), "the directory stays writable for this user");
            assertThrows(UncheckedIOException.class, () -> newFactory("wal")
                    .createPartitioned("svc", "svc-items.json", TYPE, String.class, 1));
        } finally {
            Files.setPosixFilePermissions(staging, PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        assertArrayEquals(logged, Files.readAllBytes(log));
        assertEquals(Map.of("k", "only-in-the-log"), newFactory("wal")
                .createPartitioned("svc", "svc-items.json", TYPE, String.class, 1).get("table-a").orElseThrow());
        legacy.shutdown();
    }

    @Test
    void aBackendThatFailsToFlushDoesNotStopTheOthersShuttingDown() throws Exception {
        StorageFactory factory = newFactory("hybrid");
        AccountAwareStorageBackend<Map<String, String>> failing =
                factory.createPartitioned("svc", "svc-a.json", TYPE, String.class, 1);
        AccountAwareStorageBackend<Map<String, String>> healthy =
                factory.createPartitioned("svc", "svc-b.json", TYPE, String.class, 1);
        failing.putEntry("table", "k", "v");
        healthy.putEntry("table", "k", "v");
        Path blocker = tempDir.resolve("svc-a")
                .resolve(PartitionedFileStorage.fileNameFor("000000000000/table", 0, 1) + ".tmp");
        Files.createDirectories(blocker.resolve("child"));

        assertThrows(UncheckedIOException.class, factory::shutdownAll);

        assertEquals(1, fileCount(tempDir.resolve("svc-b")));
    }

    @Test
    void memoryModeKeepsTheSingleValueStore() {
        AccountAwareStorageBackend<Map<String, String>> store = newFactory("memory")
                .createPartitioned("svc", "svc-items.json", TYPE, String.class, 1);

        assertFalse(store.writesEntries());
        store.put("table-a", Map.of("k", "v"));
        assertFalse(Files.exists(tempDir.resolve("svc-items")));
    }

    @Test
    void entriesOfTwoAccountsUnderOneKeyStaySeparate() {
        StorageFactory factory = newFactory("persistent");
        AccountAwareStorageBackend<Map<String, String>> store =
                factory.createPartitioned("svc", "svc-items.json", TYPE, String.class, 4);

        store.putEntryForAccount("111111111111", "table", "k", "one");
        store.putEntryForAccount("222222222222", "table", "k", "two");
        store.putEntryForAccount("222222222222", "table", "gone", "x");
        store.removeEntryForAccount("111111111111", "table", "gone");
        store.removeEntryForAccount("222222222222", "table", "gone");
        factory.shutdownAll();

        AccountAwareStorageBackend<Map<String, String>> reopened = newFactory("persistent")
                .createPartitioned("svc", "svc-items.json", TYPE, String.class, 4);
        assertEquals(Map.of(
                "111111111111/table", Map.of("k", "one"),
                "222222222222/table", Map.of("k", "two")), reopened.scanAllAccountsRaw());
    }

    @Test
    void repeatCallsShareOneBackend() {
        StorageFactory factory = newFactory("persistent");
        assertSame(factory.createPartitioned("svc", "svc-items.json", TYPE, String.class, 1),
                factory.createPartitioned("svc", "svc-items.json", TYPE, String.class, 1));
    }

    private static long fileCount(Path directory) {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".json")).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private StorageFactory newFactory(String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.storage().persistentPath()).thenReturn(tempDir.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60_000L);
        ServiceConfigAccess serviceConfigAccess = mock(ServiceConfigAccess.class);
        when(serviceConfigAccess.storageMode(anyString())).thenReturn(mode);
        when(serviceConfigAccess.storageFlushInterval(anyString())).thenReturn(60_000L);
        return new StorageFactory(config, serviceConfigAccess);
    }
}
