package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lifecycle contract of {@link StorageFactory}: {@code create()} owns the initial load of each
 * backend, {@code loadAll()} never reloads a live backend, {@code shutdownAll()} performs the
 * final flush and then leaves no open WAL writer behind, and a backend first created after
 * {@code clearAll()} starts empty instead of loading its pre-reset file.
 */
class StorageFactoryLifecycleTest {

    private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() {};

    @TempDir
    Path tempDir;

    @Test
    void loadAllDoesNotReplayABackendThatCreateAlreadyLoaded() {
        StorageFactory factory = newFactory("hybrid");
        AccountAwareStorageBackend<String> backend = factory.create("svc", "svc.json", TYPE);
        backend.put("seed", "v1");
        factory.flushAll();
        backend.put("unflushed", "v2");

        // Boot calls loadAll() once; a second call must be just as harmless. A reload of the
        // hybrid store would clear it and drop the write that has not been flushed yet.
        factory.loadAll();
        factory.loadAll();

        assertEquals(Optional.of("v1"), backend.get("seed"));
        assertEquals(Optional.of("v2"), backend.get("unflushed"));
        factory.shutdownAll();
    }

    @Test
    void createLoadsPersistedStateForBackendsRegisteredAfterLoadAll() {
        StorageFactory first = newFactory("wal");
        first.create("svc", "svc.json", TYPE).put("persisted", "v1");
        first.shutdownAll();

        // Lazily instantiated services register their backend after the lifecycle's loadAll().
        StorageFactory second = newFactory("wal");
        second.loadAll();
        AccountAwareStorageBackend<String> late = second.create("svc", "svc.json", TYPE);
        assertEquals(Optional.of("v1"), late.get("persisted"));
        late.put("written-late", "v2");
        second.shutdownAll();

        StorageFactory third = newFactory("wal");
        AccountAwareStorageBackend<String> reloaded = third.create("svc", "svc.json", TYPE);
        assertEquals(Optional.of("v1"), reloaded.get("persisted"));
        assertEquals(Optional.of("v2"), reloaded.get("written-late"));
        third.shutdownAll();
    }

    @Test
    void shutdownFlushesAndLeavesNoOpenWalWriter() throws IOException {
        StorageFactory factory = newFactory("wal");
        AccountAwareStorageBackend<String> backend = factory.create("svc", "svc.json", TYPE);
        backend.put("before", "v1");
        factory.loadAll();
        factory.flushAll();
        factory.loadAll();

        factory.shutdownAll();

        assertTrue(Files.readString(tempDir.resolve("svc-snapshot.json")).contains("before"),
                "final shutdown must flush the store into the snapshot");
        long walSizeAfterShutdown = walSize();
        backend.put("after", "v2");
        assertEquals(walSizeAfterShutdown, walSize(), "no WAL writer may be open after shutdown");
    }

    @Test
    void shutdownIsSafeAfterACompletedFlushAndCanBeRepeated() throws IOException {
        StorageFactory factory = newFactory("wal");
        AccountAwareStorageBackend<String> backend = factory.create("svc", "svc.json", TYPE);
        backend.put("before", "v1");
        factory.flushAll();

        factory.shutdownAll();
        assertDoesNotThrow(factory::shutdownAll);

        long walSizeAfterShutdown = walSize();
        backend.put("after", "v2");
        assertEquals(walSizeAfterShutdown, walSize(), "no WAL writer may be open after shutdown");

        StorageFactory reloaded = newFactory("wal");
        AccountAwareStorageBackend<String> again = reloaded.create("svc", "svc.json", TYPE);
        assertEquals(Optional.of("v1"), again.get("before"));
        assertEquals(Optional.empty(), again.get("after"));
        reloaded.shutdownAll();
    }

    @ParameterizedTest
    @CsvSource({"persistent,DEFAULT", "persistent,APPEND_HEAVY", "hybrid,DEFAULT", "wal,DEFAULT"})
    void storeFirstCreatedAfterAResetStartsEmptyAndOverwritesItsFile(String mode, WriteProfile profile) {
        StorageFactory earlierRun = newFactory(mode);
        earlierRun.create("ssm", "ssm-parameters.json", TYPE, profile).put("/app/stale", "v1");
        earlierRun.shutdownAll();

        // A service that is neither Resettable nor touched at boot creates its store only after the reset.
        StorageFactory resetRun = newFactory(mode);
        resetRun.clearAll();
        AccountAwareStorageBackend<String> late = resetRun.create("ssm", "ssm-parameters.json", TYPE, profile);
        assertTrue(late.keys().isEmpty(), "a store created after a reset must not load the pre-reset file");
        late.put("/app/fresh", "v2");
        resetRun.shutdownAll();

        StorageFactory restarted = newFactory(mode);
        AccountAwareStorageBackend<String> reloaded = restarted.create("ssm", "ssm-parameters.json", TYPE, profile);
        assertEquals(Optional.empty(), reloaded.get("/app/stale"));
        assertEquals(Optional.of("v2"), reloaded.get("/app/fresh"));
        restarted.shutdownAll();
    }

    @Test
    void resetLeavesFilesThatNoStoreOwnsUnderThePersistentPath() throws IOException {
        Path lambdaCode = tempDir.resolve("lambda-code").resolve("function.zip");
        Files.createDirectories(lambdaCode.getParent());
        Files.writeString(lambdaCode, "zip");

        StorageFactory factory = newFactory("persistent");
        factory.clearAll();
        factory.create("ssm", "ssm-parameters.json", TYPE);
        factory.shutdownAll();

        assertEquals("zip", Files.readString(lambdaCode));
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
