package io.github.hectorvent.floci.services.sagemaker;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.EndpointResource;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Drives InvokeEndpoint against a local HTTP server standing in for the model container. */
class SageMakerRuntimeControllerTest {
    private final SageMakerService service = mock(SageMakerService.class);
    private HttpServer container;
    private SageMakerRuntimeController controller;

    @BeforeEach
    void setUp() throws IOException {
        container = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        container.start();
        EndpointResource endpoint = new EndpointResource();
        endpoint.endpointName = "ep";
        endpoint.endpointStatus = "InService";
        endpoint.invokeHost = "127.0.0.1";
        endpoint.invokePort = container.getAddress().getPort();
        when(service.endpoint("us-east-1", "ep")).thenReturn(Optional.of(endpoint));
        controller = new SageMakerRuntimeController(service, new RegionResolver("us-east-1", "000000000000"));
    }

    @AfterEach
    void stopContainer() {
        container.stop(0);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 422})
    void containerClientErrorIsReturnedAsModelError(int status) {
        containerResponds(status, "bad input");

        Response response = invoke();

        assertModelError(response, status, "bad input");
    }

    @Test
    void containerServerErrorIsReturnedAsModelError() {
        containerResponds(500, "boom");

        Response response = invoke();

        assertModelError(response, 500, "boom");
    }

    @Test
    void containerSuccessIsPassedThrough() {
        containerResponds(200, "prediction");

        Response response = invoke();

        assertEquals(200, response.getStatus());
        assertArrayEquals("prediction".getBytes(StandardCharsets.UTF_8), (byte[]) response.getEntity());
    }

    private Response invoke() {
        return controller.invoke("ep", "text/plain", null, null, "input".getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static void assertModelError(Response response, int originalStatus, String originalMessage) {
        assertEquals(424, response.getStatus());
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals("ModelError", body.get("__type"));
        assertEquals(originalStatus, body.get("OriginalStatusCode"));
        assertEquals(originalMessage, body.get("OriginalMessage"));
    }

    private void containerResponds(int status, String body) {
        container.createContext("/invocations", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }
}
