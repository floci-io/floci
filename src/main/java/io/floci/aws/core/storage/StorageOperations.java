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
        if (store instanceof AccountAwareStorageBackend<V> accountAware) {
            accountAware.deleteMatchingIncludingLegacyAtomically(valueFilter);
        } else {
            Set<String> keys = new LinkedHashSet<>();
            for (String key : store.keys()) {
                if (store.get(key).filter(valueFilter).isPresent()) {
                    keys.add(key);
                }
            }
            store.deleteAllAtomically(keys);
        }
    }
}
