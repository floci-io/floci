package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
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
}
