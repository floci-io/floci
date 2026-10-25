package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedshiftFinalSnapshotStorageTest {
    private static final String ACCOUNT = "111111111111";

    @TempDir
    Path directory;

    @Test
    void persistentFinalSnapshotAndRetentionSurviveRestartAndRestore() throws Exception {
        Fixture first = fixture();
        seedCluster(first);
        delete(first, "FinalClusterSnapshotRetentionPeriod", "7");

        Fixture restarted = fixture();
        Snapshot snapshot = restarted.service().describeSnapshots("final-backup").getFirst();
        assertEquals(7, snapshot.getManualSnapshotRetentionPeriod());
        assertEquals("backup contents", Files.readString(Path.of(snapshot.getSqlDump())));
        assertTrue(restarted.service().describeClusters(null).isEmpty());
        restarted.service().restoreFromClusterSnapshot("restored", "final-backup");
        verify(restarted.manager()).restoreSnapshot(ACCOUNT, "restored", "admin", Path.of(snapshot.getSqlDump()));
    }

    @Test
    void actualPersistentCommitFailurePreservesClusterAndRejectsSnapshot() throws Exception {
        Fixture fixture = fixture();
        seedCluster(fixture);
        Path snapshotFile = directory.resolve("redshift-snapshots.json");
        Files.createDirectory(snapshotFile);
        Files.writeString(snapshotFile.resolve("block-replacement"), "keep");

        assertThrows(AwsException.class, () -> delete(fixture));

        assertEquals("available", fixture.service().describeClusters("final-cluster").getFirst().getClusterStatus());
        assertTrue(fixture.service().getSnapshot("final-backup").isEmpty());
        assertFalse(Files.exists(directory.resolve("redshift-dumps").resolve(ACCOUNT).resolve("final-backup.sql")));
        verifyNoInteractions(fixture.proxy());
        verify(fixture.manager(), never()).stop(anyString(), anyString());
        assertTrue(Files.readString(directory.resolve("redshift-clusters.json")).contains("final-cluster"));
    }

    @Test
    void failedDumpRemovesPartialOutputAndPreservesOriginalFailure() {
        Fixture fixture = fixture();
        seedCluster(fixture);
        AwsException original = new AwsException("InternalFailure", "dump failed", 500);
        doAnswer(invocation -> {
            Files.writeString(invocation.getArgument(3, Path.class), "partial dump");
            throw original;
        }).when(fixture.manager()).takeSnapshot(anyString(), anyString(), anyString(), any(Path.class));

        assertSame(original, assertThrows(AwsException.class, () -> delete(fixture)));
        assertFalse(Files.exists(directory.resolve("redshift-dumps").resolve(ACCOUNT).resolve("final-backup.sql")));
        assertTrue(fixture.service().getSnapshot("final-backup").isEmpty());
        verifyNoInteractions(fixture.proxy());
    }

    @Test
    void failedCleanupIsSuppressedOnTheOriginalDumpFailure() {
        Fixture fixture = fixture();
        seedCluster(fixture);
        AwsException original = new AwsException("InternalFailure", "dump failed", 500);
        doAnswer(invocation -> {
            Path output = invocation.getArgument(3, Path.class);
            Files.createDirectory(output);
            Files.writeString(output.resolve("block-cleanup"), "partial output");
            throw original;
        }).when(fixture.manager()).takeSnapshot(anyString(), anyString(), anyString(), any(Path.class));

        assertSame(original, assertThrows(AwsException.class, () -> delete(fixture)));
        assertEquals(1, original.getSuppressed().length);
        assertTrue(fixture.service().getSnapshot("final-backup").isEmpty());
        verifyNoInteractions(fixture.proxy());
    }

    private Fixture fixture() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services().redshift().proxyBasePort()).thenReturn(7100);
        when(config.services().redshift().proxyMaxPort()).thenReturn(7199);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("redshift")).thenReturn("persistent");
        StorageFactory factory = new StorageFactory(config, access);
        RedshiftContainerManager manager = mock(RedshiftContainerManager.class);
        when(manager.start(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new RedshiftContainerHandle("container", "restored", "localhost", 5432));
        doAnswer(invocation -> {
            Files.writeString(invocation.getArgument(3, Path.class), "backup contents");
            return null;
        }).when(manager).takeSnapshot(anyString(), anyString(), anyString(), any(Path.class));
        RedshiftProxyManager proxy = mock(RedshiftProxyManager.class);
        DockerHostResolver dockerHost = mock(DockerHostResolver.class);
        when(dockerHost.resolve()).thenReturn("localhost");
        RedshiftService service = new RedshiftService(factory, manager, config,
                new RegionResolver("us-east-1", ACCOUNT), proxy, dockerHost, new RedshiftCredentialBroker());
        return new Fixture(service, manager, proxy, config, factory);
    }

    private static void seedCluster(Fixture fixture) {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("final-cluster");
        cluster.setClusterStatus("available");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("secret-pw");
        AccountAwareStorageBackend<Cluster> clusters = fixture.factory().create("redshift", "redshift-clusters.json",
                new TypeReference<Map<String, Cluster>>() { });
        clusters.put("final-cluster", cluster);
    }

    private static void delete(Fixture fixture, String... options) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "final-cluster");
        params.putSingle("FinalClusterSnapshotIdentifier", "final-backup");
        for (int index = 0; index < options.length; index += 2) {
            params.putSingle(options[index], options[index + 1]);
        }
        new RedshiftQueryHandler(fixture.service(), new RedshiftCredentialBroker(), fixture.config(),
                mock(RedshiftIamDbUserResolver.class), new RegionResolver("us-east-1", ACCOUNT))
                .handle("DeleteCluster", params);
    }

    private record Fixture(RedshiftService service, RedshiftContainerManager manager, RedshiftProxyManager proxy,
                           EmulatorConfig config, StorageFactory factory) { }
}
