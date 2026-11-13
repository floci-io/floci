package io.floci.aws.core.storage;

import io.floci.aws.core.common.RequestContext;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageOperationsTest {

    @Test
    void deleteByPrefixDeletesOnlyMatchingKeys() {
        InMemoryStorage<String, String> storage = new InMemoryStorage<>();
        storage.put("app::env::1", "first");
        storage.put("app::env::2", "second");
        storage.put("app::other::1", "other");

        StorageOperations.deleteByPrefix(storage, "app::env::");

        assertEquals(1, storage.keys().size());
        assertEquals("other", storage.get("app::other::1").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"111111111111", "222222222222"})
    void atomicDeletionKeepsLegacyOwnershipAndOtherAccounts(String caller) {
        InMemoryStorage<String, String> raw = spy(new InMemoryStorage<>());
        raw.putAll(Map.of(
                "db:legacy", "db",
                "111111111111/db:current", "db",
                "222222222222/db:current", "db",
                "111111111111/db:neighbor:kept", "neighbor"));
        RequestContext request = new RequestContext();
        request.setAccountId(caller);
        @SuppressWarnings("unchecked")
        Instance<RequestContext> context = mock(Instance.class);
        when(context.get()).thenReturn(request);
        AccountAwareStorageBackend<String> storage = new AccountAwareStorageBackend<>(raw, context, "111111111111");
        clearInvocations(raw);

        StorageOperations.deleteMatchingAtomically(storage, "db"::equals);

        Set<String> removed = caller.equals("111111111111")
                ? Set.of("db:legacy", "111111111111/db:current") : Set.of("222222222222/db:current");
        Set<String> retained = caller.equals("111111111111")
                ? Set.of("222222222222/db:current", "111111111111/db:neighbor:kept")
                : Set.of("db:legacy", "111111111111/db:current", "111111111111/db:neighbor:kept");
        assertEquals(retained, raw.keys());
        verify(raw).deleteAllAtomically(removed);
        verify(raw, never()).delete(any());
        verify(raw, never()).put(any(), any());
    }

    @Test
    void failedAtomicDeletionDoesNotMigrateLegacyEntries() {
        InMemoryStorage<String, String> raw = spy(new InMemoryStorage<>());
        raw.putAll(Map.of("db:legacy", "db", "111111111111/db:current", "db"));
        Set<String> keys = raw.keys();
        AccountAwareStorageBackend<String> storage = new AccountAwareStorageBackend<>(raw, null, "111111111111");
        IllegalStateException failure = new IllegalStateException("storage commit refused");
        doThrow(failure).when(raw).deleteAllAtomically(any());
        clearInvocations(raw);

        assertEquals(failure, assertThrows(IllegalStateException.class,
                () -> StorageOperations.deleteMatchingAtomically(storage, "db"::equals)));

        assertEquals(keys, raw.keys());
        verify(raw, never()).put(any(), any());
        verify(raw, never()).delete(any());
    }
}
