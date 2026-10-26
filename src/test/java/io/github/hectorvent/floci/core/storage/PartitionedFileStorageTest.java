package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.FileSystemAssumptions;
import io.github.hectorvent.floci.testing.ThreadStates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class PartitionedFileStorageTest {

    private static final FileTime LONG_AGO = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));

    @TempDir
    Path tempDir;

    private final List<PartitionedFileStorage<String>> opened = new ArrayList<>();

    @AfterEach
    void tearDown() {
        opened.forEach(PartitionedFileStorage::shutdown);
    }

    private PartitionedFileStorage<String> open(PartitionedFileStorage.Mode mode) {
        return open(1, mode);
    }

    private PartitionedFileStorage<String> open(int shards, PartitionedFileStorage.Mode mode) {
        PartitionedFileStorage<String> storage = new PartitionedFileStorage<>(tempDir.resolve("items"),
                tempDir.resolve("items.json"), String.class, shards, mode, 60_000);
        storage.load();
        opened.add(storage);
        return storage;
    }

    private Set<String> fileNames() throws Exception {
        try (Stream<Path> files = Files.list(tempDir.resolve("items"))) {
            return files.map(f -> f.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    private static Map<String, String> entries(int count) {
        Map<String, String> entries = new HashMap<>();
        for (int i = 0; i < count; i++) {
            entries.put("entry-" + i, "value-" + i);
        }
        return entries;
    }

    @Test
    void anEntryWriteRewritesOnlyItsOwnShardFile() throws Exception {
        PartitionedFileStorage<String> storage = open(8, PartitionedFileStorage.Mode.SYNC);
        storage.put("big", entries(200));
        assertEquals(8, fileNames().size());
        for (String name : fileNames()) {
            Files.setLastModifiedTime(tempDir.resolve("items").resolve(name), LONG_AGO);
        }
        Path touched = storage.fileFor("big", "entry-7");

        storage.putEntry("big", "entry-7", "changed");
        storage.removeEntry("big", "entry-7");
        storage.putEntry("big", "entry-7", "changed-again");

        for (String name : fileNames()) {
            Path file = tempDir.resolve("items").resolve(name);
            assertEquals(file.equals(touched), !LONG_AGO.equals(Files.getLastModifiedTime(file)), name);
        }
        Map<String, String> expected = entries(200);
        expected.put("entry-7", "changed-again");
        assertEquals(expected, open(8, PartitionedFileStorage.Mode.SYNC).get("big").orElseThrow());
    }

    @Test
    void anEmptyShardHasNoFile() throws Exception {
        PartitionedFileStorage<String> storage = open(16, PartitionedFileStorage.Mode.SYNC);
        storage.putEntry("small", "only", "1");
        assertEquals(Set.of(storage.fileFor("small", "only").getFileName().toString()), fileNames());

        storage.removeEntry("small", "only");

        assertTrue(fileNames().isEmpty());
    }

    @Test
    void deletingAKeyRemovesEveryShardFile() throws Exception {
        PartitionedFileStorage<String> storage = open(4, PartitionedFileStorage.Mode.PERIODIC);
        storage.put("gone", entries(50));
        storage.put("kept", entries(1));
        storage.flush();
        Set<String> keptFiles = Set.of(storage.fileFor("kept", "entry-0").getFileName().toString());

        storage.delete("gone");
        storage.flush();

        assertEquals(keptFiles, fileNames());
    }

    @Test
    void changingTheShardCountKeepsEveryEntryAndDropsTheOldFiles() throws Exception {
        open(1, PartitionedFileStorage.Mode.SYNC).put("big", entries(100));

        PartitionedFileStorage<String> four = open(4, PartitionedFileStorage.Mode.SYNC);
        assertEquals(entries(100), four.get("big").orElseThrow());
        assertEquals(4, fileNames().size());
        assertTrue(fileNames().stream().allMatch(name -> name.contains("-of-4")), fileNames().toString());

        PartitionedFileStorage<String> two = open(2, PartitionedFileStorage.Mode.PERIODIC);
        assertEquals(entries(100), two.get("big").orElseThrow());
        assertEquals(2, fileNames().size());
        assertTrue(fileNames().stream().allMatch(name -> name.contains("-of-2")), fileNames().toString());
        assertEquals(entries(100), open(2, PartitionedFileStorage.Mode.SYNC).get("big").orElseThrow());
    }

    @Test
    void aShardCountBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PartitionedFileStorage<>(tempDir.resolve("items"),
                (Path) null, String.class, 0, PartitionedFileStorage.Mode.SYNC, 60_000));
    }

    @Test
    void syncEntryWriteRewritesOnlyItsOwnKeysFile() throws Exception {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);
        storage.put("big", Map.of("a", "1", "b", "2"));
        storage.putEntry("busy", "node-1", "beat-1");
        Path bigFile = storage.fileFor("big");
        Files.setLastModifiedTime(bigFile, LONG_AGO);

        storage.putEntry("busy", "node-1", "beat-2");
        storage.removeEntry("busy", "node-1");
        storage.putEntry("busy", "node-2", "beat-1");

        assertEquals(LONG_AGO, Files.getLastModifiedTime(bigFile));
        PartitionedFileStorage<String> reloaded = open(PartitionedFileStorage.Mode.SYNC);
        assertEquals(Map.of("node-2", "beat-1"), reloaded.get("busy").orElseThrow());
        assertEquals(Map.of("a", "1", "b", "2"), reloaded.get("big").orElseThrow());
    }

    @Test
    void periodicStoreWritesOnlyDirtyKeysOnFlush() throws Exception {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.PERIODIC);
        storage.put("big", Map.of("a", "1"));
        storage.putEntry("busy", "node-1", "beat-1");
        assertFalse(Files.exists(storage.fileFor("big")), "a periodic store writes nothing before a flush");

        storage.flush();
        Path bigFile = storage.fileFor("big");
        Files.setLastModifiedTime(bigFile, LONG_AGO);
        storage.putEntry("busy", "node-1", "beat-2");
        storage.flush();

        assertEquals(LONG_AGO, Files.getLastModifiedTime(bigFile));
        assertEquals(Map.of("node-1", "beat-2"), open(PartitionedFileStorage.Mode.PERIODIC).get("busy").orElseThrow());
    }

    @Test
    void aFlushThatFailsIsRetriedOnTheNextFlush() throws Exception {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.PERIODIC);
        storage.putEntry("other", "a", "1");
        storage.flush();
        storage.putEntry("busy", "node-1", "beat-1");
        Path directory = tempDir.resolve("items");
        FileSystemAssumptions.assumePosixPermissions(directory);
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            // A root user writes through the read-only mode, so there is nothing to test there.
            assumeFalse(Files.isWritable(directory), "the directory stays writable for this user");
            assertThrows(UncheckedIOException.class, storage::flush, "a failed flush is reported");
            assertFalse(Files.exists(storage.fileFor("busy")));
        } finally {
            Files.setPosixFilePermissions(directory, original);
        }

        storage.flush();

        assertEquals(Map.of("node-1", "beat-1"), open(PartitionedFileStorage.Mode.PERIODIC).get("busy").orElseThrow());
    }

    @Test
    void aSyncWriteThatCannotReachDiskFailsAndIsWrittenByTheNextFlush() throws Exception {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);
        Path file = storage.fileFor("table");
        // A non-empty directory where the write's temporary file goes makes that write fail.
        Path blocker = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectories(blocker);
        Files.writeString(blocker.resolve("child"), "x");

        assertThrows(UncheckedIOException.class, () -> storage.putEntry("table", "a", "1"));

        assertEquals(Map.of("a", "1"), storage.get("table").orElseThrow(), "the change stays in memory");
        Files.delete(blocker.resolve("child"));
        Files.delete(blocker);
        storage.flush();
        assertEquals(Map.of("a", "1"), open(PartitionedFileStorage.Mode.SYNC).get("table").orElseThrow());
    }

    @Test
    void deletingAKeyRemovesItsFile() {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);
        storage.put("gone", Map.of("a", "1"));
        Path file = storage.fileFor("gone");
        assertTrue(Files.exists(file));

        storage.delete("gone");

        assertFalse(Files.exists(file));
        assertTrue(open(PartitionedFileStorage.Mode.SYNC).get("gone").isEmpty());
    }

    @Test
    void removingAnEntryOfAMissingKeyDoesNotCreateIt() {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);
        storage.removeEntry("missing", "a");
        assertTrue(storage.get("missing").isEmpty());
        assertFalse(Files.exists(storage.fileFor("missing")));
    }

    @Test
    void keysOfAnyLengthAndCharacterMapToSafeDistinctFiles() {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);
        String longKey = "000000000000/us-east-1::" + "t".repeat(255);
        String otherLongKey = "000000000000/us-east-1::" + "t".repeat(254) + "u";
        storage.put(longKey, Map.of("a", "1"));
        storage.put(otherLongKey, Map.of("a", "2"));
        storage.put("../escape", Map.of("a", "3"));

        assertNotEquals(storage.fileFor(longKey), storage.fileFor(otherLongKey));
        assertTrue(storage.fileFor(longKey).getFileName().toString().length() < 120);
        assertEquals(tempDir.resolve("items"), storage.fileFor("../escape").getParent());
        PartitionedFileStorage<String> reloaded = open(PartitionedFileStorage.Mode.SYNC);
        assertEquals("1", reloaded.get(longKey).orElseThrow().get("a"));
        assertEquals("2", reloaded.get(otherLongKey).orElseThrow().get("a"));
        assertEquals("3", reloaded.get("../escape").orElseThrow().get("a"));
    }

    @Test
    void firstLoadMigratesTheLegacySingleFile() throws Exception {
        Path legacy = tempDir.resolve("items.json");
        new ObjectMapper().writeValue(legacy.toFile(), Map.of(
                "000000000000/us-east-1::a", Map.of("k1", "v1"),
                "000000000000/us-east-1::b", Map.of("k2", "v2")));

        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.PERIODIC);

        assertEquals(Map.of("k1", "v1"), storage.get("000000000000/us-east-1::a").orElseThrow());
        assertFalse(Files.exists(legacy));
        assertTrue(Files.exists(tempDir.resolve("items.json.migrated")));
        assertTrue(Files.exists(storage.fileFor("000000000000/us-east-1::b")),
                "migration writes the files at once, not on the next flush");
        assertEquals(Map.of("k2", "v2"),
                open(PartitionedFileStorage.Mode.SYNC).get("000000000000/us-east-1::b").orElseThrow());
    }

    @Test
    void aMigrationThatCrashedInStagingIsRedoneInFull() throws Exception {
        Path legacy = tempDir.resolve("items.json");
        new ObjectMapper().writeValue(legacy.toFile(), Map.of("a", Map.of("k", "1"), "b", Map.of("k", "2")));
        // What a crash mid-migration leaves: one key staged, nothing moved in place.
        Path staging = Files.createDirectories(tempDir.resolve("items" + PartitionedFileStorage.MIGRATING_SUFFIX));
        Files.writeString(staging.resolve("a.json"), "{\"key\":\"a\",\"entries\":{\"k\":\"1\"}}");

        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);

        assertEquals(Map.of("k", "1"), storage.get("a").orElseThrow());
        assertEquals(Map.of("k", "2"), storage.get("b").orElseThrow());
        assertFalse(Files.exists(staging));
        assertFalse(Files.exists(legacy));
    }

    @Test
    void anEmptyDirectoryLeftBehindStillMigratesTheLegacyFile() throws Exception {
        Path legacy = tempDir.resolve("items.json");
        new ObjectMapper().writeValue(legacy.toFile(), Map.of("a", Map.of("k", "1")));
        Files.createDirectories(tempDir.resolve("items"));
        Files.createFile(tempDir.resolve("items").resolve(PartitionedFileStorage.JOURNAL_FILE));

        assertEquals(Map.of("k", "1"), open(PartitionedFileStorage.Mode.SYNC).get("a").orElseThrow());
        assertTrue(Files.exists(tempDir.resolve("items.json.migrated")));
    }

    @Test
    void aMigrationThatCannotWriteFailsAndKeepsTheLegacyFile() throws Exception {
        Path legacy = tempDir.resolve("items.json");
        new ObjectMapper().writeValue(legacy.toFile(), Map.of("a", Map.of("k", "1")));
        FileSystemAssumptions.assumePosixPermissions(tempDir);
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(tempDir);
        Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            // A root user writes through the read-only mode, so there is nothing to test there.
            assumeFalse(Files.isWritable(tempDir), "the directory stays writable for this user");
            PartitionedFileStorage<String> storage = new PartitionedFileStorage<>(tempDir.resolve("items"),
                    legacy, String.class, 4, PartitionedFileStorage.Mode.SYNC, 60_000);

            assertThrows(UncheckedIOException.class, storage::load);
            storage.shutdown();
        } finally {
            Files.setPosixFilePermissions(tempDir, original);
        }
        assertTrue(Files.exists(legacy));
        assertFalse(Files.exists(tempDir.resolve("items")));
        assertEquals(Map.of("k", "1"), open(4, PartitionedFileStorage.Mode.SYNC).get("a").orElseThrow());
    }

    @Test
    void aLegacyFileIsIgnoredOnceTheDirectoryHoldsData() throws Exception {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);
        storage.put("a", Map.of("k", "new"));
        Path legacy = tempDir.resolve("items.json");
        new ObjectMapper().writeValue(legacy.toFile(), Map.of("a", Map.of("k", "old"), "b", Map.of("k", "old")));

        PartitionedFileStorage<String> reloaded = open(PartitionedFileStorage.Mode.SYNC);

        assertEquals(Map.of("k", "new"), reloaded.get("a").orElseThrow());
        assertTrue(reloaded.get("b").isEmpty());
        assertTrue(Files.exists(legacy), "a legacy file that was not migrated is left alone");
    }

    @Test
    void aSupersededFileThatCannotBeRewrittenStopsTheLoad() throws Exception {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);
        storage.put("key", Map.of("a", "1", "b", "2"));
        Path current = storage.fileFor("key");
        Path old = current.resolveSibling("old-name.json");
        Files.move(current, old);
        // A non-empty directory where the rewrite's temporary file goes makes the rewrite fail.
        Path blocker = current.resolveSibling(current.getFileName() + ".tmp");
        Files.createDirectories(blocker);
        Files.writeString(blocker.resolve("child"), "x");

        PartitionedFileStorage<String> blocked = new PartitionedFileStorage<>(tempDir.resolve("items"),
                tempDir.resolve("items.json"), String.class, 1, PartitionedFileStorage.Mode.SYNC, 60_000);
        opened.add(blocked);
        // Running on would let later writes change only the current file, and the next load
        // would merge the old file back in and revive what they removed.
        assertThrows(IllegalStateException.class, blocked::load);

        assertTrue(Files.exists(old));
        Files.delete(blocker.resolve("child"));
        Files.delete(blocker);
        assertEquals(Map.of("a", "1", "b", "2"), open(PartitionedFileStorage.Mode.SYNC).get("key").orElseThrow());
        assertFalse(Files.exists(old));
    }

    @Test
    void aSupersededFileThatCannotBeRemovedStopsTheLoad() throws Exception {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);
        storage.put("key", Map.of("a", "1", "b", "2"));
        Path current = storage.fileFor("key");
        Path old = current.resolveSibling("old-name.json");
        Files.move(current, old);
        Path directory = tempDir.resolve("items");
        FileSystemAssumptions.assumePosixPermissions(directory);
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(directory);

        PartitionedFileStorage<String> blocked = new PartitionedFileStorage<>(directory,
                tempDir.resolve("items.json"), String.class, 1, PartitionedFileStorage.Mode.SYNC, 60_000);
        opened.add(blocked);
        blocked.setBeforeSupersededDelete(() -> {
            // The keys are rewritten under their current names; now make removing the old file fail.
            try {
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        try {
            Throwable failure = assertThrows(RuntimeException.class, blocked::load);
            // A root user deletes through the read-only mode, so there is nothing to test there.
            assumeFalse(Files.isWritable(directory), "the directory stays writable for this user");
            // Running on would let later writes change only the current file, and the next load
            // would merge the old file back in and revive what they removed.
            assertInstanceOf(UncheckedIOException.class, failure);
            assertTrue(Files.exists(current), "the rewrite landed before the removal failed");
            assertTrue(Files.exists(old));
        } finally {
            Files.setPosixFilePermissions(directory, original);
        }

        assertEquals(Map.of("a", "1", "b", "2"), open(PartitionedFileStorage.Mode.SYNC).get("key").orElseThrow());
        assertFalse(Files.exists(old));
    }

    @Test
    void aWriteThatReadItsShardBeforeAClearDoesNotBringItBack() throws Exception {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.PERIODIC);
        storage.putEntry("table", "a", "1");
        Thread[] clearer = new Thread[1];
        storage.setBeforeShardWrite(() -> {
            // The shard is read and its file is about to be written: a clear now must wait for
            // the write, or the write puts the cleared file back.
            storage.setBeforeShardWrite(() -> { });
            clearer[0] = Thread.ofPlatform().start(storage::clear);
            ThreadStates.awaitParkedOrDone(clearer[0]);
        });

        storage.flush();
        clearer[0].join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(clearer[0].isAlive());
        assertTrue(open(PartitionedFileStorage.Mode.SYNC).get("table").isEmpty());
    }

    @Test
    void anUnreadableFileIsQuarantinedAndTheRestStillLoad() throws Exception {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);
        storage.put("good", Map.of("a", "1"));
        Files.writeString(tempDir.resolve("items").resolve("broken-0000000000000000.json"), "{not json");

        PartitionedFileStorage<String> reloaded = open(PartitionedFileStorage.Mode.SYNC);

        assertEquals(Map.of("a", "1"), reloaded.get("good").orElseThrow());
        try (Stream<Path> files = Files.list(tempDir.resolve("items"))) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().equals("broken-0000000000000000.json")));
        }
    }

    @Test
    void aFileUnderAnOldNameIsRewrittenUnderItsCurrentName() throws Exception {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);
        storage.put("key", Map.of("a", "1"));
        Path current = storage.fileFor("key");
        Path old = current.resolveSibling("old-name.json");
        Files.move(current, old);

        PartitionedFileStorage<String> reloaded = open(PartitionedFileStorage.Mode.SYNC);

        assertEquals(Map.of("a", "1"), reloaded.get("key").orElseThrow());
        assertTrue(Files.exists(current));
        assertFalse(Files.exists(old));
    }

    @Test
    void clearRemovesEveryFile() throws Exception {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);
        storage.put("a", Map.of("x", "1"));
        storage.put("b", Map.of("x", "2"));

        storage.clear();

        try (Stream<Path> files = Files.list(tempDir.resolve("items"))) {
            assertEquals(0, files.count());
        }
        assertTrue(open(PartitionedFileStorage.Mode.SYNC).keys().isEmpty());
    }
}
