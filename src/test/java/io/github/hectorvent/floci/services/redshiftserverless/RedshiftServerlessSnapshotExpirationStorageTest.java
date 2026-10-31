package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftServerlessSnapshotExpirationStorageTest {
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void deletionResumesAfterStorageReload(String mode) throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage().wal().compactionIntervalMs()).thenReturn(3600000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("redshiftserverless")).thenReturn(mode);
        when(access.storageFlushInterval("redshiftserverless")).thenReturn(3600000L);
        StorageFactory factory = new StorageFactory(config, access);
        Path dump = directory.resolve("redshift-serverless-dumps").resolve(ACCOUNT).resolve(REGION)
                .resolve("retry-snap.sql");
        Files.createDirectories(dump);
        Path blocker = Files.writeString(dump.resolve("blocker"), "partial dump");
        try {
            AccountAwareStorageBackend<RedshiftServerlessSnapshot> snapshots = factory.create(
                    "redshiftserverless", "redshiftserverless-snapshots.json", new TypeReference<>() { });
            RedshiftServerlessSnapshot snapshot = new RedshiftServerlessSnapshot();
            snapshot.setSnapshotName("retry-snap");
            snapshot.setSnapshotCreateTime(Instant.EPOCH);
            snapshot.setRetentionPeriod(1);
            snapshot.setSqlDump(dump.toString());
            snapshots.put(REGION + "::retry-snap", snapshot);
            service(factory, config).expireSnapshots(Instant.EPOCH.plusSeconds(86400), entry -> { });
            assertEquals("DELETED", snapshots.get(REGION + "::retry-snap").orElseThrow().getStatus());
            if ("memory".equals(mode)) {
                Files.delete(blocker);
                service(factory, config).expireSnapshots(Instant.EPOCH, entry -> { });
                assertTrue(snapshots.scanAllAccounts().isEmpty());
                assertFalse(Files.exists(dump));
                return;
            }
        } finally {
            factory.shutdownAll();
        }

        StorageFactory reloaded = new StorageFactory(config, access);
        try {
            RedshiftServerlessService service = service(reloaded, config);
            assertEquals("DELETED", service.getSnapshot("retry-snap", REGION).getStatus());
            Files.delete(blocker);
            service.expireSnapshots(Instant.EPOCH, entry -> { });
            assertTrue(service.findSnapshot("retry-snap", REGION).isEmpty());
            assertFalse(Files.exists(dump));
        } finally {
            reloaded.shutdownAll();
        }
    }

    private RedshiftServerlessService service(StorageFactory factory, EmulatorConfig config) {
        return new RedshiftServerlessService(factory, new RegionResolver(REGION, ACCOUNT),
                mock(RedshiftServerlessEndpoints.class), mock(RedshiftServerlessRuntime.class), config);
    }
}
