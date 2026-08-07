package io.github.hectorvent.floci.core.storage;

import io.github.hectorvent.floci.core.common.RequestContext;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Decorator over {@link StorageBackend} that transparently prefixes every storage key
 * with the current account ID, providing per-account resource isolation.
 *
 * <p>On the synchronous request path the account ID is read from {@link RequestContext},
 * which is populated by {@code AccountContextFilter} before any handler runs.
 * Outside a request (async workers, startup) the {@code defaultAccountId} is used.
 *
 * <p>Async workers that must access a specific account's data should use the explicit
 * {@code *ForAccount} overloads, passing the account ID stored on the resource model.
 *
 * <p>Backward compatibility: on a {@link #get} miss for the prefixed key, the un-prefixed
 * key is tried and the entry is migrated on read. This covers existing persistent/WAL data
 * created before multi-account support was added.
 */
public class AccountAwareStorageBackend<V> implements StorageBackend<String, V> {

    private final StorageBackend<String, V> delegate;
    private final Instance<RequestContext> requestContextInstance;
    private final String defaultAccountId;

    public AccountAwareStorageBackend(StorageBackend<String, V> delegate,
                                      Instance<RequestContext> requestContextInstance,
                                      String defaultAccountId) {
        this.delegate = delegate;
        this.requestContextInstance = requestContextInstance;
        this.defaultAccountId = defaultAccountId;
    }

    @Override
    public void put(String key, V value) {
        delegate.put(prefixed(key), value);
    }

    @Override
    public Optional<V> get(String key) {
        String prefixedKey = prefixed(key);
        Optional<V> result = delegate.get(prefixedKey);
        if (result.isPresent()) {
            return result;
        }
        // Backward-compat: try un-prefixed key (pre-multi-account data) and migrate on read.
        result = delegate.get(key);
        if (result.isPresent()) {
            delegate.put(prefixedKey, result.get());
            delegate.delete(key);
        }
        return result;
    }

    @Override
    public void delete(String key) {
        delegate.delete(prefixed(key));
    }

    @Override
    public List<V> scan(Predicate<String> keyFilter) {
        String prefix = prefix() + "/";
        return delegate.scan(k -> k.startsWith(prefix) && keyFilter.test(k.substring(prefix.length())));
    }

    @Override
    public Set<String> keys() {
        String prefix = prefix() + "/";
        return delegate.keys().stream()
                .filter(k -> k.startsWith(prefix))
                .map(k -> k.substring(prefix.length()))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public void load() {
        delegate.load();
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    // --- Explicit-account methods for async workers ---

    /** Scans all values across every account, without any account prefix filtering. */
    public List<V> scanAllAccounts() {
        return delegate.scan(k -> true);
    }

    /**
     * Returns every stored entry with its raw delegate key intact.
     *
     * <p>Startup migrations use this form when the key itself is part of a resource's identity.
     * Keeping the account prefix prevents a value stored under a corrupt or foreign key from
     * being re-keyed solely from mutable fields in the value.
     */
    public Map<String, V> scanAllAccountsWithRawKeys() {
        Map<String, V> result = new LinkedHashMap<>();
        for (String rawKey : delegate.keys()) {
            delegate.get(rawKey).ifPresent(value -> result.put(rawKey, value));
        }
        return result;
    }

    /**
     * Returns all entries across every account as a map of logical-key (account prefix stripped)
     * to value. Entries without a slash-prefixed account segment are skipped.
     */
    public Map<String, V> scanAllAccountsAsMap() {
        Map<String, V> result = new LinkedHashMap<>();
        for (String rawKey : delegate.keys()) {
            int slash = rawKey.indexOf('/');
            if (slash < 0) {
                continue;
            }
            String logicalKey = rawKey.substring(slash + 1);
            delegate.get(rawKey).ifPresent(v -> result.put(logicalKey, v));
        }
        return result;
    }

    public Optional<V> getForAccount(String accountId, String key) {
        return delegate.get(accountId + "/" + key);
    }

    /**
     * Returns an explicitly scoped entry, or safely migrates a matching legacy unscoped entry.
     *
     * <p>The caller-supplied predicate must establish that the legacy value belongs to the
     * destination account. This opt-in path avoids {@link #get(String)}'s request-context fallback,
     * which cannot validate ownership before migrating old data.
     */
    public synchronized Optional<V> getForAccountMigratingLegacy(
            String accountId, String key, Predicate<V> legacyOwner) {
        Optional<V> scoped = getForAccount(accountId, key);
        if (scoped.isPresent()) {
            return scoped;
        }
        Optional<V> legacy = delegate.get(key).filter(legacyOwner);
        if (legacy.isEmpty()) {
            return Optional.empty();
        }
        delegate.put(accountId + "/" + key, legacy.get());
        delegate.delete(key);
        return legacy;
    }

    /**
     * Returns an explicitly scoped entry, or safely migrates one of several older key shapes.
     *
     * <p>This is intended for resources whose logical key gained another scope component after
     * account isolation was already deployed. Both account-prefixed legacy keys and older
     * unprefixed keys are considered, but only when {@code legacyOwner} proves that the stored
     * value belongs to the requested destination scope. The destination is written before the
     * source is removed so a failed write cannot orphan the legacy value.
     */
    public synchronized Optional<V> getForAccountMigratingLegacyKeys(
            String accountId, String key, List<String> legacyKeys,
            Predicate<V> legacyOwner) {
        return getForAccountMigratingLegacyKeys(
                accountId, key, legacyKeys, legacyOwner, true);
    }

    /**
     * Variant of {@link #getForAccountMigratingLegacyKeys(String, String, List, Predicate)} that
     * can exclude unprefixed legacy keys when the value cannot prove account ownership.
     */
    public synchronized Optional<V> getForAccountMigratingLegacyKeys(
            String accountId, String key, List<String> legacyKeys,
            Predicate<V> legacyOwner, boolean includeUnscopedLegacyKeys) {
        String destinationKey = accountId + "/" + key;
        Set<String> candidates = new LinkedHashSet<>();
        if (includeUnscopedLegacyKeys) {
            candidates.add(key);
        }
        if (legacyKeys != null) {
            for (String legacyKey : legacyKeys) {
                if (legacyKey == null || legacyKey.isBlank()) {
                    continue;
                }
                candidates.add(accountId + "/" + legacyKey);
                if (includeUnscopedLegacyKeys) {
                    candidates.add(legacyKey);
                }
            }
        }
        candidates.remove(destinationKey);

        Optional<V> scoped = delegate.get(destinationKey);
        if (scoped.isPresent()) {
            if (scoped.filter(legacyOwner).isEmpty()) {
                // A value under the destination key that does not belong to the requested
                // scope is corrupt. Preserve both it and every legacy candidate so the
                // caller fails closed without deleting the recoverable owned value.
                return Optional.empty();
            }
            for (String candidateKey : candidates) {
                if (delegate.get(candidateKey).filter(legacyOwner).isPresent()) {
                    delegate.delete(candidateKey);
                }
            }
            return scoped;
        }

        for (String candidateKey : candidates) {
            Optional<V> legacy = delegate.get(candidateKey).filter(legacyOwner);
            if (legacy.isEmpty()) {
                continue;
            }
            delegate.put(destinationKey, legacy.get());
            delegate.delete(candidateKey);
            return legacy;
        }
        return Optional.empty();
    }

    public void putForAccount(String accountId, String key, V value) {
        delegate.put(accountId + "/" + key, value);
    }

    public void deleteForAccount(String accountId, String key) {
        delegate.delete(accountId + "/" + key);
    }

    public List<V> scanForAccount(String accountId, Predicate<String> keyFilter) {
        String prefix = accountId + "/";
        return delegate.scan(k -> k.startsWith(prefix) && keyFilter.test(k.substring(prefix.length())));
    }

    /** Returns unprefixed pre-account values that satisfy an ownership predicate. */
    public List<V> scanUnscopedLegacy(Predicate<V> legacyOwner) {
        return delegate.scan(k -> !k.contains("/")).stream()
                .filter(legacyOwner)
                .toList();
    }

    public Set<String> keysForAccount(String accountId) {
        String prefix = accountId + "/";
        return delegate.keys().stream()
                .filter(k -> k.startsWith(prefix))
                .map(k -> k.substring(prefix.length()))
                .collect(Collectors.toUnmodifiableSet());
    }

    // ---

    private String prefix() {
        if (requestContextInstance != null) {
            try {
                String accountId = requestContextInstance.get().getAccountId();
                if (accountId != null) {
                    return accountId;
                }
            } catch (ContextNotActiveException ignored) {
                // outside request scope — fall through to default
            }
        }
        return defaultAccountId;
    }

    private String prefixed(String key) {
        return prefix() + "/" + key;
    }
}
