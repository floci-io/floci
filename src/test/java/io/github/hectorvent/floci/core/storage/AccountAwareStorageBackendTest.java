package io.github.hectorvent.floci.core.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    @Test
    void scanAllAccountsRawAttributesLegacyUnprefixedKeysToDefaultAccount() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        // Simulates data persisted before multi-account support existed: no account
        // segment at all, unlike every key AccountAwareStorageBackend itself writes.
        raw.put("us-east-1::LegacyTable", "legacy-value");
        raw.put("111111111111/us-east-1::NewTable", "new-value");

        AccountAwareStorageBackend<String> aware = new AccountAwareStorageBackend<>(raw, null, "000000000000");

        Map<String, String> result = aware.scanAllAccountsRaw();

        assertEquals(2, result.size());
        assertEquals("legacy-value", result.get("000000000000/us-east-1::LegacyTable"),
                "a pre-multi-account key must be attributed to the default account, not dropped");
        assertEquals("new-value", result.get("111111111111/us-east-1::NewTable"));
        assertTrue(result.keySet().stream().allMatch(k -> k.indexOf('/') >= 0),
                "every returned key must carry an account segment");
    }

    @Test
    void scanAllAccountsRawMigratesLegacyKeyIntoUnderlyingStorage() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        raw.put("us-east-1::LegacyTable", "legacy-value");

        AccountAwareStorageBackend<String> aware = new AccountAwareStorageBackend<>(raw, null, "000000000000");
        aware.scanAllAccountsRaw();

        assertEquals(Optional.empty(), raw.get("us-east-1::LegacyTable"),
                "the bare legacy key must not be left sitting in storage after being migrated");
        assertEquals(Optional.of("legacy-value"), raw.get("000000000000/us-east-1::LegacyTable"));
    }

    @Test
    void scanAllAccountsRawPrefersAlreadyPrefixedEntryOverStaleLegacyKeyAndDeletesTheStaleOne() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        // A legacy key already superseded by a real write under its proper prefix.
        raw.put("us-east-1::Orders", "stale-legacy-value");
        raw.put("000000000000/us-east-1::Orders", "current-value");

        AccountAwareStorageBackend<String> aware = new AccountAwareStorageBackend<>(raw, null, "000000000000");
        Map<String, String> result = aware.scanAllAccountsRaw();

        assertEquals(1, result.size());
        assertEquals("current-value", result.get("000000000000/us-east-1::Orders"),
                "the already-prefixed, current entry must win over a stale legacy duplicate");
        assertEquals(Optional.empty(), raw.get("us-east-1::Orders"),
                "the superseded legacy key must be deleted, not left to collide again later");
    }

    @Test
    void scanAllAccountEntriesPreservesOwnersAndTreatsNonAwsPrefixesAsLegacy() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        raw.put("123456789012/us-east-1::api-a", "account-a");
        raw.put("210987654321/us-east-1::api-b", "account-b");
        raw.put("legacy/path", "legacy");
        raw.put("12345678901/short", "short-prefix");
        raw.put("1234567890123/long", "long-prefix");
        raw.put("１２３４５６７８９０１２/unicode", "unicode-prefix");

        AccountAwareStorageBackend<String> storage =
                new AccountAwareStorageBackend<>(raw, null, "000000000000");

        List<AccountAwareStorageBackend.AccountEntry<String>> entries =
                storage.scanAllAccountEntries(key -> true);

        assertEquals(Set.of(
                new AccountAwareStorageBackend.AccountEntry<>("123456789012", "us-east-1::api-a", "account-a"),
                new AccountAwareStorageBackend.AccountEntry<>("210987654321", "us-east-1::api-b", "account-b"),
                new AccountAwareStorageBackend.AccountEntry<>("000000000000", "legacy/path", "legacy"),
                new AccountAwareStorageBackend.AccountEntry<>("000000000000", "12345678901/short", "short-prefix"),
                new AccountAwareStorageBackend.AccountEntry<>("000000000000", "1234567890123/long", "long-prefix"),
                new AccountAwareStorageBackend.AccountEntry<>(
                        "000000000000", "１２３４５６７８９０１２/unicode", "unicode-prefix")),
                Set.copyOf(entries));
    }

    @Test
    void scanAllAccountEntriesFiltersAccountRelativeKeys() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        raw.put("123456789012/us-east-1::api-a", "account-a");
        raw.put("210987654321/eu-west-1::api-b", "account-b");
        raw.put("us-east-1::legacy", "legacy");

        AccountAwareStorageBackend<String> storage =
                new AccountAwareStorageBackend<>(raw, null, "000000000000");

        assertEquals(Set.of(
                new AccountAwareStorageBackend.AccountEntry<>("123456789012", "us-east-1::api-a", "account-a"),
                new AccountAwareStorageBackend.AccountEntry<>("000000000000", "us-east-1::legacy", "legacy")),
                Set.copyOf(storage.scanAllAccountEntries(key -> key.startsWith("us-east-1::"))));
    }
}
