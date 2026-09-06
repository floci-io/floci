package io.github.hectorvent.floci.services.neptune;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NeptuneQueryHandlerTest {

    private NeptuneService service;
    private NeptuneQueryHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(NeptuneService.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAvailabilityZone()).thenReturn("us-east-1a");
        handler = new NeptuneQueryHandler(service, config);
    }

    @Test
    void unhandledExceptionRendersXmlInternalFailure() {
        when(service.createDbCluster(any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new RuntimeException("Docker daemon connection failed"));

        MultivaluedMap<String, String> p = new MultivaluedHashMap<>();
        p.add("DBClusterIdentifier", "mycluster");

        Response response = handler.handle("CreateDBCluster", p);
        assertEquals(500, response.getStatus());
        assertEquals("application/xml", response.getMediaType().toString());

        String body = (String) response.getEntity();
        assertTrue(body.contains("<ErrorResponse xmlns=\"http://rds.amazonaws.com/doc/2014-10-31/\">"), body);
        assertTrue(body.contains("<Type>Receiver</Type>"), body);
        assertTrue(body.contains("<Code>InternalFailure</Code>"), body);
        assertTrue(body.contains("<Message>Unexpected error: Docker daemon connection failed</Message>"), body);
    }

    @Test
    void unhandledExceptionInCreateDbInstanceRendersXmlInternalFailure() {
        when(service.createDbInstance(any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new RuntimeException("Container startup timed out"));

        MultivaluedMap<String, String> p = new MultivaluedHashMap<>();
        p.add("DBInstanceIdentifier", "myinstance");
        p.add("DBClusterIdentifier", "mycluster");

        Response response = handler.handle("CreateDBInstance", p);
        assertEquals(500, response.getStatus());
        assertEquals("application/xml", response.getMediaType().toString());

        String body = (String) response.getEntity();
        assertTrue(body.contains("<ErrorResponse xmlns=\"http://rds.amazonaws.com/doc/2014-10-31/\">"), body);
        assertTrue(body.contains("<Type>Receiver</Type>"), body);
        assertTrue(body.contains("<Code>InternalFailure</Code>"), body);
        assertTrue(body.contains("<Message>Unexpected error: Container startup timed out</Message>"), body);
    }

    @Test
    void awsExceptionRendersXmlError() {
        when(service.getDbCluster("missing"))
                .thenThrow(new AwsException("DBClusterNotFoundFault", "DBCluster missing not found.", 404));

        MultivaluedMap<String, String> p = new MultivaluedHashMap<>();
        p.add("DBClusterIdentifier", "missing");

        Response response = handler.handle("DescribeDBClusters", p);
        assertEquals(404, response.getStatus());
        assertEquals("application/xml", response.getMediaType().toString());

        String body = (String) response.getEntity();
        assertTrue(body.contains("<Code>DBClusterNotFoundFault</Code>"), body);
        assertTrue(body.contains("<Type>Sender</Type>"), body);
    }

    @Test
    void unsupportedOperationReturnsQueryError() {
        Response response = handler.handle("NoSuchAction", new MultivaluedHashMap<>());
        assertEquals(400, response.getStatus());
        assertEquals("application/xml", response.getMediaType().toString());

        String body = (String) response.getEntity();
        assertTrue(body.contains("<Code>UnsupportedOperation</Code>"), body);
        assertTrue(body.contains("<Type>Sender</Type>"), body);
    }
}
