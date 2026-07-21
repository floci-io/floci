package io.github.hectorvent.floci.core.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountAwareStorageBackendTest {

    @Test
    void guardedLegacyLookupMigratesMatchingRawValue() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        raw.put("resource", "owned");
        AccountAwareStorageBackend<String> storage =
                new AccountAwareStorageBackend<>(raw, null, "111111111111");

        assertEquals("owned", storage.getForAccountMigratingLegacy(
                "111111111111", "resource", "owned"::equals).orElseThrow());
        assertTrue(raw.get("resource").isEmpty());
        assertEquals("owned", raw.get("111111111111/resource").orElseThrow());
    }

    @Test
    void guardedLegacyLookupLeavesRejectedRawValueUntouched() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        raw.put("resource", "foreign");
        AccountAwareStorageBackend<String> storage =
                new AccountAwareStorageBackend<>(raw, null, "111111111111");

        assertTrue(storage.getForAccountMigratingLegacy(
                "111111111111", "resource", "owned"::equals).isEmpty());
        assertEquals("foreign", raw.get("resource").orElseThrow());
        assertTrue(raw.get("111111111111/resource").isEmpty());
    }

    @Test
    void guardedLegacyLookupPrefersExistingScopedValue() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        raw.put("resource", "legacy");
        raw.put("111111111111/resource", "scoped");
        AccountAwareStorageBackend<String> storage =
                new AccountAwareStorageBackend<>(raw, null, "111111111111");

        assertEquals("scoped", storage.getForAccountMigratingLegacy(
                "111111111111", "resource", value -> true).orElseThrow());
        assertEquals("legacy", raw.get("resource").orElseThrow());
        assertEquals("scoped", raw.get("111111111111/resource").orElseThrow());
    }

    @Test
    void guardedLegacyLookupCanMigrateToExplicitNonDefaultAccount() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        raw.put("resource", "owned");
        AccountAwareStorageBackend<String> storage =
                new AccountAwareStorageBackend<>(raw, null, "111111111111");

        assertEquals("owned", storage.getForAccountMigratingLegacy(
                "222222222222", "resource", value -> true).orElseThrow());
        assertTrue(raw.get("resource").isEmpty());
        assertEquals("owned", raw.get("222222222222/resource").orElseThrow());
    }

    @Test
    void guardedLegacyKeyLookupMigratesOlderAccountScopedKey() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        raw.put("111111111111/resource", "owned");
        AccountAwareStorageBackend<String> storage =
                new AccountAwareStorageBackend<>(raw, null, "111111111111");

        assertEquals("owned", storage.getForAccountMigratingLegacyKeys(
                "111111111111", "us-west-2::resource", List.of("resource"),
                "owned"::equals).orElseThrow());
        assertTrue(raw.get("111111111111/resource").isEmpty());
        assertEquals("owned",
                raw.get("111111111111/us-west-2::resource").orElseThrow());
    }

    @Test
    void guardedLegacyKeyLookupDoesNotClaimWrongScope() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        raw.put("111111111111/resource", "us-east-1");
        AccountAwareStorageBackend<String> storage =
                new AccountAwareStorageBackend<>(raw, null, "111111111111");

        assertTrue(storage.getForAccountMigratingLegacyKeys(
                "111111111111", "us-west-2::resource", List.of("resource"),
                "us-west-2"::equals).isEmpty());
        assertEquals("us-east-1", raw.get("111111111111/resource").orElseThrow());
        assertTrue(raw.get("111111111111/us-west-2::resource").isEmpty());
    }

    @Test
    void guardedLegacyKeyLookupPreservesOwnedLegacyWhenCanonicalHasWrongOwner() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        raw.put("111111111111/us-west-2::resource", "us-east-1");
        raw.put("111111111111/resource", "us-west-2");
        AccountAwareStorageBackend<String> storage =
                new AccountAwareStorageBackend<>(raw, null, "111111111111");

        assertTrue(storage.getForAccountMigratingLegacyKeys(
                "111111111111", "us-west-2::resource", List.of("resource"),
                "us-west-2"::equals).isEmpty());
        assertEquals("us-east-1",
                raw.get("111111111111/us-west-2::resource").orElseThrow());
        assertEquals("us-west-2", raw.get("111111111111/resource").orElseThrow());
    }
}
