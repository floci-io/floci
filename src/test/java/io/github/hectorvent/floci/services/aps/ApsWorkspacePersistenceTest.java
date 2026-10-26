package io.github.hectorvent.floci.services.aps;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.HybridStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.aps.model.PrometheusWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class ApsWorkspacePersistenceTest {

    private static final String ACCOUNT = "741648800003";
    private static final String REGION = "eu-central-1";

    @TempDir
    Path directory;

    @Test
    void aFlushDuringCreateCannotPublishOnlyOneHalfOfTheRequest() throws Exception {
        String name = "aps-workspaces.json";
        Path restored = Files.createDirectory(directory.resolve("restored"));
        HybridStorage<String, PrometheusWorkspace> backend = new HybridStorage<>(directory.resolve(name),
                new TypeReference<Map<String, PrometheusWorkspace>>() {}, 3_600_000);
        AccountAwareStorageBackend<PrometheusWorkspace> storage =
                spy(new AccountAwareStorageBackend<>(backend, null, ACCOUNT));
        AtomicReference<byte[]> firstImage = new AtomicReference<>();
        Answer<Object> capturePublication = call -> {
            Object result = call.callRealMethod();
            backend.flush();
            firstImage.compareAndSet(null, Files.readAllBytes(directory.resolve(name)));
            return result;
        };
        doAnswer(capturePublication).when(storage).put(anyString(), any());
        doAnswer(capturePublication).when(storage).putAllAtomically(any());
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.<PrometheusWorkspace>create(eq("aps"), eq(name), any())).thenReturn(storage);
        Map<String, StorageBackend<String, ?>> restoredStores = new HashMap<>();
        try {
            ApsService first = new ApsService(factory, new RegionResolver(REGION, ACCOUNT));
            PrometheusWorkspace initial = first.createWorkspace(REGION, "original", Map.of(), null, "mid-create");
            Files.write(restored.resolve(name), firstImage.get());
            ApsService restarted = service(restored, "hybrid", restoredStores);
            PrometheusWorkspace replay = restarted.createWorkspace(REGION, "original", Map.of(), null, "mid-create");
            assertEquals(initial.getWorkspaceId(), replay.getWorkspaceId());
            assertDoesNotThrow(() -> restarted.describeWorkspace(REGION, replay.getWorkspaceId()));
            assertEquals(1, restarted.listWorkspaces(REGION, null, null, null).items().size());
        } finally {
            backend.shutdown();
            shutdown(restoredStores);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"hybrid", "persistent"})
    void savedCreateRestoresBothWorkspaceAndReceipt(String mode) throws Exception {
        Map<String, StorageBackend<String, ?>> originalStores = new HashMap<>();
        Map<String, StorageBackend<String, ?>> restoredStores = new HashMap<>();
        Path original = Files.createDirectory(directory.resolve("original"));
        Path restored = Files.createDirectory(directory.resolve("restored"));
        try {
            ApsService first = service(original, mode, originalStores);
            PrometheusWorkspace initial = first.createWorkspace(REGION, "original", Map.of("team", "one"), null, "retry");
            String savedFile = "aps-workspaces.json";
            originalStores.get(savedFile).flush();
            // Restore the disk image at this flush boundary, before any shutdown flushes.
            Files.copy(original.resolve(savedFile), restored.resolve(savedFile));

            ApsService restarted = service(restored, mode, restoredStores);
            PrometheusWorkspace replay = restarted.createWorkspace(REGION, "original", Map.of("team", "one"), null, "retry");
            assertEquals(initial.getWorkspaceId(), replay.getWorkspaceId(), "a durable create cannot duplicate on retry");
            PrometheusWorkspace described = assertDoesNotThrow(() -> restarted.describeWorkspace(REGION, replay.getWorkspaceId()),
                    "a durable receipt cannot point at a missing workspace");
            assertEquals(initial.getCreatedAt(), described.getCreatedAt());
            assertEquals(1, restarted.listWorkspaces(REGION, null, null, null).items().size());
            assertEquals(0, restarted.listWorkspaces("us-west-2", null, null, null).items().size());
            assertEquals(0, restarted.listWorkspaces("CreateWorkspace", null, null, null).items().size());
            assertEquals(0, restarted.listWorkspaces("", null, null, null).items().size());
        } finally {
            shutdown(originalStores);
            shutdown(restoredStores);
        }
    }

    @ParameterizedTest
    @CsvSource({"hybrid,false", "hybrid,true", "persistent,false", "persistent,true"})
    void savedDeletionRetainsReceiptWithoutRestoringTheDeletedWorkspace(String mode, boolean changeAlias) {
        Map<String, StorageBackend<String, ?>> originalStores = new HashMap<>();
        Map<String, StorageBackend<String, ?>> restoredStores = new HashMap<>();
        try {
            ApsService first = service(directory, mode, originalStores);
            PrometheusWorkspace initial = first.createWorkspace(REGION, "original", Map.of("team", "one"), null, "deleted");
            if (changeAlias) {
                first.updateWorkspaceAlias(REGION, initial.getWorkspaceId(), "updated");
            }
            first.deleteWorkspace(REGION, initial.getWorkspaceId());
            originalStores.values().forEach(StorageBackend::flush);

            ApsService restarted = service(directory, mode, restoredStores);
            PrometheusWorkspace replay = restarted.createWorkspace(REGION, "original", Map.of("team", "one"), null, "deleted");
            assertEquals(initial.getWorkspaceId(), replay.getWorkspaceId());
            assertEquals("original", replay.getAlias());
            assertFalse(restarted.listWorkspaces(REGION, null, null, null).items().stream()
                    .anyMatch(workspace -> workspace.getWorkspaceId().equals(initial.getWorkspaceId())));
        } finally {
            shutdown(originalStores);
            shutdown(restoredStores);
        }
    }

    private ApsService service(Path path, String mode, Map<String, StorageBackend<String, ?>> stores) {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any())).thenAnswer(call ->
                store(path, mode, call.getArgument(1), call.getArgument(2), stores));
        return new ApsService(factory, new RegionResolver(REGION, ACCOUNT));
    }

    private <T> AccountAwareStorageBackend<T> store(Path path, String mode, String name, TypeReference<Map<String, T>> type,
                                                  Map<String, StorageBackend<String, ?>> stores) {
        StorageBackend<String, T> backend = "hybrid".equals(mode)
                ? new HybridStorage<>(path.resolve(name), type, 3_600_000)
                : new PersistentStorage<>(path.resolve(name), type);
        backend.load();
        stores.put(name, backend);
        return new AccountAwareStorageBackend<>(backend, null, ACCOUNT);
    }

    private void shutdown(Map<String, StorageBackend<String, ?>> stores) {
        stores.values().forEach(backend -> {
            if (backend instanceof HybridStorage<?, ?> hybrid) {
                hybrid.shutdown();
            }
        });
    }
}
