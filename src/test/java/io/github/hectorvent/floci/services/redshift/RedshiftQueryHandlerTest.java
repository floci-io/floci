package io.github.hectorvent.floci.services.redshift;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftQueryHandlerTest {

    private RedshiftQueryHandler handler;
    private RedshiftService service;

    @BeforeEach
    void setUp() {
        service = new RedshiftService();
        handler = new RedshiftQueryHandler(service);
    }

    @Test
    void testCreateClusterAction() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "test-cluster");
        params.putSingle("NodeType", "dc2.large");
        params.putSingle("MasterUsername", "admin");
        params.putSingle("MasterUserPassword", "password123");

        Response response = handler.handle("CreateCluster", params);

        assertEquals(200, response.getStatus());
        assertNotNull(response.getEntity());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<ClusterIdentifier>test-cluster</ClusterIdentifier>"));
        assertTrue(xml.contains("<ClusterStatus>creating</ClusterStatus>"));
        assertTrue(xml.contains("<RequestId>test-req-id</RequestId>"));
    }

    @Test
    void testUnknownAction() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        Response response = handler.handle("UnknownAction", params);
        assertEquals(400, response.getStatus());
    }
}
