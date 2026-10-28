package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DurableStorageBatchTest {
    @TempDir
    Path directory;

    @Test
    void hybridDurableWriteSurvivesReloadWithoutPeriodicFlush() {
        Path file = directory.resolve("claims.json");
        HybridStorage<String, String> storage = new HybridStorage<>(file, new TypeReference<>() {}, 600_000);
        try {
            storage.putAllDurably(Map.of("action", "claimed"));
            PersistentStorage<String, String> reloaded = new PersistentStorage<>(file, new TypeReference<>() {});
            reloaded.load();
            assertEquals("claimed", reloaded.get("action").orElseThrow());
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void hybridFailureIsReportedBeforePublishingClaim() throws IOException {
        Path blocker = directory.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        HybridStorage<String, String> storage = new HybridStorage<>(blocker.resolve("claims.json"),
                new TypeReference<>() {}, 600_000);
        try {
            assertThrows(UncheckedIOException.class, () -> storage.putAllDurably(Map.of("action", "claimed")));
            assertTrue(storage.get("action").isEmpty());
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void accountAwareDurableWritesRemainIsolated() {
        AccountAwareStorageBackend<String> storage = AccountAwareStorageBackend.inMemory("default");
        storage.putAllDurablyForAccount("other", Map.of("action", "claimed"));
        assertTrue(storage.get("action").isEmpty());
        assertEquals("claimed", storage.getForAccount("other", "action").orElseThrow());
    }
}
