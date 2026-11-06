package io.floci.aws.core.storage;

public final class StorageOperations {

    private StorageOperations() {}

    public static void deleteByPrefix(StorageBackend<String, ?> store, String prefix) {
        store.keys().stream()
                .filter(key -> key.startsWith(prefix))
                .toList()
                .forEach(store::delete);
    }
}
