package io.github.hectorvent.floci.services.apigatewayv2.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.apigateway.AwsServiceRouter;
import io.github.hectorvent.floci.services.apigateway.VtlTemplateEngine;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.apigatewayv2.websocket.WebSocketIntegrationInvoker.IntegrationResult;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebSocketIntegrationInvokerHttpTargetTest {

    private WebSocketIntegrationInvoker invoker;
    private HttpServer backend;
    private final AtomicReference<String> receivedHost = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        invoker = new WebSocketIntegrationInvoker(
                mock(LambdaService.class),
                mock(AwsServiceRouter.class),
                new ObjectMapper(),
                mock(VtlTemplateEngine.class));
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            receivedHost.set(exchange.getRequestHeaders().getFirst("Host"));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        backend.start();
    }

    @AfterEach
    void tearDown() {
        backend.stop(0);
    }

    private IntegrationResult invoke(String type, String uri) {
        Integration integration = new Integration();
        integration.setIntegrationType(type);
        integration.setIntegrationUri(uri);
        return invoker.invoke("us-east-1", integration, "{}", Map.of(), Map.of(), Map.of());
    }

    @Test
    void httpProxyRejectsMetadataTarget() {
        IntegrationResult result = invoke("HTTP_PROXY", "http://169.254.169.254/latest/meta-data/");

        assertEquals(500, result.statusCode());
        assertNotNull(result.functionError());
        assertTrue(result.functionError().contains("link-local or metadata address"), result.functionError());
    }

    @Test
    void httpRejectsMetadataTarget() {
        IntegrationResult result = invoke("HTTP", "http://169.254.169.254/latest/meta-data/");

        assertEquals(500, result.statusCode());
        assertNotNull(result.functionError());
        assertTrue(result.functionError().contains("link-local or metadata address"), result.functionError());
    }

    @Test
    void httpProxyStillReachesLoopbackBackend() {
        IntegrationResult result = invoke("HTTP_PROXY", "http://127.0.0.1:" + backend.getAddress().getPort() + "/");

        assertEquals(200, result.statusCode());
        assertEquals("ok", result.body());
    }

    @Test
    void httpProxyConnectsToTheAddressItCheckedInsteadOfResolvingTheNameAgain() throws Exception {
        assertConnectsToTheAddressItCheckedInsteadOfResolvingTheNameAgain("HTTP_PROXY");
    }

    @Test
    void httpConnectsToTheAddressItCheckedInsteadOfResolvingTheNameAgain() throws Exception {
        assertConnectsToTheAddressItCheckedInsteadOfResolvingTheNameAgain("HTTP");
    }

    private void assertConnectsToTheAddressItCheckedInsteadOfResolvingTheNameAgain(String integrationType)
            throws Exception {
        receivedHost.set(null);
        AtomicInteger lookups = new AtomicInteger();
        WebSocketIntegrationInvoker pinnedInvoker = new WebSocketIntegrationInvoker(
                mock(LambdaService.class),
                mock(AwsServiceRouter.class),
                new ObjectMapper(),
                mock(VtlTemplateEngine.class),
                host -> {
                    if (lookups.incrementAndGet() == 1) {
                        return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
                    }
                    return new InetAddress[] {InetAddress.getByName("169.254.169.254")};
                });
        String uri = "http://rebind.example.test:" + backend.getAddress().getPort() + "/";
        Integration integration = new Integration();
        integration.setIntegrationType(integrationType);
        integration.setIntegrationUri(uri);

        IntegrationResult result = pinnedInvoker.invoke("us-east-1", integration, "{}", Map.of(), Map.of(), Map.of());

        assertEquals(200, result.statusCode());
        assertEquals("ok", result.body());
        assertEquals(1, lookups.get(), "the name must be resolved once, then the checked address used");
        assertEquals("rebind.example.test:" + backend.getAddress().getPort(), receivedHost.get());
    }
}
