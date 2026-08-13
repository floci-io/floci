package io.github.hectorvent.floci.core.storage;

import io.github.hectorvent.floci.core.common.RequestContext;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /**
     * In-memory account-aware store with no request context (uses {@code defaultAccountId}).
     * Useful for unit tests and for callers that need an explicit {@link AccountAwareStorageBackend}.
     */
    public static <V> AccountAwareStorageBackend<V> inMemory(String defaultAccountId) {
        return new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, defaultAccountId);
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

    /**
     * Returns all entries across every account, keyed by the raw {@code accountId/logicalKey}
     * storage key rather than the stripped logical key {@link #scanAllAccountsAsMap()} returns,
     * so entries sharing a logical key across accounts stay distinguishable.
     *
     * <p>A key with no account segment (pre-multi-account data) is attributed to
     * {@code defaultAccountId} rather than skipped, and migrated in place — unlike {@link #get}'s
     * per-key migration, this bulk scan has no caller-supplied key to migrate later, so it acts
     * immediately: already-prefixed keys load first so a legacy key can never win a collision
     * against one already written under its proper prefix, and a superseded legacy key is
     * deleted rather than left to collide again on a later restart.
     */
    public Map<String, V> scanAllAccountsRaw() {
        Map<String, V> result = new LinkedHashMap<>();
        List<String> legacyKeys = new ArrayList<>();
        for (String rawKey : delegate.keys()) {
            if (rawKey.indexOf('/') < 0) {
                legacyKeys.add(rawKey);
                continue;
            }
            delegate.get(rawKey).ifPresent(v -> result.put(rawKey, v));
        }
        for (String rawKey : legacyKeys) {
            String effectiveKey = defaultAccountId + "/" + rawKey;
            if (result.containsKey(effectiveKey)) {
                delegate.delete(rawKey);
                continue;
            }
            delegate.get(rawKey).ifPresent(v -> {
                delegate.put(effectiveKey, v);
                delegate.delete(rawKey);
                result.put(effectiveKey, v);
            });
        }
        return result;
    }

    public Optional<V> getForAccount(String accountId, String key) {
        return delegate.get(accountId + "/" + key);
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
