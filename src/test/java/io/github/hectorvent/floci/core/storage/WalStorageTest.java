package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WalStorageTest {

    @TempDir
    Path tempDir;

    private WalStorage<String, String> storage;

    @BeforeEach
    void setUp() {
        Path snapshotPath = tempDir.resolve("snapshot.json");
        Path walPath = tempDir.resolve("data.wal");
        storage = new WalStorage<>(snapshotPath, walPath, new TypeReference<>() {}, 60000);
        storage.load();
    }

    @AfterEach
    void tearDown() {
        storage.shutdown();
    }

    @Test
    void putAndGet() {
        storage.put("key1", "value1");
        assertEquals("value1", storage.get("key1").orElseThrow());
    }

    @Test
    void getReturnsEmptyForMissingKey() {
        assertTrue(storage.get("nonexistent").isEmpty());
    }

    @Test
    void deleteRemovesEntry() {
        storage.put("key1", "value1");
        storage.delete("key1");
        assertTrue(storage.get("key1").isEmpty());
    }

    @Test
    void scanWithPredicate() {
        storage.put("a.1", "v1");
        storage.put("a.2", "v2");
        storage.put("b.1", "v3");

        var results = storage.scan(key -> key.startsWith("a."));
        assertEquals(2, results.size());
    }

    @Test
    void scanReturnsMutableList() {
        storage.put("a", "1");
        storage.put("b", "2");
        var result = storage.scan(key -> true);
        assertDoesNotThrow(() -> result.sort(String::compareTo));
        assertDoesNotThrow(() -> result.add("3"));
    }

    @Test
    void clearRemovesAllEntries() {
        storage.put("key1", "value1");
        storage.put("key2", "value2");
        storage.clear();
        assertTrue(storage.get("key1").isEmpty());
        assertTrue(storage.get("key2").isEmpty());
    }

    @Test
    void flushAndLoadRestoresData() {
        Path snapshotPath = tempDir.resolve("persist-snapshot.json");
        Path walPath = tempDir.resolve("persist-data.wal");

        var store1 = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 60000);
        store1.load();
        store1.put("key1", "value1");
        store1.put("key2", "value2");
        store1.flush();
        store1.shutdown();

        var store2 = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 60000);
        store2.load();
        assertEquals("value1", store2.get("key1").orElseThrow());
        assertEquals("value2", store2.get("key2").orElseThrow());
        store2.shutdown();
    }

    @Test
    void walReplayRestoresUncompactedWrites() {
        Path snapshotPath = tempDir.resolve("replay-snapshot.json");
        Path walPath = tempDir.resolve("replay-data.wal");

        var store1 = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 600000);
        store1.load();
        store1.put("key1", "value1");
        store1.put("key2", "value2");
        store1.delete("key1");

        // Load a second instance from the same WAL (before compaction)
        var store2 = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 600000);
        store2.load();
        assertTrue(store2.get("key1").isEmpty());
        assertEquals("value2", store2.get("key2").orElseThrow());

        store1.shutdown();
        store2.shutdown();
    }

    @Test
    void walWritesBinaryFormat() throws IOException {
        Path walPath = tempDir.resolve("binary-check.wal");
        Path snapshotPath = tempDir.resolve("binary-check-snapshot.json");

        var store = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 600000);
        store.load();
        store.put("k", "v");
        store.delete("d");

        // Read raw binary WAL and verify structure (CBOR-encoded payloads)
        ObjectMapper cborMapper = new ObjectMapper(new CBORFactory());
        try (DataInputStream in = new DataInputStream(Files.newInputStream(walPath))) {
            // First entry: PUT
            assertEquals(WalStorage.OP_PUT, in.readByte());
            int keyLen = in.readInt();
            assertTrue(keyLen > 0);
            byte[] keyBytes = in.readNBytes(keyLen);
            // CBOR short string "k" starts with 0x61 (major type 3, length 1)
            assertEquals((byte) 0x61, keyBytes[0], "CBOR string type byte expected");
            assertEquals("k", cborMapper.readValue(keyBytes, String.class));
            int valueLen = in.readInt();
            assertTrue(valueLen > 0);
            byte[] valueBytes = in.readNBytes(valueLen);
            assertEquals((byte) 0x61, valueBytes[0], "CBOR string type byte expected");
            assertEquals("v", cborMapper.readValue(valueBytes, String.class));

            // Second entry: DELETE
            assertEquals(WalStorage.OP_DELETE, in.readByte());
            int delKeyLen = in.readInt();
            byte[] delKeyBytes = in.readNBytes(delKeyLen);
            assertEquals((byte) 0x61, delKeyBytes[0], "CBOR string type byte expected");
            assertEquals("d", cborMapper.readValue(delKeyBytes, String.class));

            // No more data
            assertEquals(0, in.available());
        }

        store.shutdown();
    }

    @Test
    void truncatedWalEntryIsSkippedGracefully() throws IOException {
        Path walPath = tempDir.resolve("truncated.wal");
        Path snapshotPath = tempDir.resolve("truncated-snapshot.json");

        var store1 = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 600000);
        store1.load();
        store1.put("good", "data");
        store1.put("another", "entry");

        // Truncate the WAL file mid-entry (chop off last few bytes)
        long walSize = Files.size(walPath);
        try (RandomAccessFile raf = new RandomAccessFile(walPath.toFile(), "rw")) {
            raf.setLength(walSize - 3);
        }

        // Load a new instance — should recover the first complete entry
        var store2 = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 600000);
        store2.load();
        assertEquals("data", store2.get("good").orElseThrow());
        // The truncated second entry may or may not be recovered depending on where truncation hit

        store1.shutdown();
        store2.shutdown();
    }

    @Test
    void putAllEntriesAreReplayedByAFreshInstance() {
        Path snapshotPath = tempDir.resolve("batch-snapshot.json");
        Path walPath = tempDir.resolve("batch-data.wal");

        WalStorage<String, String> store1 = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 600000);
        store1.load();
        store1.put("key1", "old");
        store1.putAll(Map.of("key1", "new", "key2", "value2", "key3", "value3"));

        WalStorage<String, String> store2 = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 600000);
        store2.load();
        assertEquals("new", store2.get("key1").orElseThrow());
        assertEquals("value2", store2.get("key2").orElseThrow());
        assertEquals("value3", store2.get("key3").orElseThrow());

        store1.shutdown();
        store2.shutdown();
    }

    @Test
    void putAllOfAnEmptyBatchLeavesTheWalUntouched() throws IOException {
        Path walPath = tempDir.resolve("data.wal");
        storage.put("key1", "value1");
        long sizeBeforeEmptyBatch = Files.size(walPath);

        storage.putAll(Map.of());

        assertEquals(sizeBeforeEmptyBatch, Files.size(walPath));
    }

    @Test
    void flushOfAnUnchangedStoreLeavesTheSnapshotUntouched() throws IOException {
        Path snapshotPath = tempDir.resolve("idle-snapshot.json");
        Path walPath = tempDir.resolve("idle-data.wal");

        WalStorage<String, String> store = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 600000);
        store.load();
        store.put("key1", "value1");
        store.flush();

        // Nothing changed since the last snapshot, so a flush has nothing to write: a marker
        // placed in the file afterwards must survive it.
        Files.writeString(snapshotPath, "left alone");
        store.flush();
        assertEquals("left alone", Files.readString(snapshotPath));

        store.put("key2", "value2");
        store.flush();
        assertTrue(Files.readString(snapshotPath).contains("key2"));

        store.delete("key2");
        store.flush();
        assertFalse(Files.readString(snapshotPath).contains("key2"));

        store.shutdown();
    }

    @Test
    void entriesReplayedFromTheWalAreCompactedByTheNextFlush() throws IOException {
        Path snapshotPath = tempDir.resolve("replayed-snapshot.json");
        Path walPath = tempDir.resolve("replayed-data.wal");

        WalStorage<String, String> store1 = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 600000);
        store1.load();
        store1.put("key1", "value1");

        WalStorage<String, String> store2 = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 600000);
        store2.load();
        store2.flush();

        assertTrue(Files.readString(snapshotPath).contains("key1"));
        assertEquals(0L, Files.size(walPath));

        store1.shutdown();
        store2.shutdown();
    }

    @Test
    void appendsAfterATornTailAreReplayedOnTheNextLoad() throws IOException {
        Path walPath = tempDir.resolve("torn.wal");
        Path snapshotPath = tempDir.resolve("torn-snapshot.json");

        WalStorage<String, String> store1 = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 600000);
        store1.load();
        store1.put("good", "data");
        store1.put("another", "entry");
        try (RandomAccessFile raf = new RandomAccessFile(walPath.toFile(), "rw")) {
            raf.setLength(Files.size(walPath) - 3);
        }

        // A restart after the crash keeps running and appends behind the torn record.
        WalStorage<String, String> store2 = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 600000);
        store2.load();
        store2.put("later", "write");

        WalStorage<String, String> store3 = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 600000);
        store3.load();
        assertEquals("data", store3.get("good").orElseThrow());
        assertEquals("write", store3.get("later").orElseThrow());

        store1.shutdown();
        store2.shutdown();
        store3.shutdown();
    }

    @Test
    void aNegativeRecordLengthStopsReplayInsteadOfFailingLoad() throws IOException {
        Path walPath = tempDir.resolve("negative.wal");
        Path snapshotPath = tempDir.resolve("negative-snapshot.json");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(walPath))) {
            writePutRecord(out, "good", "data");
            out.writeByte(WalStorage.OP_PUT);
            out.writeInt(Integer.MIN_VALUE);
        }

        WalStorage<String, String> store = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 600000);
        assertDoesNotThrow(store::load);
        assertEquals("data", store.get("good").orElseThrow());
        assertEquals(1, store.keys().size());

        store.shutdown();
    }

    @Test
    void aRecordLengthBeyondTheFileEndIsTreatedAsATornTail() throws IOException {
        Path walPath = tempDir.resolve("oversized.wal");
        Path snapshotPath = tempDir.resolve("oversized-snapshot.json");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(walPath))) {
            writePutRecord(out, "good", "data");
            out.writeByte(WalStorage.OP_PUT);
            out.writeInt(Integer.MAX_VALUE);
            out.write(new byte[] {0x61, 0x78});
        }

        WalStorage<String, String> store1 = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 600000);
        assertDoesNotThrow(store1::load);
        assertEquals("data", store1.get("good").orElseThrow());
        assertEquals(1, store1.keys().size());
        store1.put("later", "write");

        WalStorage<String, String> store2 = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 600000);
        store2.load();
        assertEquals("data", store2.get("good").orElseThrow());
        assertEquals("write", store2.get("later").orElseThrow());

        store1.shutdown();
        store2.shutdown();
    }

    @Test
    void loadQuarantinesAnUnreadableSnapshot() throws IOException {
        Path snapshotPath = tempDir.resolve("corrupt-snapshot.json");
        Path walPath = tempDir.resolve("corrupt-data.wal");
        String unreadableContents = "{ not valid json";
        Path quarantinePath = tempDir.resolve("corrupt-snapshot.json.corrupt");
        Files.writeString(snapshotPath, unreadableContents);

        WalStorage<String, String> store = new WalStorage<>(snapshotPath, walPath,
                new TypeReference<Map<String, String>>() {}, 600000);
        try {
            store.load();

            assertTrue(store.keys().isEmpty());
            assertFalse(Files.exists(snapshotPath));
            assertEquals(unreadableContents, Files.readString(quarantinePath));
        } finally {
            store.shutdown();
        }
    }

    /** Writes one PUT record in the documented WAL layout, independent of the store's writer. */
    private static void writePutRecord(DataOutputStream out, String key, String value) throws IOException {
        ObjectMapper cbor = new ObjectMapper(new CBORFactory());
        byte[] keyBytes = cbor.writeValueAsBytes(key);
        byte[] valueBytes = cbor.writeValueAsBytes(value);
        out.writeByte(WalStorage.OP_PUT);
        out.writeInt(keyBytes.length);
        out.write(keyBytes);
        out.writeInt(valueBytes.length);
        out.write(valueBytes);
    }
}
