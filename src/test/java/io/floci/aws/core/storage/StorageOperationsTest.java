package io.floci.aws.core.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
