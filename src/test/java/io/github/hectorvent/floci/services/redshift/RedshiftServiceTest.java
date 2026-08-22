package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.ClusterParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class RedshiftServiceTest {

    private StorageFactory sf;
    private AccountAwareStorageBackend<Cluster> clusterBackend;
    private AccountAwareStorageBackend<Snapshot> snapshotBackend;
    private AccountAwareStorageBackend<ClusterParameterGroup> parameterGroupBackend;
    private RedshiftContainerManager cm;
    private RedshiftService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sf = mock(StorageFactory.class);
        clusterBackend = mock(AccountAwareStorageBackend.class);
        snapshotBackend = mock(AccountAwareStorageBackend.class);
        parameterGroupBackend = mock(AccountAwareStorageBackend.class);
        cm = mock(RedshiftContainerManager.class);

        when(sf.<Cluster>create(eq("redshift"), eq("redshift-clusters.json"), any())).thenReturn(clusterBackend);
        when(sf.<Snapshot>create(eq("redshift"), eq("redshift-snapshots.json"), any())).thenReturn(snapshotBackend);
        when(sf.<ClusterParameterGroup>create(eq("redshift"), eq("redshift-parameter-groups.json"), any())).thenReturn(parameterGroupBackend);

        service = new RedshiftService(sf, cm);
    }

    @Test
    void testCreateCluster() {
        when(clusterBackend.get(anyString())).thenReturn(Optional.empty());
        when(cm.start(any(), any(), any())).thenReturn(new RedshiftContainerHandle("c1", "my-cluster", "localhost", 5432));

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
        verify(cm).stop("test-c");
        verify(clusterBackend).delete("test-c");
    }

    @Test
    void testCreateSnapshot() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setMasterUsername("admin");
        cluster.setEndpoint(new Endpoint("localhost", 5439));

        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.empty());

        Snapshot snapshot = service.createSnapshot("my-snapshot", "my-cluster");
        assertNotNull(snapshot);
        assertEquals("my-snapshot", snapshot.getSnapshotIdentifier());
        assertEquals("my-cluster", snapshot.getClusterIdentifier());
        assertEquals("available", snapshot.getStatus());
        assertEquals("admin", snapshot.getMasterUsername());
        assertEquals(5439, snapshot.getPort());
        verify(snapshotBackend).put(eq("my-snapshot"), any(Snapshot.class));
        verify(snapshotBackend).flush();
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
}
