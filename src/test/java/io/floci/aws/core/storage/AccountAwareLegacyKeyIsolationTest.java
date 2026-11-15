package io.floci.aws.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class AccountAwareLegacyKeyIsolationTest {
    private static final String DEFAULT_ACCOUNT = "111111111111";

    @ParameterizedTest
    @ValueSource(strings = {"111111111111", "222222222222"})
    void ambientPrefixAliasCannotReadOrMoveAnAccountScopedRawRecord(String owner) {
        InMemoryStorage<String, OwnedValue> raw = spy(new InMemoryStorage<>());
        AccountAwareStorageBackend<OwnedValue> storage =
                new AccountAwareStorageBackend<>(raw, null, DEFAULT_ACCOUNT);
        String alias = owner + "/resource";
        OwnedValue value = new OwnedValue(owner, "original");
        raw.put(alias, value);
        clearInvocations(raw);

        Optional<OwnedValue> actual = storage.get(alias);

        assertAll(
                () -> assertTrue(actual.isEmpty()),
                () -> assertEquals(Set.of(alias), raw.keys()),
                () -> assertSame(value, raw.scan(alias::equals).stream().findFirst().orElse(null)),
                () -> verify(raw, never()).get(alias));
    }

    @ParameterizedTest
    @ValueSource(strings = {"111111111111", "222222222222"})
    void singleKeyMigrationCannotReadOrMoveAnAccountScopedRawRecord(String owner) {
        InMemoryStorage<String, OwnedValue> raw = spy(new InMemoryStorage<>());
        AccountAwareStorageBackend<OwnedValue> storage =
                new AccountAwareStorageBackend<>(raw, null, DEFAULT_ACCOUNT);
        String alias = owner + "/resource";
        OwnedValue value = new OwnedValue(owner, "original");
        raw.put(alias, value);
        clearInvocations(raw);

        Optional<OwnedValue> actual = storage.getForAccountMigratingLegacy(
                DEFAULT_ACCOUNT, alias, candidate -> true);

        assertAll(
                () -> assertTrue(actual.isEmpty()),
                () -> assertEquals(Set.of(alias), raw.keys()),
                () -> assertSame(value, raw.scan(alias::equals).stream().findFirst().orElse(null)),
                () -> verify(raw, never()).get(alias));
    }

    @ParameterizedTest
    @ValueSource(strings = {"111111111111", "222222222222"})
    void ambientLookupPreservesExplicitScopedPrefixShapedLogicalKeys(String prefix) {
        InMemoryStorage<String, OwnedValue> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<OwnedValue> storage =
                new AccountAwareStorageBackend<>(raw, null, DEFAULT_ACCOUNT);
        String logicalKey = prefix + "/resource";
        OwnedValue scoped = new OwnedValue(DEFAULT_ACCOUNT, "scoped");
        OwnedValue other = new OwnedValue(prefix, "other raw record");
        storage.put(logicalKey, scoped);
        raw.put(logicalKey, other);

        assertSame(scoped, storage.get(logicalKey).orElseThrow());
        assertSame(other, raw.get(logicalKey).orElseThrow());
        assertEquals(Set.of(logicalKey, DEFAULT_ACCOUNT + "/" + logicalKey), raw.keys());
    }

    @ParameterizedTest
    @ValueSource(strings = {"111111111111", "222222222222"})
    void singleKeyLookupPreservesExplicitScopedPrefixShapedLogicalKeys(String prefix) {
        InMemoryStorage<String, OwnedValue> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<OwnedValue> storage =
                new AccountAwareStorageBackend<>(raw, null, DEFAULT_ACCOUNT);
        String logicalKey = prefix + "/resource";
        OwnedValue scoped = new OwnedValue(DEFAULT_ACCOUNT, "scoped");
        OwnedValue other = new OwnedValue(prefix, "other raw record");
        storage.putForAccount(DEFAULT_ACCOUNT, logicalKey, scoped);
        raw.put(logicalKey, other);

        assertSame(scoped, storage.getForAccountMigratingLegacy(
                DEFAULT_ACCOUNT, logicalKey, candidate -> false).orElseThrow());
        assertSame(other, raw.get(logicalKey).orElseThrow());
        assertEquals(Set.of(logicalKey, DEFAULT_ACCOUNT + "/" + logicalKey), raw.keys());
    }

    @Test
    void ambientLookupStillMigratesGenuineDefaultAccountLegacyData() {
        InMemoryStorage<String, OwnedValue> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<OwnedValue> storage =
                new AccountAwareStorageBackend<>(raw, null, DEFAULT_ACCOUNT);
        OwnedValue legacy = new OwnedValue(DEFAULT_ACCOUNT, "legacy");
        raw.put("legacy/path", legacy);

        assertSame(legacy, storage.get("legacy/path").orElseThrow());
        assertEquals(Set.of(DEFAULT_ACCOUNT + "/legacy/path"), raw.keys());
        assertSame(legacy, raw.get(DEFAULT_ACCOUNT + "/legacy/path").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"111111111111", "222222222222"})
    void singleKeyMigrationRetainsProvenLegacyOwnersIncludingNondefaultAccounts(String owner) {
        InMemoryStorage<String, OwnedValue> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<OwnedValue> storage =
                new AccountAwareStorageBackend<>(raw, null, DEFAULT_ACCOUNT);
        OwnedValue legacy = new OwnedValue(owner, "legacy");
        raw.put("legacy/path", legacy);

        assertSame(legacy, storage.getForAccountMigratingLegacy(
                owner, "legacy/path", candidate -> owner.equals(candidate.owner())).orElseThrow());
        assertEquals(Set.of(owner + "/legacy/path"), raw.keys());
        assertSame(legacy, raw.get(owner + "/legacy/path").orElseThrow());
    }

    @Test
    void singleKeyMigrationPreservesLegacyDataWhenOwnerPredicateRejectsIt() {
        InMemoryStorage<String, OwnedValue> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<OwnedValue> storage =
                new AccountAwareStorageBackend<>(raw, null, DEFAULT_ACCOUNT);
        OwnedValue legacy = new OwnedValue("222222222222", "legacy");
        raw.put("resource", legacy);

        assertTrue(storage.getForAccountMigratingLegacy(DEFAULT_ACCOUNT, "resource",
                candidate -> DEFAULT_ACCOUNT.equals(candidate.owner())).isEmpty());
        assertEquals(Set.of("resource"), raw.keys());
        assertSame(legacy, raw.get("resource").orElseThrow());
    }

    @Test
    void pluralMigrationStillAcceptsAnIntentionalKnownAccountScopedLegacyKey() {
        InMemoryStorage<String, OwnedValue> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<OwnedValue> storage =
                new AccountAwareStorageBackend<>(raw, null, DEFAULT_ACCOUNT);
        OwnedValue legacy = new OwnedValue(DEFAULT_ACCOUNT, "legacy");
        raw.put(DEFAULT_ACCOUNT + "/resource", legacy);

        assertSame(legacy, storage.getForAccountMigratingLegacyKeys(
                DEFAULT_ACCOUNT, "us-west-2::resource", List.of("resource"),
                candidate -> DEFAULT_ACCOUNT.equals(candidate.owner())).orElseThrow());
        assertEquals(Set.of(DEFAULT_ACCOUNT + "/us-west-2::resource"), raw.keys());
        assertSame(legacy, raw.get(DEFAULT_ACCOUNT + "/us-west-2::resource").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedMigrationWritePreservesTheGenuineLegacyRecord(boolean explicitAccount) {
        InMemoryStorage<String, OwnedValue> raw = spy(new InMemoryStorage<>());
        AccountAwareStorageBackend<OwnedValue> storage =
                new AccountAwareStorageBackend<>(raw, null, DEFAULT_ACCOUNT);
        OwnedValue legacy = new OwnedValue(DEFAULT_ACCOUNT, "legacy");
        raw.put("resource", legacy);
        doThrow(new IllegalStateException("injected pre-write failure"))
                .when(raw).put(DEFAULT_ACCOUNT + "/resource", legacy);

        assertThrows(IllegalStateException.class, () -> {
            if (explicitAccount) {
                storage.getForAccountMigratingLegacy(DEFAULT_ACCOUNT, "resource",
                        candidate -> DEFAULT_ACCOUNT.equals(candidate.owner()));
            } else {
                storage.get("resource");
            }
        });

        assertEquals(Set.of("resource"), raw.keys());
        assertSame(legacy, raw.get("resource").orElseThrow());
    }

    private record OwnedValue(String owner, String marker) {}
}
