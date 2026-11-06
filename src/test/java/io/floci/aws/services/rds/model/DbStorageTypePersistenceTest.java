package io.floci.aws.services.rds.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DbStorageTypePersistenceTest {
    private static StorageFactory factory(String mode, Path directory) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(600000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("rds")).thenReturn(mode);
        when(access.storageFlushInterval("rds")).thenReturn(600000L);
        return new StorageFactory(config, access);
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void instanceAndSnapshotTypesSurviveQuiescentFactoryReload(String mode, @TempDir Path directory) {
        StorageFactory first = factory(mode, directory);
        StorageBackend<String, DbInstance> instances = first.create("rds", "rds-instances.json",
                new TypeReference<Map<String, DbInstance>>() {});
        StorageBackend<String, DbSnapshot> snapshots = first.create("rds", "rds-snapshots.json",
                new TypeReference<Map<String, DbSnapshot>>() {});
        try {
            for (String storageType : new String[]{"gp3", "io1", "io2"}) {
                DbInstance instance = new DbInstance();
                instance.setDbInstanceIdentifier("storage-db-" + storageType);
                instance.setStorageType(storageType);
                DbSnapshot snapshot = new DbSnapshot();
                snapshot.setDbSnapshotIdentifier("storage-snapshot-" + storageType);
                snapshot.setStorageType(storageType);
                instances.put("us-east-1/storage-db-" + storageType, instance);
                snapshots.put("us-east-1/storage-snapshot-" + storageType, snapshot);
            }
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = factory(mode, directory);
        try {
            StorageBackend<String, DbInstance> restoredInstances = second.create("rds", "rds-instances.json",
                    new TypeReference<Map<String, DbInstance>>() {});
            StorageBackend<String, DbSnapshot> restoredSnapshots = second.create("rds", "rds-snapshots.json",
                    new TypeReference<Map<String, DbSnapshot>>() {});
            for (String storageType : new String[]{"gp3", "io1", "io2"}) {
                assertEquals(storageType, restoredInstances.get("us-east-1/storage-db-" + storageType)
                        .orElseThrow().getStorageType());
                assertEquals(storageType, restoredSnapshots.get("us-east-1/storage-snapshot-" + storageType)
                        .orElseThrow().getStorageType());
            }
        } finally {
            second.shutdownAll();
        }
    }

    @Test
    void absentAndNullLegacyMembersUseThePriorGp2Fallback() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String json : new String[]{"{}", "{\"storageType\":null}"}) {
            assertEquals("gp2", mapper.readValue(json, DbInstance.class).getStorageType());
            assertEquals("gp2", mapper.readValue(json, DbSnapshot.class).getStorageType());
        }
    }
}
