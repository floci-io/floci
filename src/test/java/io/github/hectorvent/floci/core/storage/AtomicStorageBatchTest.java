package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AtomicStorageBatchTest {
    private static final TypeReference<Map<String, Integer>> TYPE = new TypeReference<>() {};

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void atomicDeletionRemovesSelectedAccountsAndSurvivesRestart(String mode) {
        StorageBackend<String, Integer> backend = backend(mode);
        backend.load();
        try {
            AccountAwareStorageBackend<Integer> accounts = new AccountAwareStorageBackend<>(backend, null, "111111111111");
            accounts.putAllAtomically(Map.of("a", 1, "b", 2));
            accounts.putAllAtomicallyForAccount("222222222222", Map.of("a", 3, "keep", 4));
            accounts.deleteAllAtomicallyForAccounts(Map.of("111111111111", Set.of("a", "b"), "222222222222", Set.of("a")));
            assertEquals(Set.of("222222222222/keep"), backend.keys());
            assertEquals(4, accounts.getForAccount("222222222222", "keep").orElseThrow());
            backend.flush();
        } finally {
            close(backend);
        }
        if (!"memory".equals(mode)) {
            StorageBackend<String, Integer> reopened = backend(mode);
            reopened.load();
            try {
                assertEquals(Set.of("222222222222/keep"), reopened.keys());
            } finally {
                close(reopened);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void publishesWholeBatchWithAccountIsolation(String mode) {
        StorageBackend<String, Integer> backend = backend(mode);
        backend.load();
        try {
            AccountAwareStorageBackend<Integer> store =
                    new AccountAwareStorageBackend<>(backend, null, "111111111111");
            store.putAllAtomically(Map.of("source", 3, "destination", 1));
            store.putAllAtomicallyForAccount("222222222222", Map.of("source", 8, "destination", 2));
            store.putAllAtomically(Map.of("source", 1, "destination", 3));
            assertEquals(4, store.scan(key -> true).stream().mapToInt(Integer::intValue).sum());
            assertEquals(10, store.scanForAccount("222222222222", key -> true)
                    .stream().mapToInt(Integer::intValue).sum());
            assertEquals(1, store.get("source").orElseThrow());
            assertEquals(8, store.getForAccount("222222222222", "source").orElseThrow());
            // Enable the owner index, then publish a new account through the batch path.
            assertTrue(store.findAnyAccount("missing").isEmpty());
            store.putAllAtomicallyForAccount("333333333333", Map.of("unique", 9));
            assertEquals("333333333333", store.findAnyAccountEntry("unique").orElseThrow().account());
        } finally {
            close(backend);
        }
    }

    @Test
    void persistentFailureLeavesMemoryAndPreviousSnapshotUnchanged() throws Exception {
        PersistentStorage<String, Integer> store = new PersistentStorage<>(directory.resolve("data.json"), TYPE);
        store.putAllAtomically(Map.of("source", 3, "destination", 1));
        Path snapshot = directory.resolve("data.json");
        Path backup = directory.resolve("backup.json");
        Files.move(snapshot, backup);
        Files.createDirectory(snapshot);
        Files.writeString(snapshot.resolve("blocker"), "owned test file");
        assertThrows(UncheckedIOException.class,
                () -> store.putAllAtomically(Map.of("source", 1, "destination", 3)));
        assertThrows(UncheckedIOException.class, () -> store.deleteAllAtomically(Set.of("source", "destination")));
        assertEquals(3, store.get("source").orElseThrow());
        assertEquals(1, store.get("destination").orElseThrow());
        PersistentStorage<String, Integer> recovered = new PersistentStorage<>(backup, TYPE);
        recovered.load();
        assertEquals(3, recovered.get("source").orElseThrow());
        Files.delete(snapshot.resolve("blocker"));
        Files.delete(snapshot);
        Files.move(backup, snapshot);
        store.putAllAtomically(Map.of("source", 1, "destination", 3));
        recovered = new PersistentStorage<>(snapshot, TYPE);
        recovered.load();
        assertEquals(1, recovered.get("source").orElseThrow());
        assertEquals(3, recovered.get("destination").orElseThrow());
    }

    @Test
    void hybridBatchDoesNotBecomeWriteThrough() throws Exception {
        HybridStorage<String, Integer> store = new HybridStorage<>(directory.resolve("data.json"), TYPE, Long.MAX_VALUE);
        try {
            store.putAllAtomically(Map.of("source", 3, "destination", 1));
            store.flush();
            byte[] before = Files.readAllBytes(directory.resolve("data.json"));
            store.putAllAtomically(Map.of("source", 1, "destination", 3));
            assertArrayEquals(before, Files.readAllBytes(directory.resolve("data.json")));
            store.flush();
            PersistentStorage<String, Integer> recovered = new PersistentStorage<>(directory.resolve("data.json"), TYPE);
            recovered.load();
            assertEquals(1, recovered.get("source").orElseThrow());
            assertEquals(3, recovered.get("destination").orElseThrow());
        } finally {
            store.shutdown();
        }
    }

    @Test
    void hybridSnapshotAndBatchSharePublicationBoundary() throws Exception {
        TypeReference<Map<String, SnapshotValue>> type = new TypeReference<>() {};
        HybridStorage<String, SnapshotValue> store =
                new HybridStorage<>(directory.resolve("data.json"), type, Long.MAX_VALUE);
        CountDownLatch serializing = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        SnapshotValue source = new SnapshotValue(3, serializing, resume);
        store.putAllAtomically(Map.of("source", source, "destination", new SnapshotValue(1, null, null)));
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> flush = workers.submit(store::flush);
            try {
                assertTrue(serializing.await(10, TimeUnit.SECONDS));
                Future<?> batch = workers.submit(() -> store.putAllAtomically(
                        Map.of("source", new SnapshotValue(1, null, null),
                                "destination", new SnapshotValue(3, null, null))));
                batch.get(10, TimeUnit.SECONDS);
                Future<Integer> read = workers.submit(() -> store.get("source").orElseThrow().getCount());
                assertEquals(1, read.get(10, TimeUnit.SECONDS));
                resume.countDown();
                flush.get(10, TimeUnit.SECONDS);
                PersistentStorage<String, SnapshotValue> recovered =
                        new PersistentStorage<>(directory.resolve("data.json"), type);
                recovered.load();
                assertEquals(3, recovered.get("source").orElseThrow().getCount());
                assertEquals(1, recovered.get("destination").orElseThrow().getCount());
                assertEquals(1, store.get("source").orElseThrow().getCount());
                assertEquals(3, store.get("destination").orElseThrow().getCount());
            } finally {
                resume.countDown();
            }
        } finally {
            store.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "wal"})
    void readsContinueWhileSnapshotSerializationIsPaused(String mode) throws Exception {
        TypeReference<Map<String, SnapshotValue>> type = new TypeReference<>() {};
        StorageBackend<String, SnapshotValue> store = "wal".equals(mode)
                ? new WalStorage<>(directory.resolve("data.json"), directory.resolve("data.wal"), type, Long.MAX_VALUE)
                : new PersistentStorage<>(directory.resolve("data.json"), type);
        store.load();
        store.putAllAtomically(Map.of("value", new SnapshotValue(3, null, null)));
        CountDownLatch serializing = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        SnapshotValue blocking = new SnapshotValue(4, serializing, resume);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> write;
            if ("wal".equals(mode)) {
                // Activate the pause only after the ordinary WAL append has encoded the value.
                SnapshotValue value = new SnapshotValue(4, null, null);
                store.put("value", value);
                value.pause(serializing, resume);
                write = workers.submit(store::flush);
            } else {
                write = workers.submit(() -> store.putAllAtomically(Map.of("value", blocking)));
            }
            try {
                assertTrue(serializing.await(10, TimeUnit.SECONDS));
                Future<SnapshotValue> read = workers.submit(() -> store.get("value").orElseThrow());
                assertEquals("wal".equals(mode) ? 4 : 3, read.get(10, TimeUnit.SECONDS).count);
                Future<Integer> scan = workers.submit(() -> store.scan(key -> true).size());
                assertEquals(1, scan.get(10, TimeUnit.SECONDS));
            } finally {
                resume.countDown();
            }
            write.get(10, TimeUnit.SECONDS);
        } finally {
            if (store instanceof WalStorage<String, SnapshotValue> wal) {
                wal.shutdown();
            }
        }
    }

    @Test
    void inMemoryReadersDoNotQueueBehindAnotherScan() throws Exception {
        InMemoryStorage<String, Integer> store = new InMemoryStorage<>();
        store.put("value", 1);
        CountDownLatch scanning = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> scan = workers.submit(() -> store.scan(key -> {
                scanning.countDown();
                try {
                    assertTrue(resume.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return true;
            }));
            try {
                assertTrue(scanning.await(10, TimeUnit.SECONDS));
                Future<Integer> read = workers.submit(() -> store.get("value").orElseThrow());
                assertEquals(1, read.get(10, TimeUnit.SECONDS));
            } finally {
                resume.countDown();
            }
            scan.get(10, TimeUnit.SECONDS);
        }
    }

    public static class SnapshotValue {
        private int count;
        private CountDownLatch serializing;
        private CountDownLatch resume;

        public SnapshotValue() {
            this(0, null, null);
        }

        SnapshotValue(int count, CountDownLatch serializing, CountDownLatch resume) {
            this.count = count;
            this.serializing = serializing;
            this.resume = resume;
        }

        void pause(CountDownLatch started, CountDownLatch release) {
            serializing = started;
            resume = release;
        }

        public int getCount() {
            if (serializing != null) {
                serializing.countDown();
                try {
                    assertTrue(resume.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    @Test
    void everyTornBatchPrefixRetainsLegacyRecordsAndAllowsLaterWrites() throws Exception {
        Path snapshot = directory.resolve("original.json");
        Path log = directory.resolve("original.wal");
        WalStorage<String, Integer> store = new WalStorage<>(snapshot, log, TYPE, Long.MAX_VALUE);
        store.load();
        try {
            // Ordinary writes retain the old PUT/DELETE encoding.
            store.put("source", 3);
            store.put("destination", 1);
            store.put("deleted", 1);
            store.delete("deleted");
            int oldLength = Files.readAllBytes(log).length;
            store.putAllAtomically(Map.of("source", 1, "destination", 3));
            byte[] complete = Files.readAllBytes(log);
            for (int length = oldLength; length <= complete.length; length++) {
                Path replayLog = directory.resolve("replay-" + length + ".wal");
                Path replaySnapshot = directory.resolve("replay-" + length + ".json");
                Files.write(replayLog, Arrays.copyOf(complete, length));
                WalStorage<String, Integer> recovered = new WalStorage<>(replaySnapshot, replayLog, TYPE, Long.MAX_VALUE);
                recovered.load();
                try {
                    assertEquals(length == complete.length ? 1 : 3, recovered.get("source").orElseThrow(),
                            "prefix " + length);
                    assertEquals(length == complete.length ? 3 : 1, recovered.get("destination").orElseThrow(),
                            "prefix " + length);
                    assertTrue(recovered.get("deleted").isEmpty());
                    recovered.putAllAtomically(Map.of("later", 7));
                    Map<String, Integer> replayed = WalStorage.readContents(replaySnapshot, replayLog, TYPE);
                    assertEquals(7, replayed.get("later"));
                    assertEquals(4, replayed.get("source") + replayed.get("destination"));
                } finally {
                    recovered.shutdown();
                }
            }
        } finally {
            store.shutdown();
        }
    }

    @Test
    void walAppendFailureDoesNotPublishAndCanRetry() throws Exception {
        Path log = directory.resolve("data.wal");
        WalStorage<String, Integer> store = new WalStorage<>(directory.resolve("data.json"), log, TYPE, Long.MAX_VALUE);
        store.load();
        try {
            store.putAllAtomically(Map.of("source", 3, "destination", 1));
            Path saved = directory.resolve("saved.wal");
            Files.move(log, saved);
            Files.createDirectory(log);
            assertThrows(UncheckedIOException.class,
                    () -> store.putAllAtomically(Map.of("source", 1, "destination", 3)));
            assertThrows(UncheckedIOException.class, () -> store.deleteAllAtomically(Set.of("source", "destination")));
            assertEquals(3, store.get("source").orElseThrow());
            assertEquals(1, store.get("destination").orElseThrow());
            Files.delete(log);
            Files.move(saved, log);
            store.putAllAtomically(Map.of("source", 1, "destination", 3));
            assertEquals(Map.of("source", 1, "destination", 3),
                    WalStorage.readContents(directory.resolve("data.json"), log, TYPE));
        } finally {
            store.shutdown();
        }
    }

    @Test
    void tornWalDeletionBatchNeverRemovesOnlySomeKeys() throws Exception {
        Path snapshot = directory.resolve("data.json");
        Path log = directory.resolve("data.wal");
        WalStorage<String, Integer> writer = new WalStorage<>(snapshot, log, TYPE, Long.MAX_VALUE);
        writer.load();
        try {
            writer.putAllAtomically(Map.of("a", 1, "b", 2, "keep", 3));
            writer.flush();
            byte[] initial = Files.readAllBytes(snapshot);
            writer.deleteAllAtomically(Set.of("a", "b"));
            byte[] complete = Files.readAllBytes(log);
            for (int length = 0; length <= complete.length; length++) {
                Path replaySnapshot = directory.resolve("replay-" + length + ".json");
                Path replayLog = directory.resolve("replay-" + length + ".wal");
                Files.write(replaySnapshot, initial);
                Files.write(replayLog, Arrays.copyOf(complete, length));
                WalStorage<String, Integer> replay = new WalStorage<>(replaySnapshot, replayLog, TYPE, Long.MAX_VALUE);
                replay.load();
                try {
                    boolean deleted = length == complete.length;
                    assertEquals(deleted, replay.get("a").isEmpty(), "prefix " + length);
                    assertEquals(deleted, replay.get("b").isEmpty(), "prefix " + length);
                    assertEquals(3, replay.get("keep").orElseThrow());
                } finally {
                    replay.shutdown();
                }
            }
        } finally {
            writer.shutdown();
        }
    }

    private StorageBackend<String, Integer> backend(String mode) {
        return switch (mode) {
            case "persistent" -> new PersistentStorage<>(directory.resolve("data.json"), TYPE);
            case "hybrid" -> new HybridStorage<>(directory.resolve("data.json"), TYPE, Long.MAX_VALUE);
            case "wal" -> new WalStorage<>(directory.resolve("data.json"), directory.resolve("data.wal"),
                    TYPE, Long.MAX_VALUE);
            default -> new InMemoryStorage<>();
        };
    }

    private void close(StorageBackend<String, Integer> backend) {
        if (backend instanceof WalStorage<String, Integer> wal) {
            wal.shutdown();
        } else if (backend instanceof HybridStorage<String, Integer> hybrid) {
            hybrid.shutdown();
        }
    }
}
