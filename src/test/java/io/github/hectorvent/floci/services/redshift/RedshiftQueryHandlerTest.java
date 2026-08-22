package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.ClusterParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class RedshiftQueryHandlerTest {

    private RedshiftQueryHandler handler;
    private RedshiftService service;

    @BeforeEach
    void setUp() {
        service = mock(RedshiftService.class);
        handler = new RedshiftQueryHandler(service);
    }

    @Test
    void testCreateClusterAction() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "test-cluster");
        params.putSingle("NodeType", "dc2.large");
        params.putSingle("MasterUsername", "admin");
        params.putSingle("MasterUserPassword", "password123");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("test-cluster");
        cluster.setClusterStatus("available");
        when(service.createCluster(any(), any(), any(), any())).thenReturn(cluster);

        Response response = handler.handle("CreateCluster", params);

        assertEquals(200, response.getStatus());
        assertNotNull(response.getEntity());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<ClusterIdentifier>test-cluster</ClusterIdentifier>"));
        assertTrue(xml.contains("<ClusterStatus>available</ClusterStatus>"));
        assertTrue(xml.contains("<RequestId>test-req-id</RequestId>"));
    }
    
    @Test
    void testDescribeClusters() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "test-cluster");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("test-cluster");
        cluster.setClusterStatus("available");
        when(service.describeClusters(any())).thenReturn(List.of(cluster));

        Response response = handler.handle("DescribeClusters", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<ClusterIdentifier>test-cluster</ClusterIdentifier>"));
    }
    
    @Test
    void testDeleteCluster() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "test-cluster");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("test-cluster");
        cluster.setClusterStatus("deleting");
        when(service.deleteCluster(any())).thenReturn(cluster);

        Response response = handler.handle("DeleteCluster", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<ClusterIdentifier>test-cluster</ClusterIdentifier>"));
        assertTrue(xml.contains("<ClusterStatus>deleting</ClusterStatus>"));
    }

    @Test
    void testCreateClusterSnapshot() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("SnapshotIdentifier", "test-snapshot");
        params.putSingle("ClusterIdentifier", "test-cluster");

        Snapshot snapshot = new Snapshot("test-snapshot", "test-cluster", "available", 5439, "admin");
        when(service.createSnapshot("test-snapshot", "test-cluster")).thenReturn(snapshot);

        Response response = handler.handle("CreateClusterSnapshot", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<SnapshotIdentifier>test-snapshot</SnapshotIdentifier>"));
        assertTrue(xml.contains("<ClusterIdentifier>test-cluster</ClusterIdentifier>"));
        assertTrue(xml.contains("<Status>available</Status>"));
        assertTrue(xml.contains("<Port>5439</Port>"));
        assertTrue(xml.contains("<MasterUsername>admin</MasterUsername>"));
        assertTrue(xml.contains("<RequestId>test-req-id</RequestId>"));
    }

    @Test
    void testDescribeClusterSnapshots() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("SnapshotIdentifier", "test-snapshot");
        params.putSingle("ClusterIdentifier", "test-cluster");

        Snapshot snapshot = new Snapshot("test-snapshot", "test-cluster", "available", 5439, "admin");
        when(service.describeSnapshots("test-snapshot", "test-cluster")).thenReturn(List.of(snapshot));

        Response response = handler.handle("DescribeClusterSnapshots", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<Snapshots>"));
        assertTrue(xml.contains("<SnapshotIdentifier>test-snapshot</SnapshotIdentifier>"));
        assertTrue(xml.contains("</Snapshots>"));
    }

    @Test
    void testDeleteClusterSnapshot() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("SnapshotIdentifier", "test-snapshot");

        Snapshot snapshot = new Snapshot("test-snapshot", "test-cluster", "deleted", 5439, "admin");
        when(service.deleteSnapshot("test-snapshot")).thenReturn(snapshot);

        Response response = handler.handle("DeleteClusterSnapshot", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<SnapshotIdentifier>test-snapshot</SnapshotIdentifier>"));
        assertTrue(xml.contains("<Status>deleted</Status>"));
    }

    @Test
    void testRestoreFromClusterSnapshot() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "restored-cluster");
        params.putSingle("SnapshotIdentifier", "test-snapshot");
        params.putSingle("NodeType", "dc2.large");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("restored-cluster");
        cluster.setNodeType("dc2.large");
        cluster.setMasterUsername("admin");
        cluster.setClusterStatus("available");
        cluster.setEndpoint(new Endpoint("localhost", 5439));
        when(service.restoreFromClusterSnapshot("restored-cluster", "test-snapshot", "dc2.large")).thenReturn(cluster);

        Response response = handler.handle("RestoreFromClusterSnapshot", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<ClusterIdentifier>restored-cluster</ClusterIdentifier>"));
        assertTrue(xml.contains("<ClusterStatus>available</ClusterStatus>"));
        assertTrue(xml.contains("<Address>localhost</Address>"));
        assertTrue(xml.contains("<Port>5439</Port>"));
    }

    @Test
    void testCreateClusterParameterGroup() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "test-pg");
        params.putSingle("ParameterGroupFamily", "redshift-1.0");
        params.putSingle("Description", "custom redshift param group");

        ClusterParameterGroup group = new ClusterParameterGroup("test-pg", "redshift-1.0", "custom redshift param group");
        when(service.createClusterParameterGroup("test-pg", "redshift-1.0", "custom redshift param group")).thenReturn(group);

        Response response = handler.handle("CreateClusterParameterGroup", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<ParameterGroupName>test-pg</ParameterGroupName>"));
        assertTrue(xml.contains("<ParameterGroupFamily>redshift-1.0</ParameterGroupFamily>"));
        assertTrue(xml.contains("<Description>custom redshift param group</Description>"));
    }

    @Test
    void testDescribeClusterParameterGroups() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "test-pg");

        ClusterParameterGroup group = new ClusterParameterGroup("test-pg", "redshift-1.0", "custom redshift param group");
        when(service.describeClusterParameterGroups("test-pg")).thenReturn(List.of(group));

        Response response = handler.handle("DescribeClusterParameterGroups", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<ParameterGroups>"));
        assertTrue(xml.contains("<ParameterGroupName>test-pg</ParameterGroupName>"));
        assertTrue(xml.contains("</ParameterGroups>"));
    }

    @Test
    void testDescribeClusterParameters() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "test-pg");

        ClusterParameterGroup group = new ClusterParameterGroup("test-pg", "redshift-1.0", "custom redshift param group");
        when(service.describeClusterParameterGroups("test-pg")).thenReturn(List.of(group));

        Response response = handler.handle("DescribeClusterParameters", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<Parameters>"));
        assertTrue(xml.contains("</Parameters>"));
    }

    @Test
    void testDeleteClusterParameterGroup() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "test-pg");

        ClusterParameterGroup group = new ClusterParameterGroup("test-pg", "redshift-1.0", "custom redshift param group");
        when(service.deleteClusterParameterGroup("test-pg")).thenReturn(group);

        Response response = handler.handle("DeleteClusterParameterGroup", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<DeleteClusterParameterGroupResponse>"));
    }
}
