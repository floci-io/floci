package io.github.hectorvent.floci.core.storage;

import io.github.hectorvent.floci.core.common.RequestContext;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;

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
     * storage key. Unlike {@link #scanAllAccountsAsMap()}, the account segment is preserved
     * rather than stripped, so entries that share a logical key across different accounts
     * (e.g. two accounts each owning a resource named "orders") remain distinguishable.
     *
     * <p>Entries with no account segment at all — persisted before multi-account support was
     * added — are attributed to {@code defaultAccountId} rather than skipped, mirroring how
     * {@link #get} treats them on a per-key lookup. This keeps a bulk startup reload (which has
     * no per-key caller to trigger that migrate-on-read path) from silently losing pre-existing
     * data on upgrade.
     */
    public Map<String, V> scanAllAccountsRaw() {
        Map<String, V> result = new LinkedHashMap<>();
        for (String rawKey : delegate.keys()) {
            String effectiveKey = rawKey.indexOf('/') < 0 ? defaultAccountId + "/" + rawKey : rawKey;
            delegate.get(rawKey).ifPresent(v -> result.put(effectiveKey, v));
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
