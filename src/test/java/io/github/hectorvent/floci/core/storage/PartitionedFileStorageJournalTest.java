package io.github.hectorvent.floci.core.storage;

import io.github.hectorvent.floci.testing.FileSystemAssumptions;
import io.github.hectorvent.floci.testing.ThreadStates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** {@link PartitionedFileStorage} in {@link PartitionedFileStorage.Mode#JOURNAL} mode. */
class PartitionedFileStorageJournalTest {

    private static final FileTime LONG_AGO = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));

    @TempDir
    Path tempDir;

    private final List<PartitionedFileStorage<String>> opened = new ArrayList<>();

    @AfterEach
    void tearDown() {
        opened.forEach(PartitionedFileStorage::shutdown);
    }

    /** Opens the store the way a restart after a crash would: whatever an earlier instance left. */
    private PartitionedFileStorage<String> open(int shards) {
        PartitionedFileStorage<String> storage = new PartitionedFileStorage<>(tempDir.resolve("items"),
                tempDir.resolve("items.json"), String.class, shards, PartitionedFileStorage.Mode.JOURNAL,
                Long.MAX_VALUE);
        storage.load();
        opened.add(storage);
        return storage;
    }

    private Path journal() {
        return tempDir.resolve("items").resolve(PartitionedFileStorage.JOURNAL_FILE);
    }

    private long shardFileCount() throws Exception {
        try (Stream<Path> files = Files.list(tempDir.resolve("items"))) {
            return files.filter(f -> f.getFileName().toString().endsWith(".json")).count();
        }
    }

    @Test
    void aWriteCostsOneRecordWhateverTheSizeOfItsKey() throws Exception {
        PartitionedFileStorage<String> storage = open(4);
        for (int i = 0; i < 100; i++) {
            storage.putEntry("big", "entry-" + i, "value");
        }
        long before = Files.size(journal());
        storage.putEntry("big", "entry-x", "value");
        long costAtOneHundred = Files.size(journal()) - before;

        for (int i = 100; i < 10_000; i++) {
            storage.putEntry("big", "entry-" + i, "value");
        }
        before = Files.size(journal());
        storage.putEntry("big", "entry-y", "value");
        long costAtTenThousand = Files.size(journal()) - before;

        assertTrue(costAtOneHundred > 0);
        assertEquals(costAtOneHundred, costAtTenThousand);
        assertEquals(0, shardFileCount(), "shard files are only written by compaction");
    }

    @Test
    void aRestartWithoutCompactionReplaysEveryChange() {
        PartitionedFileStorage<String> crashed = open(4);
        crashed.put("table", Map.of("a", "1", "b", "2", "c", "3"));
        crashed.putEntry("table", "d", "4");
        crashed.removeEntry("table", "a");
        crashed.putEntry("gone", "x", "1");
        crashed.delete("gone");

        PartitionedFileStorage<String> restarted = open(4);

        assertEquals(Map.of("b", "2", "c", "3", "d", "4"), restarted.get("table").orElseThrow());
        assertTrue(restarted.get("gone").isEmpty());
    }

    @Test
    void aCompactionBeforeTheFirstLoadKeepsTheJournal() {
        PartitionedFileStorage<String> crashed = open(1);
        crashed.putEntry("table", "a", "1");

        // The compaction schedule starts with the constructor, so it can run before load().
        PartitionedFileStorage<String> restarting = new PartitionedFileStorage<>(tempDir.resolve("items"),
                tempDir.resolve("items.json"), String.class, 1, PartitionedFileStorage.Mode.JOURNAL, Long.MAX_VALUE);
        opened.add(restarting);
        restarting.flush();
        restarting.load();

        assertEquals(Map.of("a", "1"), restarting.get("table").orElseThrow());
    }

    @Test
    void aRecordThatDecodesToNullEndsReplayInsteadOfFailingLoad() throws Exception {
        byte[] cborNull = {(byte) 0xF6};
        byte[] mapWithNullValue = {(byte) 0xA1, 0x61, 'k', (byte) 0xF6};
        List<byte[]> badRecords = List.of(
                record(EntryJournal.OP_PUT_ENTRY, "table".getBytes(), "k".getBytes(), cborNull),
                record(EntryJournal.OP_PUT, "table".getBytes(), cborNull),
                record(EntryJournal.OP_PUT, "table".getBytes(), mapWithNullValue));
        open(1).putEntry("table", "a", "1");

        for (byte[] bad : badRecords) {
            long goodLength = Files.size(journal());
            Files.write(journal(), bad, StandardOpenOption.APPEND);

            PartitionedFileStorage<String> restarted = open(1);

            assertEquals(Map.of("a", "1"), restarted.get("table").orElseThrow());
            assertEquals(goodLength, Files.size(journal()), "the unreadable record is cut off");
        }
    }

    @Test
    void aRecordWithAKeyThatIsNotUtf8EndsReplay() throws Exception {
        byte[] cborX = {0x61, 'x'};
        byte[] notUtf8 = {'t', 'a', 'b', 'l', (byte) 0xFF};
        List<byte[]> badRecords = List.of(
                record(EntryJournal.OP_PUT_ENTRY, notUtf8, "k".getBytes(), cborX),
                record(EntryJournal.OP_PUT_ENTRY, "table".getBytes(), new byte[]{(byte) 0xC3, 0x28}, cborX));
        open(1).putEntry("table", "a", "1");

        for (byte[] bad : badRecords) {
            long goodLength = Files.size(journal());
            Files.write(journal(), bad, StandardOpenOption.APPEND);

            PartitionedFileStorage<String> restarted = open(1);

            assertEquals(Set.of("table"), restarted.keys());
            assertEquals(Map.of("a", "1"), restarted.get("table").orElseThrow());
            assertEquals(goodLength, Files.size(journal()), "the unreadable record is cut off");
        }
    }

    /** One journal record: the op byte, then each part as a length-prefixed frame. */
    private static byte[] record(byte op, byte[]... parts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(op);
        for (byte[] part : parts) {
            out.writeInt(part.length);
            out.write(part);
        }
        out.flush();
        return bytes.toByteArray();
    }

    @Test
    void compactionRewritesOnlyChangedShardsAndEmptiesTheJournal() throws Exception {
        PartitionedFileStorage<String> storage = open(8);
        Map<String, String> entries = new HashMap<>();
        for (int i = 0; i < 200; i++) {
            entries.put("entry-" + i, "value-" + i);
        }
        storage.put("big", entries);
        storage.flush();
        assertEquals(8, shardFileCount());
        assertEquals(0, Files.size(journal()));
        try (Stream<Path> files = Files.list(tempDir.resolve("items"))) {
            for (Path file : files.toList()) {
                Files.setLastModifiedTime(file, LONG_AGO);
            }
        }
        Path touched = storage.fileFor("big", "entry-7");

        storage.putEntry("big", "entry-7", "changed");
        storage.flush();

        try (Stream<Path> files = Files.list(tempDir.resolve("items"))) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                assertEquals(file.equals(touched), !LONG_AGO.equals(Files.getLastModifiedTime(file)),
                        file.getFileName().toString());
            }
        }
        assertEquals(0, Files.size(journal()));
        entries.put("entry-7", "changed");
        assertEquals(entries, open(8).get("big").orElseThrow());
    }

    @Test
    void anIdleCompactionWritesNothing() throws Exception {
        PartitionedFileStorage<String> storage = open(1);
        storage.putEntry("table", "a", "1");
        storage.flush();
        Path file = storage.fileFor("table");
        Files.setLastModifiedTime(file, LONG_AGO);

        storage.flush();

        assertEquals(LONG_AGO, Files.getLastModifiedTime(file));
    }

    @Test
    void aTornTailIsCutAndLaterRecordsSurvive() throws Exception {
        PartitionedFileStorage<String> crashed = open(1);
        crashed.putEntry("table", "a", "1");
        crashed.putEntry("table", "b", "2");
        Files.write(journal(), new byte[]{0x01, 0x00, 0x00, 0x7f}, StandardOpenOption.APPEND);

        PartitionedFileStorage<String> restarted = open(1);
        assertEquals(Map.of("a", "1", "b", "2"), restarted.get("table").orElseThrow());
        restarted.putEntry("table", "c", "3");

        assertEquals(Map.of("a", "1", "b", "2", "c", "3"), open(1).get("table").orElseThrow());
    }

    @Test
    void replayOverShardFilesAppliesOnlyTheNewerChanges() {
        PartitionedFileStorage<String> storage = open(2);
        storage.put("table", Map.of("a", "1", "b", "2"));
        storage.flush();
        storage.putEntry("table", "a", "changed");
        storage.removeEntry("table", "b");

        assertEquals(Map.of("a", "changed"), open(2).get("table").orElseThrow());
    }

    @Test
    void clearRemovesTheJournalAndEveryFile() throws Exception {
        PartitionedFileStorage<String> storage = open(2);
        storage.put("table", Map.of("a", "1"));
        storage.flush();
        storage.putEntry("table", "b", "2");

        storage.clear();

        assertEquals(0, shardFileCount());
        assertTrue(open(2).keys().isEmpty());
    }

    @Test
    void shutdownCompacts() throws Exception {
        PartitionedFileStorage<String> storage = open(1);
        storage.putEntry("table", "a", "1");

        storage.shutdown();
        opened.remove(storage);

        assertEquals(0, Files.size(journal()));
        assertEquals(1, shardFileCount());
    }

    @Test
    void aWriteRacingCompactionIsNeverLost() throws Exception {
        PartitionedFileStorage<String> live = open(1);
        live.putEntry("table", "early", "1");
        Thread[] writer = new Thread[1];
        live.setBeforeJournalTruncate(() -> {
            // The shards are written and the journal is about to be emptied. A write now must
            // wait for the compaction to finish, or its journal record is dropped unwritten.
            writer[0] = Thread.ofPlatform().start(() -> live.putEntry("table", "late", "2"));
            ThreadStates.awaitParkedOrDone(writer[0]);
        });

        live.flush();
        live.setBeforeJournalTruncate(() -> { });
        writer[0].join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(writer[0].isAlive());
        assertEquals(Map.of("early", "1", "late", "2"), recoverCopy().get("table").orElseThrow());
    }

    @Test
    void twoWritesToOneEntryReplayInTheOrderTheyWereApplied() throws Exception {
        PartitionedFileStorage<String> live = open(1);
        Thread[] second = new Thread[1];
        live.setBeforeJournalAppend(() -> {
            // The first write is in memory and about to be journaled. A second write to the same
            // entry must wait, or its record lands first and a replay ends on the older value.
            live.setBeforeJournalAppend(() -> { });
            second[0] = Thread.ofPlatform().start(() -> live.putEntry("table", "a", "second"));
            ThreadStates.awaitParkedOrDone(second[0]);
        });

        live.putEntry("table", "a", "first");
        second[0].join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(second[0].isAlive());
        assertEquals(Map.of("a", "second"), live.get("table").orElseThrow());
        assertEquals(Map.of("a", "second"), recoverCopy().get("table").orElseThrow());
    }

    /**
     * What a restart would recover right now, read from a copy of the directory so that opening
     * it, which replays and later compacts, cannot change the files the live store is using.
     */
    private PartitionedFileStorage<String> recoverCopy() throws Exception {
        Path copy = tempDir.resolve("recovered");
        Files.createDirectories(copy);
        try (Stream<Path> files = Files.list(tempDir.resolve("items"))) {
            for (Path file : files.toList()) {
                Files.copy(file, copy.resolve(file.getFileName()));
            }
        }
        PartitionedFileStorage<String> recovered = new PartitionedFileStorage<>(copy, (Path) null, String.class, 1,
                PartitionedFileStorage.Mode.JOURNAL, Long.MAX_VALUE);
        recovered.load();
        opened.add(recovered);
        return recovered;
    }

    @Test
    void aChangeTheJournalCannotTakeIsCompactedIntoItsShardAtOnce() throws Exception {
        // An empty directory where the journal goes: it cannot be opened, so the first append
        // fails, while the compaction that follows can remove it and start a real journal.
        Files.createDirectories(journal());
        PartitionedFileStorage<String> storage = open(1);

        storage.putEntry("table", "a", "1");

        assertTrue(Files.exists(storage.fileFor("table")), "the change reached its shard file");
        assertTrue(Files.isRegularFile(journal()));
        storage.putEntry("table", "b", "2");
        assertTrue(Files.size(journal()) > 0, "later changes are journaled again");
        assertEquals(Map.of("a", "1", "b", "2"), recoverCopy().get("table").orElseThrow());
    }

    @Test
    void aWriteAfterATornAppendIsNeverAcknowledgedBehindIt() throws Exception {
        PartitionedFileStorage<String> crashed = open(1);
        crashed.putEntry("table", "a", "1");
        // A non-empty directory where the shard file goes makes every fallback compaction fail.
        Path blocker = crashed.fileFor("table");
        Files.createDirectories(blocker);
        Files.writeString(blocker.resolve("child"), "x");
        crashed.setBeforeJournalWrite(file -> {
            // The write fails after part of the record reached the file.
            crashed.setBeforeJournalWrite(ignored -> { });
            Files.write(file, new byte[]{EntryJournal.OP_PUT_ENTRY, 0x00, 0x00, 0x7f}, StandardOpenOption.APPEND);
            throw new IOException("disk full");
        });

        assertThrows(UncheckedIOException.class, () -> crashed.putEntry("table", "b", "2"));
        assertThrows(UncheckedIOException.class, () -> crashed.putEntry("table", "c", "3"),
                "a record appended behind the torn one would be lost on replay, so it is not acknowledged");

        Files.delete(blocker.resolve("child"));
        Files.delete(blocker);
        crashed.putEntry("table", "d", "4");

        assertEquals(Map.of("a", "1", "b", "2", "c", "3", "d", "4"), open(1).get("table").orElseThrow());
    }

    @Test
    void aJournalThatCannotBeReadFailsLoadAndIsNeverCompactedAway() throws Exception {
        PartitionedFileStorage<String> crashed = open(1);
        crashed.putEntry("table", "a", "1");
        FileSystemAssumptions.assumePosixPermissions(journal());
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(journal());
        PartitionedFileStorage<String> restarting = new PartitionedFileStorage<>(tempDir.resolve("items"),
                tempDir.resolve("items.json"), String.class, 1, PartitionedFileStorage.Mode.JOURNAL, Long.MAX_VALUE);
        opened.add(restarting);
        Files.setPosixFilePermissions(journal(), PosixFilePermissions.fromString("---------"));
        try {
            // A root user reads through the mode, so there is nothing to test there.
            assumeFalse(Files.isReadable(journal()), "the journal stays readable for this user");

            assertThrows(UncheckedIOException.class, restarting::load);
            restarting.flush();
        } finally {
            Files.setPosixFilePermissions(journal(), original);
        }

        assertTrue(Files.size(journal()) > 0, "the unreplayed records are kept");
        assertEquals(Map.of("a", "1"), open(1).get("table").orElseThrow());
    }

    @ParameterizedTest
    @EnumSource(value = PartitionedFileStorage.Mode.class, names = {"SYNC", "PERIODIC"})
    void anotherModeWritesALeftoverJournalIntoItsShardsAndRemovesIt(PartitionedFileStorage.Mode mode) {
        PartitionedFileStorage<String> crashed = open(1);
        crashed.put("table", Map.of("a", "1", "b", "2"));
        crashed.flush();
        crashed.putEntry("table", "a", "journaled");

        PartitionedFileStorage<String> switched = new PartitionedFileStorage<>(tempDir.resolve("items"),
                tempDir.resolve("items.json"), String.class, 1, mode, Long.MAX_VALUE);
        opened.add(switched);
        switched.load();

        assertEquals(Map.of("a", "journaled", "b", "2"), switched.get("table").orElseThrow());
        assertFalse(Files.exists(journal()), "the journal is folded into the shard files and removed");
        switched.putEntry("table", "a", "newer");
        switched.flush();

        // Back in journal mode, nothing stale is replayed over the newer shard file.
        assertEquals(Map.of("a", "newer", "b", "2"), open(1).get("table").orElseThrow());
    }

    @Test
    void aShardThatCannotBeWrittenKeepsTheJournal() throws Exception {
        PartitionedFileStorage<String> crashed = open(1);
        crashed.putEntry("table", "a", "1");
        // A non-empty directory where the shard file goes makes only that write fail.
        Path blocker = crashed.fileFor("table");
        Files.createDirectories(blocker);
        Files.writeString(blocker.resolve("child"), "x");

        assertThrows(UncheckedIOException.class, crashed::flush, "a shard left unwritten is reported");

        assertTrue(Files.size(journal()) > 0, "the journal still holds the unwritten change");
        Files.delete(blocker.resolve("child"));
        Files.delete(blocker);
        assertEquals(Map.of("a", "1"), open(1).get("table").orElseThrow());
    }

    @Test
    void aJournalThatCannotBeEmptiedStaysOpenForLaterChanges() throws Exception {
        PartitionedFileStorage<String> crashed = open(1);
        crashed.putEntry("table", "a", "1");
        Path directory = tempDir.resolve("items");
        FileSystemAssumptions.assumePosixPermissions(directory);
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(directory);
        crashed.setBeforeJournalTruncate(() -> {
            // The shards are written; now make emptying the journal fail.
            try {
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        try {
            UncheckedIOException failure = null;
            try {
                crashed.flush();
            } catch (UncheckedIOException e) {
                failure = e;
            }
            // A root user writes through the read-only mode, so there is nothing to test there.
            assumeFalse(Files.isWritable(directory), "the directory stays writable for this user");
            assertNotNull(failure, "a journal left unemptied is reported");
        } finally {
            crashed.setBeforeJournalTruncate(() -> { });
            Files.setPosixFilePermissions(directory, original);
        }

        crashed.putEntry("table", "b", "2");

        assertEquals(Map.of("a", "1", "b", "2"), open(1).get("table").orElseThrow());
    }
}
