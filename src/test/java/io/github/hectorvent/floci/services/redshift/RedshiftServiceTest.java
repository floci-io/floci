package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.ClusterParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.ClusterSubnetGroup;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshift.model.Parameter;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class RedshiftServiceTest {

    private StorageFactory sf;
    private AccountAwareStorageBackend<Cluster> clusterBackend;
    private AccountAwareStorageBackend<Snapshot> snapshotBackend;
    private AccountAwareStorageBackend<String> snapshotDumpBackend;
    private AccountAwareStorageBackend<ClusterParameterGroup> parameterGroupBackend;
    private AccountAwareStorageBackend<ClusterSubnetGroup> subnetGroupBackend;
    private RedshiftContainerManager cm;
    private io.github.hectorvent.floci.core.common.RegionResolver regionResolver;
    private io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager proxyManager;
    private io.github.hectorvent.floci.core.common.docker.DockerHostResolver dockerHostResolver;
    private RedshiftService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sf = mock(StorageFactory.class);
        clusterBackend = mock(AccountAwareStorageBackend.class);
        snapshotBackend = mock(AccountAwareStorageBackend.class);
        snapshotDumpBackend = mock(AccountAwareStorageBackend.class);
        parameterGroupBackend = mock(AccountAwareStorageBackend.class);
        subnetGroupBackend = mock(AccountAwareStorageBackend.class);
        cm = mock(RedshiftContainerManager.class);
        proxyManager = mock(io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager.class);
        dockerHostResolver = mock(io.github.hectorvent.floci.core.common.docker.DockerHostResolver.class);
        when(dockerHostResolver.resolve()).thenReturn("localhost");

        io.github.hectorvent.floci.config.EmulatorConfig config = mock(io.github.hectorvent.floci.config.EmulatorConfig.class);
        io.github.hectorvent.floci.config.EmulatorConfig.StorageConfig storageConfig = mock(io.github.hectorvent.floci.config.EmulatorConfig.StorageConfig.class);
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.persistentPath()).thenReturn("target/test-data");

        io.github.hectorvent.floci.config.EmulatorConfig.ServicesConfig servicesConfig =
                mock(io.github.hectorvent.floci.config.EmulatorConfig.ServicesConfig.class);
        io.github.hectorvent.floci.config.EmulatorConfig.RedshiftServiceConfig redshiftConfig =
                mock(io.github.hectorvent.floci.config.EmulatorConfig.RedshiftServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.redshift()).thenReturn(redshiftConfig);
        when(redshiftConfig.proxyBasePort()).thenReturn(7100);
        when(redshiftConfig.proxyMaxPort()).thenReturn(7199);
        when(redshiftConfig.endpointHost()).thenReturn(Optional.empty());

        when(sf.<Cluster>create(eq("redshift"), eq("redshift-clusters.json"), any())).thenReturn(clusterBackend);
        when(sf.<Snapshot>create(eq("redshift"), eq("redshift-snapshots.json"), any())).thenReturn(snapshotBackend);
        when(sf.<ClusterParameterGroup>create(eq("redshift"), eq("redshift-parameter-groups.json"), any())).thenReturn(parameterGroupBackend);
        when(sf.<ClusterSubnetGroup>create(eq("redshift"), eq("redshift-subnet-groups.json"), any())).thenReturn(subnetGroupBackend);
        when(clusterBackend.accountId()).thenReturn("111111111111");

        regionResolver = new io.github.hectorvent.floci.core.common.RegionResolver("us-east-1", "111111111111");

        service = new RedshiftService(sf, cm, config, regionResolver, proxyManager, dockerHostResolver);
    }

    /** Absolute dump path as {@code createSnapshot} now stores it: under {@code <persistentPath>/redshift-dumps/<accountId>}. */
    private static String dumpPath(String snapshotId) {
        return Paths.get("target/test-data", "redshift-dumps", "111111111111", snapshotId + ".sql")
                .toAbsolutePath().normalize().toString();
    }

    @Test
    void testOnStartRecreatesContainersAcrossAccounts() {
        Cluster clusterA = new Cluster();
        clusterA.setClusterIdentifier("cluster-a");
        clusterA.setMasterUsername("admin");
        clusterA.setMasterPassword("pw-a");
        clusterA.setClusterStatus("available");

        Cluster clusterB = new Cluster();
        clusterB.setClusterIdentifier("cluster-b");
        clusterB.setMasterUsername("admin");
        clusterB.setMasterPassword("pw-b");
        clusterB.setClusterStatus("available");

        when(clusterBackend.scanAllAccountEntries(any())).thenReturn(List.of(
                new AccountAwareStorageBackend.AccountEntry<>("111111111111", "cluster-a", clusterA),
                new AccountAwareStorageBackend.AccountEntry<>("222222222222", "cluster-b", clusterB)));
        when(cm.getContainer(anyString(), anyString())).thenReturn(Optional.empty());
        when(cm.adoptOrStart(eq("111111111111"), eq("cluster-a"), eq("admin"), eq("pw-a")))
                .thenReturn(new RedshiftContainerHandle("c-a", "cluster-a", "localhost", 5432));
        when(cm.adoptOrStart(eq("222222222222"), eq("cluster-b"), eq("admin"), eq("pw-b")))
                .thenReturn(new RedshiftContainerHandle("c-b", "cluster-b", "localhost", 5433));

        service.onStart(null);

        // Cluster owned by a second, non-default account must also be recovered
        verify(cm).adoptOrStart("111111111111", "cluster-a", "admin", "pw-a");
        verify(cm).adoptOrStart("222222222222", "cluster-b", "admin", "pw-b");
        verify(clusterBackend).putForAccount(eq("111111111111"), eq("cluster-a"), any(Cluster.class));
        verify(clusterBackend).putForAccount(eq("222222222222"), eq("cluster-b"), any(Cluster.class));
        verify(clusterBackend).flush();
    }

    @Test
    void testOnStartSkipsClusterWithRunningContainer() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("cluster-a");
        cluster.setClusterStatus("available");
        when(clusterBackend.scanAllAccountEntries(any())).thenReturn(List.of(
                new AccountAwareStorageBackend.AccountEntry<>("111111111111", "cluster-a", cluster)));
        when(cm.getContainer("111111111111", "cluster-a"))
                .thenReturn(Optional.of(new RedshiftContainerHandle("c-a", "cluster-a", "localhost", 5432)));

        service.onStart(null);

        verify(cm, never()).adoptOrStart(any(), any(), any(), any());
        verify(clusterBackend, never()).putForAccount(any(), any(), any());
    }

    @Test
    void testOnStartMarksClusterUnavailableOnStartFailure() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("cluster-a");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("pw-a");
        cluster.setClusterStatus("available");
        when(clusterBackend.scanAllAccountEntries(any())).thenReturn(List.of(
                new AccountAwareStorageBackend.AccountEntry<>("111111111111", "cluster-a", cluster)));
        when(cm.getContainer("111111111111", "cluster-a")).thenReturn(Optional.empty());
        when(cm.adoptOrStart(eq("111111111111"), eq("cluster-a"), eq("admin"), eq("pw-a"))).thenThrow(new RuntimeException("docker down"));

        service.onStart(null);

        ArgumentCaptor<Cluster> captor = ArgumentCaptor.forClass(Cluster.class);
        verify(clusterBackend).putForAccount(eq("111111111111"), eq("cluster-a"), captor.capture());
        assertEquals("unavailable", captor.getValue().getClusterStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onStartRestartsTheProxyForAnAdoptedCluster() {
        Cluster persisted = new Cluster();
        persisted.setClusterIdentifier("c1");
        persisted.setMasterUsername("admin");
        persisted.setMasterPassword("Secret123");
        persisted.setClusterStatus("available");
        persisted.setProxyPort(7108);

        var entry = mock(AccountAwareStorageBackend.AccountEntry.class);
        when(entry.value()).thenReturn(persisted);
        when(entry.accountId()).thenReturn("111111111111");
        when(entry.key()).thenReturn("c1");
        when(clusterBackend.scanAllAccountEntries(any())).thenReturn(List.of(entry));
        when(cm.getContainer("111111111111", "c1")).thenReturn(Optional.empty());
        RedshiftContainerHandle handle = mock(RedshiftContainerHandle.class);
        when(handle.getHost()).thenReturn("172.17.0.12");
        when(handle.getPort()).thenReturn(32830);
        when(cm.adoptOrStart("111111111111", "c1", "admin", "Secret123")).thenReturn(handle);

        service.onStart(null);

        verify(proxyManager).startProxy(eq("111111111111:c1"), eq(7108),
                eq("172.17.0.12"), eq(32830), eq("localhost"),
                eq("admin"), eq("Secret123"), eq("dev"), any());
    }

    @Test
    void testCreateCluster() {
        when(clusterBackend.get(anyString())).thenReturn(Optional.empty());
        when(cm.start(any(), any(), any(), any())).thenReturn(new RedshiftContainerHandle("c1", "my-cluster", "localhost", 5432));

        Cluster cluster = service.createCluster("my-cluster", "dc2.large", "admin", "password123");
        assertNotNull(cluster);
        assertEquals("my-cluster", cluster.getClusterIdentifier());
        assertEquals("available", cluster.getClusterStatus());
        assertEquals("localhost", cluster.getEndpoint().getAddress());
        verify(clusterBackend, times(2)).put(eq("my-cluster"), any(Cluster.class));
    }

    @Test
    void testCreateClusterWithVpcMetadata() {
        when(clusterBackend.get(anyString())).thenReturn(Optional.empty());
        when(cm.start(any(), any(), any(), any())).thenReturn(new RedshiftContainerHandle("c1", "my-cluster", "localhost", 5432));

        Cluster cluster = service.createCluster("my-cluster", "dc2.large", "admin", "password123",
                "my-subnet-group", List.of("sg-1", "sg-2"));

        assertEquals("my-subnet-group", cluster.getClusterSubnetGroupName());
        assertEquals(List.of("sg-1", "sg-2"), cluster.getVpcSecurityGroupIds());
    }

    @Test
    void createClusterStartsAProxyAndAdvertisesTheProxyEndpoint() {
        when(clusterBackend.get("c1")).thenReturn(Optional.empty());
        when(clusterBackend.accountId()).thenReturn("111111111111");
        RedshiftContainerHandle handle = mock(RedshiftContainerHandle.class);
        when(handle.getHost()).thenReturn("172.17.0.9");
        when(handle.getPort()).thenReturn(32800);
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("Secret123"))).thenReturn(handle);

        Cluster cluster = service.createCluster("c1", "dc2.large", "admin", "Secret123");

        // Endpoint is the proxy, not the container.
        assertEquals("localhost", cluster.getEndpoint().getAddress());
        assertTrue(cluster.getEndpoint().getPort() >= 7100 && cluster.getEndpoint().getPort() <= 7199);
        assertEquals("172.17.0.9", cluster.getContainerHost());
        assertEquals(32800, cluster.getContainerPort());
        assertEquals(cluster.getEndpoint().getPort(), cluster.getProxyPort());

        verify(proxyManager).startProxy(eq("111111111111:c1"), eq(cluster.getProxyPort()),
                eq("172.17.0.9"), eq(32800), eq("localhost"),
                eq("admin"), eq("Secret123"), eq("dev"), any());
    }

    @Test
    void testCreateClusterAlreadyExists() {
        when(clusterBackend.get("existing-cluster")).thenReturn(Optional.of(new Cluster()));

        assertThrows(AwsException.class, () ->
                service.createCluster("existing-cluster", "dc2.large", "admin", "password123"));
    }

    @Test
    void createClusterRemovesMetadataOnFailure() {
        when(clusterBackend.accountId()).thenReturn("111111111111");
        when(clusterBackend.get("c1")).thenReturn(Optional.empty());
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("password123")))
                .thenThrow(new RuntimeException("startup failed"));

        assertThrows(AwsException.class, () ->
                service.createCluster("c1", "dc2.large", "admin", "password123"));

        verify(clusterBackend).delete("c1");
        verify(clusterBackend, atLeastOnce()).flush();
    }

    @Test
    void createClusterKeepsMetadataWhenProxyStopFails() {
        when(clusterBackend.accountId()).thenReturn("111111111111");
        when(clusterBackend.get("c1")).thenReturn(Optional.empty());
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("password123")))
                .thenThrow(new RuntimeException("startup failed"));
        doThrow(new RuntimeException("proxy stop failed"))
                .when(proxyManager).stopProxy("111111111111:c1");

        assertThrows(AwsException.class, () ->
                service.createCluster("c1", "dc2.large", "admin", "password123"));

        verify(clusterBackend, never()).delete("c1");
        verify(clusterBackend, atLeast(1)).put(eq("c1"), argThat(c -> "failed".equals(c.getClusterStatus())));
        verify(clusterBackend, atLeastOnce()).flush();
    }

    @Test
    void restoreFromClusterSnapshotRemovesMetadataOnFailure() {
        when(clusterBackend.accountId()).thenReturn("111111111111");
        Snapshot snapshot = new Snapshot();
        snapshot.setSnapshotIdentifier("snap-1");
        snapshot.setClusterIdentifier("source-c1");
        snapshot.setMasterUsername("admin");
        snapshot.setMasterPassword("password123");
        snapshot.setSqlDump(null);

        when(clusterBackend.get("c1")).thenReturn(Optional.empty());
        when(snapshotBackend.get("snap-1")).thenReturn(Optional.of(snapshot));
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("password123")))
                .thenThrow(new RuntimeException("startup failed"));

        assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("c1", "snap-1"));

        verify(clusterBackend).delete("c1");
        verify(clusterBackend, atLeastOnce()).flush();
    }

    @Test
    void restoreFromClusterSnapshotKeepsMetadataWhenProxyStopFails() {
        when(clusterBackend.accountId()).thenReturn("111111111111");
        Snapshot snapshot = new Snapshot();
        snapshot.setSnapshotIdentifier("snap-1");
        snapshot.setClusterIdentifier("source-c1");
        snapshot.setMasterUsername("admin");
        snapshot.setMasterPassword("password123");
        snapshot.setSqlDump(null);

        when(clusterBackend.get("c1")).thenReturn(Optional.empty());
        when(snapshotBackend.get("snap-1")).thenReturn(Optional.of(snapshot));
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("password123")))
                .thenThrow(new RuntimeException("startup failed"));
        doThrow(new RuntimeException("proxy stop failed"))
                .when(proxyManager).stopProxy("111111111111:c1");

        assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("c1", "snap-1"));

        verify(clusterBackend, never()).delete("c1");
        verify(clusterBackend, atLeast(1)).put(eq("c1"), argThat(c -> "failed".equals(c.getClusterStatus())));
        verify(clusterBackend, atLeastOnce()).flush();
    }

    @Test
    void testDescribeClusters() {
        Cluster c = new Cluster();
        c.setClusterIdentifier("test-c");
        when(clusterBackend.get("test-c")).thenReturn(Optional.of(c));

        List<Cluster> list = service.describeClusters("test-c");
        assertEquals(1, list.size());
        assertEquals("test-c", list.get(0).getClusterIdentifier());
    }

    @Test
    void testDeleteCluster() {
        Cluster c = new Cluster();
        c.setClusterIdentifier("test-c");
        when(clusterBackend.get("test-c")).thenReturn(Optional.of(c));

        Cluster deleted = service.deleteCluster("test-c");
        assertEquals("deleting", deleted.getClusterStatus());
        verify(cm).stop("111111111111", "test-c");
        verify(clusterBackend).delete("test-c");
    }

    @Test
    void deleteClusterAbortsAndKeepsMetadataWhenTheProxyWontStop() {
        Cluster c = new Cluster();
        c.setClusterIdentifier("test-c");
        c.setProxyPort(7107);
        when(clusterBackend.get("test-c")).thenReturn(Optional.of(c));
        doThrow(new RuntimeException("listener close failed"))
                .when(proxyManager).stopProxy("111111111111:test-c");

        AwsException ex = assertThrows(AwsException.class, () -> service.deleteCluster("test-c"));
        assertEquals("InternalFailure", ex.getErrorCode());

        // Container and metadata are left intact so the deletion can be retried.
        verify(cm, never()).stop(anyString(), anyString());
        verify(clusterBackend, never()).delete(anyString());
    }

    @Test
    void deleteClusterRetrySucceedsOnceTheProxyStops() {
        Cluster c = new Cluster();
        c.setClusterIdentifier("test-c");
        c.setProxyPort(7107);
        when(clusterBackend.get("test-c")).thenReturn(Optional.of(c));
        doThrow(new RuntimeException("listener close failed"))
                .doNothing()
                .when(proxyManager).stopProxy("111111111111:test-c");

        assertThrows(AwsException.class, () -> service.deleteCluster("test-c"));
        Cluster deleted = service.deleteCluster("test-c");

        assertEquals("deleting", deleted.getClusterStatus());
        verify(cm).stop("111111111111", "test-c");
        verify(clusterBackend).delete("test-c");
    }

    @Test
    void testRebootClusterDumpsAndRestoresData() throws Exception {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("pw");
        cluster.setClusterStatus("available");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));
        when(cm.start(eq("111111111111"), eq("my-cluster"), eq("admin"), eq("pw")))
                .thenReturn(new RedshiftContainerHandle("c-rebooted", "my-cluster", "localhost", 5555));
        doAnswer(invocation -> {
            Path dumpFile = invocation.getArgument(3);
            Files.writeString(dumpFile, "-- dump");
            return null;
        }).when(cm).takeSnapshot(eq("111111111111"), eq("my-cluster"), eq("admin"), any(Path.class));

        Cluster rebooted = service.rebootCluster("my-cluster");

        assertEquals("available", rebooted.getClusterStatus());
        // Endpoint now advertises the auth proxy; the restarted container is tracked separately.
        assertEquals(5555, rebooted.getContainerPort());
        assertTrue(rebooted.getEndpoint().getPort() >= 7100 && rebooted.getEndpoint().getPort() <= 7199);
        verify(cm).stop("111111111111", "my-cluster");
        verify(cm).start("111111111111", "my-cluster", "admin", "pw");
        verify(cm).restoreSnapshot(eq("111111111111"), eq("my-cluster"), eq("admin"), any(Path.class));
    }

    @Test
    void rebootClusterRestartsTheProxyOnTheSamePort() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123");
        cluster.setProxyPort(7107);
        cluster.setEndpoint(new Endpoint("localhost", 7107));
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        when(clusterBackend.accountId()).thenReturn("111111111111");
        RedshiftContainerHandle handle = mock(RedshiftContainerHandle.class);
        when(handle.getHost()).thenReturn("172.17.0.11");
        when(handle.getPort()).thenReturn(32820);
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("Secret123"))).thenReturn(handle);

        Cluster rebooted = service.rebootCluster("c1");

        assertEquals(7107, rebooted.getEndpoint().getPort());
        assertEquals("localhost", rebooted.getEndpoint().getAddress());
        assertEquals("172.17.0.11", rebooted.getContainerHost());
        verify(proxyManager).stopProxy("111111111111:c1");
        verify(proxyManager).startProxy(eq("111111111111:c1"), eq(7107),
                eq("172.17.0.11"), eq(32820), eq("localhost"),
                eq("admin"), eq("Secret123"), eq("dev"), any());
    }

    @Test
    void testRebootClusterNotFound() {
        when(clusterBackend.get("missing")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () -> service.rebootCluster("missing"));
    }

    @Test
    void rebootClusterTearsDownTheProxyWhenRestoreFails() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123");
        cluster.setProxyPort(7107);
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        RedshiftContainerHandle handle = mock(RedshiftContainerHandle.class);
        when(handle.getHost()).thenReturn("172.17.0.11");
        when(handle.getPort()).thenReturn(32820);
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("Secret123"))).thenReturn(handle);
        doThrow(new RuntimeException("restore boom"))
                .when(cm).restoreSnapshot(eq("111111111111"), eq("c1"), eq("admin"), any(Path.class));

        assertThrows(AwsException.class, () -> service.rebootCluster("c1"));

        // The proxy started during the reboot must be stopped again on failure, and the
        // replacement container must be stopped too — once before the restart, once in
        // rollback — so it is not left running behind a "failed" cluster.
        verify(proxyManager).startProxy(eq("111111111111:c1"), eq(7107), any(), anyInt(),
                any(), any(), any(), any(), any());
        verify(proxyManager, times(2)).stopProxy("111111111111:c1");
        verify(cm, times(2)).stop("111111111111", "c1");
    }

    @Test
    void rebootClusterLeavesTheOriginalContainerAloneWhenTheDumpFails() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123");
        cluster.setProxyPort(7107);
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        doThrow(new RuntimeException("dump boom"))
                .when(cm).takeSnapshot(eq("111111111111"), eq("c1"), eq("admin"), any(Path.class));

        assertThrows(AwsException.class, () -> service.rebootCluster("c1"));

        // The dump failed before the original was torn down: rollback must not stop the
        // still-running original container or touch its proxy.
        verify(cm, never()).stop(anyString(), anyString());
        verify(proxyManager, never()).stopProxy(anyString());
    }

    @Test
    void rebootClusterRemovesTheReplacementWhenItsStartupThrows() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123");
        cluster.setProxyPort(7107);
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("Secret123")))
                .thenThrow(new RuntimeException("readiness timed out"));

        assertThrows(AwsException.class, () -> service.rebootCluster("c1"));

        // start() can create the container before throwing (readiness check); the
        // original is already gone, so rollback removes anything under the name —
        // once for the original teardown, once for the possible orphan.
        verify(cm, times(2)).stop("111111111111", "c1");

        // The reserved port belongs to the cluster and is not released by reboot rollback;
        // it is released only when the cluster is successfully deleted.
        assertEquals(7107, cluster.getProxyPort());
    }

    @Test
    void rebootClusterKeepsProxyPortWhenRollbackStopFails() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123");
        cluster.setProxyPort(7107);
        when(clusterBackend.accountId()).thenReturn("111111111111");
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("Secret123")))
                .thenThrow(new RuntimeException("readiness timed out"));
        
        // The first call stops the proxy during takeSnapshot; the second call happens in rollback.
        doNothing().doThrow(new RuntimeException("stop failed")).when(proxyManager).stopProxy(anyString());

        assertThrows(AwsException.class, () -> service.rebootCluster("c1"));

        assertEquals(7107, cluster.getProxyPort());
    }

    @Test
    void testCreateSnapshot() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("secret-pw");
        cluster.setEndpoint(new Endpoint("localhost", 5439));

        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.empty());
        doNothing().when(cm).takeSnapshot(eq("111111111111"), eq("my-cluster"), eq("admin"), any(Path.class));

        Snapshot snapshot = service.createSnapshot("my-snapshot", "my-cluster");
        assertNotNull(snapshot);
        assertEquals("my-snapshot", snapshot.getSnapshotIdentifier());
        assertEquals("my-cluster", snapshot.getClusterIdentifier());
        assertEquals("available", snapshot.getStatus());
        assertEquals("admin", snapshot.getMasterUsername());
        // Password captured at snapshot time so restore can recover it after the source cluster is gone
        assertEquals("secret-pw", snapshot.getMasterPassword());
        assertEquals(5439, snapshot.getPort());
        // sqlDump is now an absolute path scoped by account to avoid collisions across accounts
        assertTrue(snapshot.getSqlDump().contains("111111111111"));
        assertTrue(snapshot.getSqlDump().endsWith("my-snapshot.sql"));
        verify(snapshotBackend).put(eq("my-snapshot"), any(Snapshot.class));
        verify(snapshotBackend).flush();
        verify(cm).takeSnapshot(eq("111111111111"), eq("my-cluster"), eq("admin"), any(Path.class));
    }

    @Test
    void testCreateSnapshotClusterNotFound() {
        when(clusterBackend.get("missing-cluster")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () ->
                service.createSnapshot("snap-1", "missing-cluster"));
    }

    @Test
    void testCreateSnapshotAlreadyExists() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));
        when(snapshotBackend.get("snap-1")).thenReturn(Optional.of(new Snapshot()));

        assertThrows(AwsException.class, () ->
                service.createSnapshot("snap-1", "my-cluster"));
    }

    @Test
    void testCreateSnapshotRejectsTraversalOrMalformedIdentifier() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setMasterUsername("admin");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));
        when(snapshotBackend.get(anyString())).thenReturn(Optional.empty());

        String[] bad = {
                "../evil", "../../etc/cron.d/x", "/etc/passwd", "a/b", "a\\b",
                "foo/../bar", "-leading", "trailing-", "double--hyphen", "1startsdigit", "", "has space",
        };
        for (String id : bad) {
            AwsException ex = assertThrows(AwsException.class,
                    () -> service.createSnapshot(id, "my-cluster"), "expected rejection for: " + id);
            assertEquals("InvalidParameterValue", ex.getErrorCode(), id);
            assertEquals(400, ex.getHttpStatus(), id);
        }
        verify(cm, never()).takeSnapshot(any(), any(), any(), any(Path.class));
        verify(snapshotBackend, never()).put(anyString(), any(Snapshot.class));
    }

    @Test
    void testModifyClusterUpdatesMetadataAndPassword() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setNodeType("dc2.large");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("old-pw");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        Cluster updated = service.modifyCluster("my-cluster", "ra3.xlplus", null, "new-pw", null, null);

        assertEquals("ra3.xlplus", updated.getNodeType());
        assertEquals("new-pw", updated.getMasterPassword());
        verify(cm).alterUserPassword("111111111111", "my-cluster", "admin", "new-pw");
        verify(clusterBackend).put(eq("my-cluster"), any(Cluster.class));
        verify(clusterBackend).flush();
    }

    @Test
    void testModifyClusterUpdatesVpcSecurityGroups() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setMasterUsername("admin");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        Cluster updated = service.modifyCluster("my-cluster", null, null, null, "custom-pg", List.of("sg-9"));

        assertEquals("custom-pg", updated.getClusterParameterGroupName());
        assertEquals(List.of("sg-9"), updated.getVpcSecurityGroupIds());
        verify(cm, never()).alterUserPassword(any(), any(), any(), any());
    }

    @Test
    void testModifyClusterNotFound() {
        when(clusterBackend.get("missing")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () ->
                service.modifyCluster("missing", "ra3.xlplus", null, null, null, null));
    }

    @Test
    void testDescribeSnapshots() {
        Snapshot s = new Snapshot("snap-1", "my-cluster", "available", 5439, "admin");
        when(snapshotBackend.get("snap-1")).thenReturn(Optional.of(s));

        List<Snapshot> list = service.describeSnapshots("snap-1");
        assertEquals(1, list.size());
        assertEquals("snap-1", list.get(0).getSnapshotIdentifier());
    }

    @Test
    void testDescribeSnapshotsNotFound() {
        when(snapshotBackend.get("missing-snap")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () -> service.describeSnapshots("missing-snap"));
    }

    @Test
    void testDeleteSnapshot() {
        Snapshot s = new Snapshot("snap-1", "my-cluster", "available", 5439, "admin");
        s.setSqlDump(dumpPath("snap-1"));
        when(snapshotBackend.get("snap-1")).thenReturn(Optional.of(s));

        Snapshot deleted = service.deleteSnapshot("snap-1");
        assertNotNull(deleted);
        assertEquals("deleted", deleted.getStatus());
        verify(snapshotBackend).delete("snap-1");
        verify(snapshotBackend).flush();
    }

    @Test
    void testDeleteSnapshotNotFound() {
        when(snapshotBackend.get("missing-snap")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () -> service.deleteSnapshot("missing-snap"));
    }

    @Test
    void testRestoreFromClusterSnapshot() {
        Cluster sourceCluster = new Cluster();
        sourceCluster.setClusterIdentifier("source-cluster");
        sourceCluster.setMasterPassword("password123");

        Snapshot snapshot = new Snapshot("my-snapshot", "source-cluster", "available", 5439, "admin");
        snapshot.setSqlDump(dumpPath("my-snapshot"));
        when(clusterBackend.get("restored-cluster")).thenReturn(Optional.empty());
        when(clusterBackend.get("source-cluster")).thenReturn(Optional.of(sourceCluster));
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));
        when(cm.start(eq("111111111111"), eq("restored-cluster"), eq("admin"), eq("password123")))
                .thenReturn(new RedshiftContainerHandle("c-new", "restored-cluster", "localhost", 5432));
        doNothing().when(cm).restoreSnapshot(eq("111111111111"), eq("restored-cluster"), eq("admin"), any(Path.class));

        Cluster cluster = service.restoreFromClusterSnapshot("restored-cluster", "my-snapshot", "dc2.large");
        assertNotNull(cluster);
        assertEquals("restored-cluster", cluster.getClusterIdentifier());
        assertEquals("available", cluster.getClusterStatus());
        assertEquals("admin", cluster.getMasterUsername());
        assertEquals("password123", cluster.getMasterPassword());
        assertEquals("dc2.large", cluster.getNodeType());
        assertEquals("localhost", cluster.getEndpoint().getAddress());

        // Restore must use the source cluster's actual password, not a hardcoded one
        verify(cm).start("111111111111", "restored-cluster", "admin", "password123");
        verify(cm).restoreSnapshot(eq("111111111111"), eq("restored-cluster"), eq("admin"), any(Path.class));
        verify(clusterBackend, times(2)).put(eq("restored-cluster"), any(Cluster.class));
        verify(clusterBackend, times(2)).flush();
    }

    @Test
    void testRestoreFromClusterSnapshotUsesStoredPasswordAfterSourceClusterDeleted() {
        Snapshot snapshot = new Snapshot("my-snapshot", "deleted-source", "available", 5439, "admin");
        snapshot.setMasterPassword("original-secret");
        snapshot.setSqlDump(dumpPath("my-snapshot"));
        when(clusterBackend.get("restored-cluster")).thenReturn(Optional.empty());
        // Source cluster no longer exists, but the snapshot itself still carries the original password
        when(clusterBackend.get("deleted-source")).thenReturn(Optional.empty());
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));
        when(cm.start(eq("111111111111"), eq("restored-cluster"), eq("admin"), eq("original-secret")))
                .thenReturn(new RedshiftContainerHandle("c-new", "restored-cluster", "localhost", 5432));
        doNothing().when(cm).restoreSnapshot(eq("111111111111"), eq("restored-cluster"), eq("admin"), any(Path.class));

        Cluster cluster = service.restoreFromClusterSnapshot("restored-cluster", "my-snapshot", "dc2.large");
        assertEquals("original-secret", cluster.getMasterPassword());
        verify(cm).start("111111111111", "restored-cluster", "admin", "original-secret");
    }

    @Test
    void testRestoreFromClusterSnapshotFallsBackToAdminWhenSourceClusterGone() {
        Snapshot snapshot = new Snapshot("my-snapshot", "deleted-source", "available", 5439, "admin");
        snapshot.setSqlDump(dumpPath("my-snapshot"));
        when(clusterBackend.get("restored-cluster")).thenReturn(Optional.empty());
        when(clusterBackend.get("deleted-source")).thenReturn(Optional.empty());
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));
        when(cm.start(eq("111111111111"), eq("restored-cluster"), eq("admin"), eq("admin")))
                .thenReturn(new RedshiftContainerHandle("c-new", "restored-cluster", "localhost", 5432));
        doNothing().when(cm).restoreSnapshot(eq("111111111111"), eq("restored-cluster"), eq("admin"), any(Path.class));

        Cluster cluster = service.restoreFromClusterSnapshot("restored-cluster", "my-snapshot", "dc2.large");
        assertEquals("admin", cluster.getMasterPassword());
        verify(cm).start("111111111111", "restored-cluster", "admin", "admin");
    }

    @Test
    void testRestoreFromClusterSnapshotAlreadyExists() {
        when(clusterBackend.get("existing-cluster")).thenReturn(Optional.of(new Cluster()));

        assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("existing-cluster", "my-snapshot"));
    }

    @Test
    void testRestoreFromClusterSnapshotNotFound() {
        when(clusterBackend.get("new-cluster")).thenReturn(Optional.empty());
        when(snapshotBackend.get("missing-snapshot")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("new-cluster", "missing-snapshot"));
    }

    @Test
    void testRestoreFromClusterSnapshotFailure() {
        Snapshot snapshot = new Snapshot("my-snapshot", "source-cluster", "available", 5439, "admin", dumpPath("my-snapshot"));
        when(clusterBackend.get("failed-cluster")).thenReturn(Optional.empty());
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));
        when(cm.start(any(), any(), any(), any())).thenThrow(new RuntimeException("Docker error"));

        assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("failed-cluster", "my-snapshot"));
    }

    @Test
    void testRestoreFromClusterSnapshotRejectsUntrustedDumpPathBeforeProvisioning() {
        // A dump path persisted outside the account dir (e.g. by pre-validation code) must be
        // rejected up front — no cluster record, no container.
        Snapshot snapshot = new Snapshot("my-snapshot", "source-cluster", "available", 5439, "admin", "/etc/shadow");
        when(clusterBackend.get("restored-cluster")).thenReturn(Optional.empty());
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("restored-cluster", "my-snapshot", "dc2.large"));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        verify(cm, never()).start(any(), any(), any(), any());
        verify(cm, never()).restoreSnapshot(any(), any(), any(), any());
        verify(clusterBackend, never()).put(anyString(), any(Cluster.class));
    }

    @Test
    void deleteClusterStopsTheProxyAndReleasesThePort() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setProxyPort(7105);
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        when(clusterBackend.accountId()).thenReturn("111111111111");

        service.deleteCluster("c1");

        verify(proxyManager).stopProxy("111111111111:c1");
    }

    @Test
    void modifyClusterPasswordUpdatesTheProxySnapshot() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("old");
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        when(clusterBackend.accountId()).thenReturn("111111111111");

        service.modifyCluster("c1", null, null, "NewSecret1", null, null);

        verify(proxyManager).updateMasterPassword("111111111111:c1", "NewSecret1");
    }

    @Test
    void restoreFromSnapshotStartsAProxyForTheNewCluster() {
        Snapshot snap = new Snapshot();
        snap.setSnapshotIdentifier("s1");
        snap.setClusterIdentifier("src");
        snap.setMasterUsername("admin");
        snap.setMasterPassword("Secret123");
        snap.setSqlDump(null);
        when(snapshotBackend.get("s1")).thenReturn(Optional.of(snap));
        when(clusterBackend.get("restored")).thenReturn(Optional.empty());
        when(clusterBackend.accountId()).thenReturn("111111111111");
        RedshiftContainerHandle handle = mock(RedshiftContainerHandle.class);
        when(handle.getHost()).thenReturn("172.17.0.10");
        when(handle.getPort()).thenReturn(32810);
        when(cm.start(eq("111111111111"), eq("restored"), eq("admin"), eq("Secret123"))).thenReturn(handle);

        Cluster restored = service.restoreFromClusterSnapshot("restored", "s1");

        assertEquals("localhost", restored.getEndpoint().getAddress());
        assertEquals(restored.getEndpoint().getPort(), restored.getProxyPort());
        verify(proxyManager).startProxy(eq("111111111111:restored"), anyInt(),
                eq("172.17.0.10"), eq(32810), eq("localhost"),
                eq("admin"), eq("Secret123"), eq("dev"), any());
    }

    @Test
    void testCreateClusterParameterGroup() {
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.empty());

        ClusterParameterGroup pg = service.createClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        assertNotNull(pg);
        assertEquals("my-pg", pg.getParameterGroupName());
        assertEquals("redshift-1.0", pg.getParameterGroupFamily());
        assertEquals("custom pg", pg.getDescription());
        verify(parameterGroupBackend).put(eq("my-pg"), any(ClusterParameterGroup.class));
        verify(parameterGroupBackend).flush();
    }

    @Test
    void testCreateClusterParameterGroupAlreadyExists() {
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(new ClusterParameterGroup()));

        assertThrows(AwsException.class, () ->
                service.createClusterParameterGroup("my-pg", "redshift-1.0", "custom pg"));
    }

    @Test
    void testDescribeClusterParameterGroups() {
        ClusterParameterGroup pg = new ClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(pg));

        List<ClusterParameterGroup> list = service.describeClusterParameterGroups("my-pg");
        assertEquals(1, list.size());
        assertEquals("my-pg", list.get(0).getParameterGroupName());
    }

    @Test
    void testDescribeClusterParameterGroupsNotFound() {
        when(parameterGroupBackend.get("missing-pg")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () ->
                service.describeClusterParameterGroups("missing-pg"));
    }

    @Test
    void testDeleteClusterParameterGroup() {
        ClusterParameterGroup pg = new ClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(pg));

        ClusterParameterGroup deleted = service.deleteClusterParameterGroup("my-pg");
        assertNotNull(deleted);
        assertEquals("my-pg", deleted.getParameterGroupName());
        verify(parameterGroupBackend).delete("my-pg");
        verify(parameterGroupBackend).flush();
    }

    @Test
    void testDeleteClusterParameterGroupNotFound() {
        when(parameterGroupBackend.get("missing-pg")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () ->
                service.deleteClusterParameterGroup("missing-pg"));
    }

    @Test
    void testModifyClusterParameterGroup() {
        ClusterParameterGroup group = new ClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(group));

        ClusterParameterGroup updated = service.modifyClusterParameterGroup("my-pg",
                List.of(new Parameter("max_cursor_result_set_size", "1000")));

        assertEquals("1000", updated.getParameters().stream()
                .filter(p -> "max_cursor_result_set_size".equals(p.getParameterName()))
                .findFirst().orElseThrow().getParameterValue());
        verify(parameterGroupBackend).put(eq("my-pg"), any(ClusterParameterGroup.class));
        verify(parameterGroupBackend).flush();
    }

    @Test
    void testModifyClusterParameterGroupAppendsUnknownParameter() {
        ClusterParameterGroup group = new ClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(group));

        ClusterParameterGroup updated = service.modifyClusterParameterGroup("my-pg",
                List.of(new Parameter("statement_timeout", "5000")));

        assertTrue(updated.getParameters().stream()
                .anyMatch(p -> "statement_timeout".equals(p.getParameterName()) && "5000".equals(p.getParameterValue())));
    }

    @Test
    void testModifyClusterParameterGroupNotFound() {
        when(parameterGroupBackend.get("missing")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () ->
                service.modifyClusterParameterGroup("missing", List.of(new Parameter("x", "y"))));
    }

    @Test
    void testDescribeClusterParametersReturnsStoredValues() {
        ClusterParameterGroup group = new ClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        group.setParameters(new ArrayList<>(List.of(new Parameter("statement_timeout", "5000"))));
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(group));

        List<Parameter> params = service.describeClusterParameters("my-pg");

        assertEquals(1, params.size());
        assertEquals("statement_timeout", params.get(0).getParameterName());
        assertEquals("5000", params.get(0).getParameterValue());
    }

    @Test
    void testModifyClusterParameterGroupPreservesMetadata() {
        ClusterParameterGroup group = new ClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(group));

        // Modify using 2-arg constructor (only name and value) - should preserve description and dataType
        ClusterParameterGroup updated = service.modifyClusterParameterGroup("my-pg",
                List.of(new Parameter("max_cursor_result_set_size", "1000")));

        Parameter modified = updated.getParameters().stream()
                .filter(p -> "max_cursor_result_set_size".equals(p.getParameterName()))
                .findFirst().orElseThrow();
        assertEquals("1000", modified.getParameterValue());
        assertEquals("Maximum cursor result set size", modified.getDescription());
        assertEquals("integer", modified.getDataType());
    }

    @Test
    void testCreateAndListTagsForCluster() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        service.createTags("arn:aws:redshift:us-east-1:111111111111:cluster:my-cluster",
                Map.of("env", "test"));

        assertEquals("test", cluster.getTags().get("env"));
        verify(clusterBackend).put(eq("my-cluster"), any(Cluster.class));
    }

    @Test
    void testDeleteTagsForCluster() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setTags(new LinkedHashMap<>(Map.of("env", "test", "team", "data")));
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        service.deleteTags("arn:aws:redshift:us-east-1:111111111111:cluster:my-cluster", List.of("env"));

        assertEquals(Map.of("team", "data"), cluster.getTags());
    }

    @Test
    void testCreateTagsRejectsNonArnResourceName() {
        assertThrows(AwsException.class, () ->
                service.createTags("my-cluster", Map.of("env", "test")));
    }

    @Test
    void testCreateTagsRejectsUnknownResourceType() {
        assertThrows(AwsException.class, () ->
                service.createTags("arn:aws:redshift:us-east-1:111111111111:reservednode:foo", Map.of("env", "test")));
    }

    @Test
    void testDescribeTagsForSpecificResource() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setTags(new LinkedHashMap<>(Map.of("env", "test")));
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        List<RedshiftService.TaggedResource> tagged =
                service.describeTags("arn:aws:redshift:us-east-1:111111111111:cluster:my-cluster", null, null);

        assertEquals(1, tagged.size());
        assertEquals("cluster", tagged.get(0).resourceType());
        assertEquals("env", tagged.get(0).tagKey());
        assertEquals("test", tagged.get(0).tagValue());
    }

    @Test
    void testDescribeTagsScansAllResourcesOfType() {
        Cluster a = new Cluster();
        a.setClusterIdentifier("cluster-a");
        a.setTags(new LinkedHashMap<>(Map.of("env", "prod")));
        Cluster b = new Cluster();
        b.setClusterIdentifier("cluster-b");
        b.setTags(new LinkedHashMap<>());
        when(clusterBackend.scan(any())).thenReturn(List.of(a, b));
        when(snapshotBackend.scan(any())).thenReturn(List.of());
        when(parameterGroupBackend.scan(any())).thenReturn(List.of());
        when(subnetGroupBackend.scan(any())).thenReturn(List.of());

        List<RedshiftService.TaggedResource> tagged = service.describeTags(null, "cluster", null);

        assertEquals(1, tagged.size());
        assertEquals("cluster-a", extractResourceId(tagged.get(0).resourceName()));
    }

    @Test
    void testCreateClusterSubnetGroup() {
        when(subnetGroupBackend.get("my-subnet-group")).thenReturn(Optional.empty());

        ClusterSubnetGroup group = service.createClusterSubnetGroup(
                "my-subnet-group", "test group", "vpc-123", List.of("subnet-1", "subnet-2"));

        assertEquals("my-subnet-group", group.getClusterSubnetGroupName());
        assertEquals(List.of("subnet-1", "subnet-2"), group.getSubnetIds());
        verify(subnetGroupBackend).put(eq("my-subnet-group"), any(ClusterSubnetGroup.class));
        verify(subnetGroupBackend).flush();
    }

    @Test
    void testCreateClusterSubnetGroupAlreadyExists() {
        when(subnetGroupBackend.get("existing")).thenReturn(Optional.of(new ClusterSubnetGroup()));

        assertThrows(AwsException.class, () ->
                service.createClusterSubnetGroup("existing", "d", "vpc-1", List.of("subnet-1")));
    }

    @Test
    void testDescribeClusterSubnetGroups() {
        ClusterSubnetGroup group = new ClusterSubnetGroup("my-group", "d", "vpc-1", List.of("subnet-1"));
        when(subnetGroupBackend.get("my-group")).thenReturn(Optional.of(group));

        List<ClusterSubnetGroup> list = service.describeClusterSubnetGroups("my-group");

        assertEquals(1, list.size());
        assertEquals("my-group", list.get(0).getClusterSubnetGroupName());
    }

    @Test
    void testModifyClusterSubnetGroup() {
        ClusterSubnetGroup group = new ClusterSubnetGroup("my-group", "old", "vpc-1", List.of("subnet-1"));
        when(subnetGroupBackend.get("my-group")).thenReturn(Optional.of(group));

        ClusterSubnetGroup updated = service.modifyClusterSubnetGroup("my-group", "new", List.of("subnet-2", "subnet-3"));

        assertEquals("new", updated.getDescription());
        assertEquals(List.of("subnet-2", "subnet-3"), updated.getSubnetIds());
    }

    @Test
    void testDeleteClusterSubnetGroup() {
        ClusterSubnetGroup group = new ClusterSubnetGroup("my-group", "d", "vpc-1", List.of("subnet-1"));
        when(subnetGroupBackend.get("my-group")).thenReturn(Optional.of(group));

        ClusterSubnetGroup deleted = service.deleteClusterSubnetGroup("my-group");

        assertEquals("my-group", deleted.getClusterSubnetGroupName());
        verify(subnetGroupBackend).delete("my-group");
        verify(subnetGroupBackend).flush();
    }

    @Test
    void testDeleteClusterSubnetGroupNotFound() {
        when(subnetGroupBackend.get("missing")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () -> service.deleteClusterSubnetGroup("missing"));
    }

    private static String extractResourceId(String arn) {
        String resource = arn.substring(arn.lastIndexOf(':') + 1);
        return resource.contains("/") ? resource.substring(resource.lastIndexOf('/') + 1) : resource;
    }
}
