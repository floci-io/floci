package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How {@link StorageFactory} maps a store's {@link WriteProfile} onto a backend: under
 * {@code persistent} mode an append-heavy store is journaled instead of rewritten on every
 * mutation, while its snapshot stays at the store's own JSON file so the data can be read by a
 * plain persistent store and back.
 */
class StorageFactoryWriteProfileTest {

    private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() {};

    @TempDir
    Path tempDir;

    @Test
    void persistentModeJournalsAppendHeavyStoresSoAWriteCostsTheWriteNotTheStore() throws IOException {
        StorageFactory factory = newFactory("persistent");
        AccountAwareStorageBackend<String> events =
                factory.create("svc", "svc.json", TYPE, WriteProfile.APPEND_HEAVY);

        fill(events, 0, 1_000);
        long smallStore = walSize();
        events.put(key(1_000), "value");
        long costAtOneThousand = walSize() - smallStore;

        fill(events, 1_001, 10_000);
        long largeStore = walSize();
        events.put(key(10_000), "value");
        long costAtTenThousand = walSize() - largeStore;

        assertTrue(costAtOneThousand > 0, "a put must reach the journal");
        assertEquals(costAtOneThousand, costAtTenThousand, "the cost of one put must not grow with the store");
        assertFalse(Files.exists(tempDir.resolve("svc.json")), "the store file is only written by compaction");

        factory.shutdownAll();
        assertTrue(Files.readString(tempDir.resolve("svc.json")).contains(key(10_000)),
                "shutdown must fold the journal into the store file");
    }

    @Test
    void persistentModeStillRewritesDefaultProfileStores() {
        StorageFactory factory = newFactory("persistent");
        factory.create("svc", "svc.json", TYPE).put("only", "v1");

        assertTrue(Files.exists(tempDir.resolve("svc.json")), "a default-profile store is written on every mutation");
        assertFalse(Files.exists(tempDir.resolve("svc.wal")));
        factory.shutdownAll();
    }

    @Test
    void aPersistentStoreIsReadByTheJournaledBackendAndBack() {
        StorageFactory before = newFactory("persistent");
        before.create("svc", "svc.json", TYPE).put("first", "v1");
        before.shutdownAll();

        StorageFactory journaled = newFactory("persistent");
        AccountAwareStorageBackend<String> upgraded =
                journaled.create("svc", "svc.json", TYPE, WriteProfile.APPEND_HEAVY);
        assertEquals(Optional.of("v1"), upgraded.get("first"));
        upgraded.put("second", "v2");
        journaled.shutdownAll();

        StorageFactory after = newFactory("persistent");
        AccountAwareStorageBackend<String> downgraded = after.create("svc", "svc.json", TYPE);
        assertEquals(Optional.of("v1"), downgraded.get("first"));
        assertEquals(Optional.of("v2"), downgraded.get("second"));
        after.shutdownAll();
    }

    @Test
    void memoryModeIgnoresTheWriteProfile() throws IOException {
        StorageFactory factory = newFactory("memory");
        factory.create("svc", "svc.json", TYPE, WriteProfile.APPEND_HEAVY).put("only", "v1");
        factory.shutdownAll();

        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(0, files.count(), "memory mode must not touch the filesystem");
        }
    }

    private static void fill(AccountAwareStorageBackend<String> store, int from, int toExclusive) {
        for (int i = from; i < toExclusive; i++) {
            store.put(key(i), "value");
        }
    }

    /** Fixed-width keys, so every journal record has the same size. */
    private static String key(int i) {
        return String.format("k%05d", i);
    }

    private long walSize() throws IOException {
        Path wal = tempDir.resolve("svc.wal");
        return Files.exists(wal) ? Files.size(wal) : 0L;
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
