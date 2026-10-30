package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalStorageWriteFailureTest {

    private static final TypeReference<Map<String, String>> STRINGS = new TypeReference<>() {};

    @TempDir
    Path directory;

    enum Mutation { PUT, PUT_ALL, DELETE }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void aMissingWriterRejectsMutations(Mutation mutation) {
        WalStorage<String, String> store = store();
        try {
            assertThrows(IllegalStateException.class, () -> mutate(store, mutation));
            assertTrue(store.keys().isEmpty());
        } finally {
            store.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void aWriterFlushFailureDoesNotPublishTheMutation(Mutation mutation) throws Exception {
        WalStorage<String, String> store = store();
        store.load();
        try {
            store.put("keep", "old");
            replaceWriter(store, new DataOutputStream(OutputStream.nullOutputStream()) {
                @Override
                public void flush() throws IOException {
                    throw new IOException("synthetic flush failure");
                }
            });
            assertThrows(UncheckedIOException.class, () -> mutate(store, mutation));
            assertOldValue(store);
        } finally {
            store.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void anUnwritableLogDoesNotPublishAndCanBeRetried(Mutation mutation) throws IOException {
        WalStorage<String, String> store = store();
        store.load();
        Path log = directory.resolve("data.wal");
        Path savedLog = directory.resolve("retained.wal");
        try {
            store.put("keep", "old");
            Files.move(log, savedLog);
            Files.createDirectory(log);
            try {
                assertThrows(UncheckedIOException.class, () -> mutate(store, mutation));
                assertOldValue(store);
            } finally {
                Files.delete(log);
                Files.move(savedLog, log);
            }
            mutate(store, mutation);
            Map<String, String> replayed = WalStorage.readContents(directory.resolve("snapshot.json"), log, STRINGS);
            if (mutation == Mutation.DELETE) {
                assertTrue(replayed.isEmpty());
            } else {
                assertEquals("new", replayed.get("keep"));
            }
        } finally {
            store.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void aClosedWriterDoesNotPublishAndCanBeRetried(Mutation mutation) throws Exception {
        WalStorage<String, String> store = store();
        store.load();
        Path log = directory.resolve("data.wal");
        try {
            store.put("keep", "old");
            currentWriter(store).close();
            assertThrows(UncheckedIOException.class, () -> mutate(store, mutation));
            assertOldValue(store);
            mutate(store, mutation);
            Map<String, String> replayed = WalStorage.readContents(directory.resolve("snapshot.json"), log, STRINGS);
            if (mutation == Mutation.DELETE) {
                assertTrue(replayed.isEmpty());
            } else {
                assertEquals("new", replayed.get("keep"));
            }
        } finally {
            store.shutdown();
        }
    }

    @Test
    void aFailedBufferedRollbackRejectsLaterWritesAndPreservesTheRecoveryLog() throws Exception {
        WalStorage<String, String> store = store();
        store.load();
        Path log = directory.resolve("data.wal");
        Path retained = directory.resolve("retained.wal");
        try {
            store.put("keep", "old");
            byte[] committedLog = Files.readAllBytes(log);
            replaceWriter(store, new DataOutputStream(OutputStream.nullOutputStream()) {
                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    Files.move(log, retained);
                    Files.createDirectory(log);
                    throw new IOException("synthetic write failure with unavailable rollback path");
                }
            });
            UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> store.put("keep", "new"));
            assertEquals(1, failure.getCause().getSuppressed().length);
            assertOldValue(store);
            assertThrows(IllegalStateException.class, () -> store.put("later", "unsafe"));
            store.flush();
            assertTrue(Files.notExists(directory.resolve("snapshot.json")));
            assertArrayEquals(committedLog, Files.readAllBytes(retained));
        } finally {
            store.shutdown();
        }
    }

    @Test
    void aRepeatedWriterExceptionStillPropagatesTheOriginalFailure() throws Exception {
        WalStorage<String, String> store = store();
        store.load();
        IOException original = new IOException("synthetic repeated writer failure");
        try {
            store.put("keep", "old");
            replaceWriter(store, new DataOutputStream(OutputStream.nullOutputStream()) {
                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    throw original;
                }

                @Override
                public void close() throws IOException {
                    throw original;
                }
            });
            UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> store.put("keep", "new"));
            assertSame(original, failure.getCause());
            assertOldValue(store);
            assertThrows(IllegalStateException.class, () -> store.put("later", "unsafe"));
        } finally {
            store.shutdown();
        }
    }

    @Test
    void anUnserializableValueIsNotPublished() {
        WalStorage<String, Object> store = new WalStorage<>(directory.resolve("snapshot.json"),
                directory.resolve("data.wal"), new TypeReference<>() {}, Long.MAX_VALUE);
        store.load();
        try {
            assertThrows(UncheckedIOException.class, () -> store.put("bad", new BrokenValue()));
            assertTrue(store.keys().isEmpty());
        } finally {
            store.shutdown();
        }
    }

    @Test
    void anUnserializableBatchPublishesNoneOfItsValues() {
        WalStorage<String, Object> store = new WalStorage<>(directory.resolve("snapshot.json"),
                directory.resolve("data.wal"), new TypeReference<>() {}, Long.MAX_VALUE);
        store.load();
        try {
            store.put("keep", "old");
            assertThrows(UncheckedIOException.class,
                    () -> store.putAll(Map.of("keep", "new", "bad", new BrokenValue())));
            assertEquals("old", store.get("keep").orElseThrow());
            assertTrue(store.get("bad").isEmpty());
        } finally {
            store.shutdown();
        }
    }

    @Test
    void ordinaryWritesKeepTheLegacyRecordLayout() throws IOException {
        WalStorage<String, String> store = store();
        store.load();
        try {
            store.put("a", "1");
            store.putAll(Map.of("b", "2", "c", "3"));
            store.delete("a");
            List<Integer> operations = new ArrayList<>();
            try (DataInputStream input = new DataInputStream(Files.newInputStream(directory.resolve("data.wal")))) {
                while (input.available() > 0) {
                    int operation = input.readUnsignedByte();
                    operations.add(operation);
                    input.skipNBytes(input.readInt());
                    if (operation == 1) {
                        input.skipNBytes(input.readInt());
                    }
                }
            }
            assertEquals(List.of(1, 1, 1, 2), operations);
            assertEquals(Map.of("b", "2", "c", "3"),
                    WalStorage.readContents(directory.resolve("snapshot.json"), directory.resolve("data.wal"), STRINGS));
        } finally {
            store.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void aPartialBufferedWriteRollsBackAndAllowsARetry(Mutation mutation) throws Exception {
        assertBufferedFailureRollsBack(mutation, false);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void aFlushFailureRollsBackAndAllowsARetry(Mutation mutation) throws Exception {
        assertBufferedFailureRollsBack(mutation, true);
    }

    private void assertBufferedFailureRollsBack(Mutation mutation, boolean failFlush) throws Exception {
        WalStorage<String, String> store = store();
        store.load();
        Path log = directory.resolve("data.wal");
        try {
            store.put("keep", "old");
            byte[] committedLog = Files.readAllBytes(log);
            AtomicBoolean failNext = new AtomicBoolean(true);
            OutputStream output = Files.newOutputStream(log, StandardOpenOption.APPEND);
            replaceWriter(store, new DataOutputStream(new BufferedOutputStream(new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    output.write(value);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (!failFlush && failNext.getAndSet(false)) {
                        output.write(bytes, offset, Math.min(3, length));
                        throw new IOException("synthetic partial buffered write failure");
                    }
                    output.write(bytes, offset, length);
                }

                @Override
                public void flush() throws IOException {
                    assertOldValue(store);
                    output.flush();
                    if (failFlush && failNext.getAndSet(false)) {
                        throw new IOException("synthetic flush failure after writing the record");
                    }
                }

                @Override
                public void close() throws IOException {
                    output.close();
                }
            })));
            UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> mutate(store, mutation));
            assertTrue(failure.getCause().getMessage().contains("synthetic"));
            assertOldValue(store);
            assertArrayEquals(committedLog, Files.readAllBytes(log));
            assertEquals(Map.of("keep", "old"),
                    WalStorage.readContents(directory.resolve("snapshot.json"), log, STRINGS));
            mutate(store, mutation);
            Map<String, String> replayed = WalStorage.readContents(directory.resolve("snapshot.json"), log, STRINGS);
            if (mutation == Mutation.DELETE) {
                assertTrue(replayed.isEmpty());
            } else {
                assertEquals("new", replayed.get("keep"));
            }
        } finally {
            store.shutdown();
        }
    }

    enum AtomicMutation { PUT_ALL, DELETE_ALL }

    @ParameterizedTest
    @EnumSource(AtomicMutation.class)
    void aFailedAtomicSyncRollsBackTheLogAndAllowsARetry(AtomicMutation mutation) throws IOException {
        AtomicBoolean failNext = new AtomicBoolean();
        WalStorage<String, String> store = new WalStorage<>(directory.resolve("snapshot.json"),
                directory.resolve("data.wal"), STRINGS, Long.MAX_VALUE, channel -> {
                    if (failNext.getAndSet(false)) {
                        throw new IOException("synthetic sync failure");
                    }
                    channel.force(true);
                });
        store.load();
        try {
            store.put("keep", "old");
            long committedLength = Files.size(directory.resolve("data.wal"));
            failNext.set(true);
            assertThrows(UncheckedIOException.class, () -> mutateAtomically(store, mutation));
            assertOldValue(store);
            assertEquals(committedLength, Files.size(directory.resolve("data.wal")));
            assertEquals(Map.of("keep", "old"), WalStorage.readContents(directory.resolve("snapshot.json"),
                    directory.resolve("data.wal"), STRINGS));
            mutateAtomically(store, mutation);
            Map<String, String> replayed = WalStorage.readContents(directory.resolve("snapshot.json"),
                    directory.resolve("data.wal"), STRINGS);
            if (mutation == AtomicMutation.DELETE_ALL) {
                assertTrue(replayed.isEmpty());
            } else {
                assertEquals("new", replayed.get("keep"));
            }
        } finally {
            store.shutdown();
        }
    }

    @Test
    void aFailedRollbackRejectsLaterWritesAndPreservesTheRecoveryLog() throws IOException {
        AtomicBoolean failSync = new AtomicBoolean();
        WalStorage<String, String> store = new WalStorage<>(directory.resolve("snapshot.json"),
                directory.resolve("data.wal"), STRINGS, Long.MAX_VALUE, channel -> {
                    if (failSync.get()) {
                        throw new IOException("synthetic disk failure during sync and rollback");
                    }
                    channel.force(true);
                });
        store.load();
        try {
            store.put("keep", "old");
            byte[] committedLog = Files.readAllBytes(directory.resolve("data.wal"));
            failSync.set(true);
            UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                    () -> store.putAllAtomically(Map.of("keep", "new")));
            assertEquals(1, failure.getCause().getSuppressed().length);
            assertOldValue(store);
            assertThrows(IllegalStateException.class, () -> store.put("later", "unsafe"));
            store.flush();
            assertArrayEquals(committedLog, Files.readAllBytes(directory.resolve("data.wal")));
            assertTrue(Files.notExists(directory.resolve("snapshot.json")));
        } finally {
            store.shutdown();
        }
    }

    @Test
    void ordinaryWritesReuseTheBufferedWriterWhileAtomicBatchesForceTheLog() throws Exception {
        AtomicInteger syncs = new AtomicInteger();
        WalStorage<String, String> store = new WalStorage<>(directory.resolve("snapshot.json"),
                directory.resolve("data.wal"), STRINGS, Long.MAX_VALUE, channel -> {
                    channel.force(true);
                    syncs.incrementAndGet();
                });
        store.load();
        try {
            DataOutputStream writer = currentWriter(store);
            store.put("keep", "old");
            assertEquals(0, syncs.get());
            store.putAll(Map.of("keep", "new", "added", "value"));
            assertEquals(0, syncs.get());
            store.delete("added");
            assertEquals(0, syncs.get());
            assertSame(writer, currentWriter(store));
            store.putAllAtomically(Map.of("batch", "value"));
            assertEquals(1, syncs.get());
            store.deleteAllAtomically(Set.of("batch"));
            assertEquals(2, syncs.get());
        } finally {
            store.shutdown();
        }
    }

    private void mutateAtomically(WalStorage<String, String> store, AtomicMutation mutation) {
        switch (mutation) {
            case PUT_ALL -> store.putAllAtomically(Map.of("keep", "new", "added", "value"));
            case DELETE_ALL -> store.deleteAllAtomically(Set.of("keep"));
        }
    }

    private WalStorage<String, String> store() {
        return new WalStorage<>(directory.resolve("snapshot.json"), directory.resolve("data.wal"),
                STRINGS, Long.MAX_VALUE);
    }

    private void mutate(WalStorage<String, String> store, Mutation mutation) {
        switch (mutation) {
            case PUT -> store.put("keep", "new");
            case PUT_ALL -> store.putAll(Map.of("keep", "new", "added", "value"));
            case DELETE -> store.delete("keep");
        }
    }

    private void assertOldValue(WalStorage<String, String> store) {
        assertEquals("old", store.get("keep").orElseThrow());
        assertTrue(store.get("added").isEmpty());
    }

    private DataOutputStream currentWriter(WalStorage<?, ?> store) throws Exception {
        Field field = WalStorage.class.getDeclaredField("walWriter");
        field.setAccessible(true);
        return (DataOutputStream) field.get(store);
    }

    private void replaceWriter(WalStorage<?, ?> store, DataOutputStream replacement) throws Exception {
        Field field = WalStorage.class.getDeclaredField("walWriter");
        field.setAccessible(true);
        ((DataOutputStream) field.get(store)).close();
        field.set(store, replacement);
    }

    public static class BrokenValue {
        public String getValue() {
            throw new IllegalStateException("synthetic serialization failure");
        }
    }
}
