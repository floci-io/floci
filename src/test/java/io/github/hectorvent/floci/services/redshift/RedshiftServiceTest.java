package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.ClusterParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshift.model.Parameter;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    private RedshiftContainerManager cm;
    private io.github.hectorvent.floci.core.common.RegionResolver regionResolver;
    private RedshiftService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sf = mock(StorageFactory.class);
        clusterBackend = mock(AccountAwareStorageBackend.class);
        snapshotBackend = mock(AccountAwareStorageBackend.class);
        snapshotDumpBackend = mock(AccountAwareStorageBackend.class);
        parameterGroupBackend = mock(AccountAwareStorageBackend.class);
        cm = mock(RedshiftContainerManager.class);
        
        io.github.hectorvent.floci.config.EmulatorConfig config = mock(io.github.hectorvent.floci.config.EmulatorConfig.class);
        io.github.hectorvent.floci.config.EmulatorConfig.StorageConfig storageConfig = mock(io.github.hectorvent.floci.config.EmulatorConfig.StorageConfig.class);
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.persistentPath()).thenReturn("target/test-data");

        when(sf.<Cluster>create(eq("redshift"), eq("redshift-clusters.json"), any())).thenReturn(clusterBackend);
        when(sf.<Snapshot>create(eq("redshift"), eq("redshift-snapshots.json"), any())).thenReturn(snapshotBackend);
        when(sf.<ClusterParameterGroup>create(eq("redshift"), eq("redshift-parameter-groups.json"), any())).thenReturn(parameterGroupBackend);
        when(clusterBackend.accountId()).thenReturn("111111111111");

        regionResolver = new io.github.hectorvent.floci.core.common.RegionResolver("us-east-1", "111111111111");

        service = new RedshiftService(sf, cm, config, regionResolver);
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
    void testCreateClusterAlreadyExists() {
        when(clusterBackend.get("existing-cluster")).thenReturn(Optional.of(new Cluster()));

        assertThrows(AwsException.class, () ->
                service.createCluster("existing-cluster", "dc2.large", "admin", "password123"));
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
    void testCreateSnapshot() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("secret-pw");
        cluster.setEndpoint(new Endpoint("localhost", 5439));

        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.empty());
        doNothing().when(cm).takeSnapshot(eq("111111111111"), eq("my-cluster"), eq("admin"), any(java.nio.file.Path.class));

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
        verify(cm).takeSnapshot(eq("111111111111"), eq("my-cluster"), eq("admin"), any(java.nio.file.Path.class));
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
        s.setSqlDump("snap-1.sql");
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
        snapshot.setSqlDump("my-snapshot.sql");
        when(clusterBackend.get("restored-cluster")).thenReturn(Optional.empty());
        when(clusterBackend.get("source-cluster")).thenReturn(Optional.of(sourceCluster));
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));
        when(cm.start(eq("111111111111"), eq("restored-cluster"), eq("admin"), eq("password123")))
                .thenReturn(new RedshiftContainerHandle("c-new", "restored-cluster", "localhost", 5432));
        doNothing().when(cm).restoreSnapshot(eq("111111111111"), eq("restored-cluster"), eq("admin"), any(java.nio.file.Path.class));

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
        verify(cm).restoreSnapshot(eq("111111111111"), eq("restored-cluster"), eq("admin"), any(java.nio.file.Path.class));
        verify(clusterBackend, times(2)).put(eq("restored-cluster"), any(Cluster.class));
        verify(clusterBackend, times(2)).flush();
    }

    @Test
    void testRestoreFromClusterSnapshotUsesStoredPasswordAfterSourceClusterDeleted() {
        Snapshot snapshot = new Snapshot("my-snapshot", "deleted-source", "available", 5439, "admin");
        snapshot.setMasterPassword("original-secret");
        snapshot.setSqlDump("my-snapshot.sql");
        when(clusterBackend.get("restored-cluster")).thenReturn(Optional.empty());
        // Source cluster no longer exists, but the snapshot itself still carries the original password
        when(clusterBackend.get("deleted-source")).thenReturn(Optional.empty());
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));
        when(cm.start(eq("111111111111"), eq("restored-cluster"), eq("admin"), eq("original-secret")))
                .thenReturn(new RedshiftContainerHandle("c-new", "restored-cluster", "localhost", 5432));
        doNothing().when(cm).restoreSnapshot(eq("111111111111"), eq("restored-cluster"), eq("admin"), any(java.nio.file.Path.class));

        Cluster cluster = service.restoreFromClusterSnapshot("restored-cluster", "my-snapshot", "dc2.large");
        assertEquals("original-secret", cluster.getMasterPassword());
        verify(cm).start("111111111111", "restored-cluster", "admin", "original-secret");
    }

    @Test
    void testRestoreFromClusterSnapshotFallsBackToAdminWhenSourceClusterGone() {
        Snapshot snapshot = new Snapshot("my-snapshot", "deleted-source", "available", 5439, "admin");
        snapshot.setSqlDump("my-snapshot.sql");
        when(clusterBackend.get("restored-cluster")).thenReturn(Optional.empty());
        when(clusterBackend.get("deleted-source")).thenReturn(Optional.empty());
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));
        when(cm.start(eq("111111111111"), eq("restored-cluster"), eq("admin"), eq("admin")))
                .thenReturn(new RedshiftContainerHandle("c-new", "restored-cluster", "localhost", 5432));
        doNothing().when(cm).restoreSnapshot(eq("111111111111"), eq("restored-cluster"), eq("admin"), any(java.nio.file.Path.class));

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
        Snapshot snapshot = new Snapshot("my-snapshot", "source-cluster", "available", 5439, "admin", "CREATE TABLE t;");
        when(clusterBackend.get("failed-cluster")).thenReturn(Optional.empty());
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));
        when(snapshotDumpBackend.get("my-snapshot")).thenReturn(Optional.of("CREATE TABLE t;"));
        when(cm.start(any(), any(), any(), any())).thenThrow(new RuntimeException("Docker error"));

        assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("failed-cluster", "my-snapshot"));
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
        group.setParameters(new java.util.ArrayList<>(List.of(new Parameter("statement_timeout", "5000"))));
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(group));

        List<Parameter> params = service.describeClusterParameters("my-pg");

        assertEquals(1, params.size());
        assertEquals("statement_timeout", params.get(0).getParameterName());
        assertEquals("5000", params.get(0).getParameterValue());
    }

    @Test
    void testCreateAndListTagsForCluster() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        service.createTags("arn:aws:redshift:us-east-1:111111111111:cluster:my-cluster",
                java.util.Map.of("env", "test"));

        assertEquals("test", cluster.getTags().get("env"));
        verify(clusterBackend).put(eq("my-cluster"), any(Cluster.class));
    }

    @Test
    void testDeleteTagsForCluster() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setTags(new java.util.LinkedHashMap<>(java.util.Map.of("env", "test", "team", "data")));
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        service.deleteTags("arn:aws:redshift:us-east-1:111111111111:cluster:my-cluster", java.util.List.of("env"));

        assertEquals(java.util.Map.of("team", "data"), cluster.getTags());
    }

    @Test
    void testCreateTagsRejectsNonArnResourceName() {
        assertThrows(AwsException.class, () ->
                service.createTags("my-cluster", java.util.Map.of("env", "test")));
    }

    @Test
    void testCreateTagsRejectsUnknownResourceType() {
        assertThrows(AwsException.class, () ->
                service.createTags("arn:aws:redshift:us-east-1:111111111111:reservednode:foo", java.util.Map.of("env", "test")));
    }

    @Test
    void testDescribeTagsForSpecificResource() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setTags(new java.util.LinkedHashMap<>(java.util.Map.of("env", "test")));
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
        a.setTags(new java.util.LinkedHashMap<>(java.util.Map.of("env", "prod")));
        Cluster b = new Cluster();
        b.setClusterIdentifier("cluster-b");
        b.setTags(new java.util.LinkedHashMap<>());
        when(clusterBackend.scan(any())).thenReturn(java.util.List.of(a, b));
        when(snapshotBackend.scan(any())).thenReturn(java.util.List.of());
        when(parameterGroupBackend.scan(any())).thenReturn(java.util.List.of());

        List<RedshiftService.TaggedResource> tagged = service.describeTags(null, "cluster", null);

        assertEquals(1, tagged.size());
        assertEquals("cluster-a", extractResourceId(tagged.get(0).resourceName()));
    }

    private static String extractResourceId(String arn) {
        String resource = arn.substring(arn.lastIndexOf(':') + 1);
        return resource.contains("/") ? resource.substring(resource.lastIndexOf('/') + 1) : resource;
    }
}
