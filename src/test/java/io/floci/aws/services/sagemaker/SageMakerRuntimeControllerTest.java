package io.floci.aws.services.sagemaker;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.EndpointResource;
import io.floci.aws.testing.ParallelLocks;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Takes a free port and releases it before dialing it, so it holds the ports lock exclusively.
@ResourceLock(ParallelLocks.PORTS)
class SageMakerRuntimeControllerTest {

    /**
     * #5664: an endpoint whose container is gone refuses the connection, with an exception that carries no message.
     * The invocation answers ModelError rather than failing on the missing message.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anEndpointWhoseContainerRefusesTheConnectionAnswersModelError() throws IOException {
        SageMakerService service = mock(SageMakerService.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.resolveRegion(any())).thenReturn("us-east-1");
        EndpointResource endpoint = new EndpointResource();
        endpoint.endpointName = "gone";
        endpoint.endpointStatus = "InService";
        endpoint.invokeHost = "127.0.0.1";
        endpoint.invokePort = closedPort();
        when(service.endpoint("us-east-1", "gone")).thenReturn(Optional.of(endpoint));

        Response response = new SageMakerRuntimeController(service, regionResolver).invoke("gone", "text/plain", null,
                mock(HttpHeaders.class), "hello".getBytes(StandardCharsets.UTF_8));

        assertEquals(424, response.getStatus());
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals("ModelError", body.get("__type"));
        assertNotNull(body.get("message"));
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
