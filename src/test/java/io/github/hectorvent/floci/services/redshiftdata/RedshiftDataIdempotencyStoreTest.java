package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.HybridStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.WalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RedshiftDataIdempotencyStoreTest {

    private static final Instant START = Instant.parse("2026-10-23T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void expirySweepDoesNotRewriteTheFileForEveryReceipt(@TempDir Path directory) {
        PersistentStorage<String, RedshiftDataIdempotencyStore.Receipt> delegate = spy(new PersistentStorage<>(
                directory.resolve("idempotency.json"), new TypeReference<Map<String, RedshiftDataIdempotencyStore.Receipt>>() { }));
        AccountAwareStorageBackend<RedshiftDataIdempotencyStore.Receipt> backend =
                new AccountAwareStorageBackend<>(delegate, null, "000000000000");
        RedshiftDataIdempotencyStore initial = store(backend, START);
        for (int i = 0; i < 25; i++) {
            initial.put(scope(i % 2 == 0 ? "000000000000" : "111111111111", "us-east-1", "ExecuteStatement", "token-" + i),
                    mapper.createObjectNode().put("Id", "statement-" + i));
        }
        clearInvocations(delegate);
        store(backend, START.plus(Duration.ofHours(8))).sweep();
        assertTrue(backend.scanAllAccountEntries(key -> true).isEmpty());
        verify(delegate, never()).delete(any());
        verify(delegate, never()).flush();
        verify(delegate, times(1)).deleteAllAtomically(any());
    }

    @Test
    void sweepSnapshotDoesNotBlockNewReceiptsOrDeleteAReplacement() throws Exception {
        AccountAwareStorageBackend<RedshiftDataIdempotencyStore.Receipt> backend =
                spy(AccountAwareStorageBackend.<RedshiftDataIdempotencyStore.Receipt>inMemory("000000000000"));
        RedshiftDataIdempotencyStore.Scope scope = scope("000000000000", "us-east-1", "ExecuteStatement", "token");
        store(backend, START).put(scope, mapper.createObjectNode().put("Id", "expired"));
        RedshiftDataIdempotencyStore later = store(backend, START.plus(Duration.ofHours(8)));
        CountDownLatch scanned = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object entries = invocation.callRealMethod();
            scanned.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return entries;
        }).when(backend).scanAllAccountEntries(any());
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> sweep = workers.submit(later::sweep);
            try {
                assertTrue(scanned.await(5, TimeUnit.SECONDS));
                Future<?> replacement = workers.submit(() -> later.put(scope, mapper.createObjectNode().put("Id", "replacement")));
                replacement.get(2, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            sweep.get(5, TimeUnit.SECONDS);
            assertEquals("replacement", later.find(scope).response.path("Id").asText());
        }
    }

    @Test
    void deadlineDoesNotSlideAndExpiresAtEightHours() {
        AccountAwareStorageBackend<RedshiftDataIdempotencyStore.Receipt> backend =
                AccountAwareStorageBackend.inMemory("000000000000");
        RedshiftDataIdempotencyStore initial = store(backend, START);
        RedshiftDataIdempotencyStore.Scope scope = scope("000000000000", "us-east-1", "ExecuteStatement", "token");
        initial.put(scope, mapper.createObjectNode().put("Id", "original"));
        RedshiftDataIdempotencyStore later = store(backend, START.plus(Duration.ofHours(8)).minusNanos(1));
        assertEquals("original", later.find(scope).response.path("Id").asText());
        assertEquals(START.plus(Duration.ofHours(8)), later.find(scope).expiresAt);
        assertNull(store(backend, START.plus(Duration.ofHours(8))).find(scope));
    }

    @Test
    void scopesCannotCollideAndReceiptsAreIndependentSnapshots() {
        RedshiftDataIdempotencyStore store = store(AccountAwareStorageBackend.inMemory("000000000000"), START);
        RedshiftDataIdempotencyStore.Scope original = scope("000000000000", "us-east-1", "ExecuteStatement", "x:y/ z");
        ObjectNode response = mapper.createObjectNode().put("Id", "original");
        store.put(original, response);
        response.put("Id", "mutated");
        assertEquals("original", store.find(original).response.path("Id").asText());
        store.find(original).response.put("Id", "mutated-read");
        assertEquals("original", store.find(original).response.path("Id").asText());
        assertNull(store.find(scope("111111111111", "us-east-1", "ExecuteStatement", "x:y/ z")));
        assertNull(store.find(scope("000000000000", "eu-west-1", "ExecuteStatement", "x:y/ z")));
        assertNull(store.find(scope("000000000000", "us-east-1", "BatchExecuteStatement", "x:y/ z")));
        assertNull(store.find(scope("000000000000", "us-east-1", "ExecuteStatement", "X:y/ z")));
    }

    @Test
    void staleRemovalCannotDeleteAReplacementReceipt() {
        RedshiftDataIdempotencyStore store = store(AccountAwareStorageBackend.inMemory("000000000000"), START);
        RedshiftDataIdempotencyStore.Scope scope = scope("000000000000", "us-east-1", "ExecuteStatement", "token");
        store.put(scope, mapper.createObjectNode().put("Id", "new"));
        store.removeIfStatement(scope, "old");
        assertNotNull(store.find(scope));
        store.removeIfStatement(scope, "new");
        assertNull(store.find(scope));
    }

    @Test
    void sweepAndClearCoverEveryAccount() {
        AccountAwareStorageBackend<RedshiftDataIdempotencyStore.Receipt> backend =
                AccountAwareStorageBackend.inMemory("000000000000");
        RedshiftDataIdempotencyStore store = store(backend, START);
        store.put(scope("000000000000", "us-east-1", "ExecuteStatement", "a"), mapper.createObjectNode().put("Id", "a"));
        store.put(scope("111111111111", "us-east-1", "ExecuteStatement", "a"), mapper.createObjectNode().put("Id", "b"));
        store(backend, START.plus(Duration.ofHours(8))).sweep();
        assertTrue(backend.scanAllAccountEntries(key -> true).isEmpty());
        store.put(scope("111111111111", "us-east-1", "ExecuteStatement", "a"), mapper.createObjectNode().put("Id", "c"));
        store.clear();
        assertTrue(backend.scanAllAccountEntries(key -> true).isEmpty());
    }

    @Test
    void receiptsSurvivePersistentBackendReopen(@TempDir Path directory) {
        Path file = directory.resolve("idempotency.json");
        RedshiftDataIdempotencyStore.Scope scope = scope("111111111111", "cn-north-1", "ExecuteStatement", "token");
        RedshiftDataIdempotencyStore original = store(persistent(file), START);
        assertNull(original.find(scope));
        original.put(scope, mapper.createObjectNode().put("Id", "persisted").put("CreatedAt", 123.25));
        RedshiftDataIdempotencyStore reopened = store(persistent(file), START.plusSeconds(60));
        assertEquals("persisted", reopened.find(scope).response.path("Id").asText());
        assertEquals(123.25, reopened.find(scope).response.path("CreatedAt").asDouble());
        assertEquals(START.plus(Duration.ofHours(8)), reopened.find(scope).expiresAt);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hybrid", "wal"})
    void receiptsSurviveWriteBehindBackendRestart(String mode, @TempDir Path directory) {
        RedshiftDataIdempotencyStore.Scope scope = scope("111111111111", "us-east-1", "ExecuteStatement", "token");
        StorageBackend<String, RedshiftDataIdempotencyStore.Receipt> initial = writeBehind(mode, directory);
        initial.load();
        try {
            store(new AccountAwareStorageBackend<>(initial, null, "000000000000"), START)
                    .put(scope, mapper.createObjectNode().put("Id", "persisted"));
        } finally {
            shutdown(initial);
        }
        StorageBackend<String, RedshiftDataIdempotencyStore.Receipt> reopened = writeBehind(mode, directory);
        reopened.load();
        try {
            assertEquals("persisted", store(new AccountAwareStorageBackend<>(reopened, null, "000000000000"), START)
                    .find(scope).response.path("Id").asText());
        } finally {
            shutdown(reopened);
        }
    }

    private StorageBackend<String, RedshiftDataIdempotencyStore.Receipt> writeBehind(String mode, Path directory) {
        TypeReference<Map<String, RedshiftDataIdempotencyStore.Receipt>> type = new TypeReference<>() { };
        return switch (mode) {
            case "hybrid" -> new HybridStorage<>(directory.resolve("idempotency.json"), type, 60000);
            case "wal" -> new WalStorage<>(directory.resolve("idempotency.json"), directory.resolve("idempotency.wal"), type, 60000);
            default -> throw new IllegalArgumentException(mode);
        };
    }

    private void shutdown(StorageBackend<String, RedshiftDataIdempotencyStore.Receipt> backend) {
        if (backend instanceof HybridStorage<String, RedshiftDataIdempotencyStore.Receipt> hybrid) {
            hybrid.shutdown();
        } else if (backend instanceof WalStorage<String, RedshiftDataIdempotencyStore.Receipt> wal) {
            wal.shutdown();
        }
    }

    private AccountAwareStorageBackend<RedshiftDataIdempotencyStore.Receipt> persistent(Path file) {
        PersistentStorage<String, RedshiftDataIdempotencyStore.Receipt> delegate = new PersistentStorage<>(file,
                new TypeReference<Map<String, RedshiftDataIdempotencyStore.Receipt>>() { });
        delegate.load();
        return new AccountAwareStorageBackend<>(delegate, null, "000000000000");
    }

    private RedshiftDataIdempotencyStore store(AccountAwareStorageBackend<RedshiftDataIdempotencyStore.Receipt> backend,
                                              Instant now) {
        return new RedshiftDataIdempotencyStore(backend, Clock.fixed(now, ZoneOffset.UTC));
    }

    private RedshiftDataIdempotencyStore.Scope scope(String account, String region, String operation, String token) {
        return new RedshiftDataIdempotencyStore.Scope(account, region, operation, token);
    }
}
