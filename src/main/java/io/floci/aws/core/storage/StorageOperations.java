package io.floci.aws.core.storage;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

public final class StorageOperations {

    private StorageOperations() {}

    public static void deleteByPrefix(StorageBackend<String, ?> store, String prefix) {
        store.keys().stream()
                .filter(key -> key.startsWith(prefix))
                .toList()
                .forEach(store::delete);
    }

    /** Deletes matching values in one commit, preserving account ownership of legacy keys. */
    public static <V> void deleteMatchingAtomically(StorageBackend<String, V> store, Predicate<V> valueFilter) {
        deleteMatchingAtomically(store, key -> true, valueFilter);
    }

    /**
     * As above; with {@code keyFilter}, only among the keys it accepts, each as {@code store} names it: a
     * Region view's logical key, an account store's account-relative key or unprefixed legacy key.
     */
    public static <V> void deleteMatchingAtomically(StorageBackend<String, V> store, Predicate<String> keyFilter,
                                                    Predicate<V> valueFilter) {
        if (store instanceof AccountAwareStorageBackend<V> accountAware) {
            accountAware.deleteMatchingIncludingLegacyAtomically(keyFilter, valueFilter);
        } else if (store instanceof RegionScopedStorage<V> regional) {
            regional.deleteMatchingAtomically(keyFilter, valueFilter);
        } else {
            Set<String> keys = new LinkedHashSet<>();
            for (String key : store.keys()) {
                if (keyFilter.test(key) && store.get(key).filter(valueFilter).isPresent()) {
                    keys.add(key);
                }
            }
            store.deleteAllAtomically(keys);
        }
    }
}
