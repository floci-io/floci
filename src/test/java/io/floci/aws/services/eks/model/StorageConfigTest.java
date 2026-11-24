package io.floci.aws.services.eks.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageConfigTest {

    @Test
    void blockStorageIsOnOnlyWhenEnabledIsTrue() {
        assertTrue(StorageConfig.blockStorageOn(new StorageConfig(new BlockStorage(true))));
        assertFalse(StorageConfig.blockStorageOn(new StorageConfig(new BlockStorage(false))));
        assertFalse(StorageConfig.blockStorageOn(new StorageConfig(new BlockStorage(null))));
        assertFalse(StorageConfig.blockStorageOn(new StorageConfig(null)));
        assertFalse(StorageConfig.blockStorageOn(null));
    }
}
