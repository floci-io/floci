package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link HttpProxyInvoker} forwards method, path, headers, and body
 * to a real backend HTTP server, applies RequestParameters transformations,
 * and propagates the response back as a ProxyResult.
 */
class HttpProxyInvokerTest {

    private HttpServer backend;
    private int backendPort;
    private final AtomicReference<RecordedRequest> received = new AtomicReference<>();

    private record RecordedRequest(String method, String path, String query, Headers headers, byte[] body) {}

    @BeforeEach
    void setUp() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            byte[] reqBody = exchange.getRequestBody().readAllBytes();
            received.set(new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(),
                    exchange.getRequestHeaders(),
                    reqBody));

            byte[] resp = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Backend", "true");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        backend.start();
        backendPort = backend.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        backend.stop(0);
    }

    private Integration httpProxyIntegration(String uri, Map<String, String> requestParameters) {
        Integration i = new Integration();
        i.setIntegrationType("HTTP_PROXY");
        i.setIntegrationUri(uri);
        i.setIntegrationMethod("ANY");
        i.setRequestParameters(requestParameters);
        return i;
    }

    private RequestContext ctxFor(String method, String path, String proxy, Map<String, String> headers,
                                   Map<String, String> query, byte[] body, Map<String, Object> claims) {
        return new RequestContext("api1", "$default", method, path, proxy, "ANY /wallet/{proxy+}",
                "req-test", "127.0.0.1", headers, query,
                proxy == null ? Map.of() : Map.of("proxy", proxy),
                body, claims, Map.of());
    }

    @Test
    void forwardsMethodPathAndBody() {
        Integration integration = httpProxyIntegration(
                "http://127.0.0.1:" + backendPort + "/public/{proxy}",
                null);
        RequestContext ctx = ctxFor("POST", "/wallet/balance", "balance",
                Map.of("Content-Type", "application/json"),
                Map.of(),
                "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                Map.of());

        ProxyResult result = new HttpProxyInvoker().invoke(integration, ctx);

        assertEquals(200, result.statusCode());
        RecordedRequest r = received.get();
        assertNotNull(r, "backend should have received the request");
        assertEquals("POST", r.method());
        assertEquals("/public/balance", r.path());
        assertEquals("{\"a\":1}", new String(r.body(), StandardCharsets.UTF_8));
    }

    @Test
    void appliesRequestParametersHeaderInjection() {
        Integration integration = httpProxyIntegration(
                "http://127.0.0.1:" + backendPort + "/public/{proxy}",
                Map.of("append:header.x-user-id", "$context.authorizer.claims.userId"));
        RequestContext ctx = ctxFor("GET", "/wallet/balance", "balance",
                Map.of(), Map.of(), null,
                Map.of("userId", "u-42"));

        new HttpProxyInvoker().invoke(integration, ctx);

        assertEquals("u-42", received.get().headers().getFirst("X-User-Id"));
    }

    @Test
    void overwritePathReplacesBackendPath() {
        Integration integration = httpProxyIntegration(
                "http://127.0.0.1:" + backendPort + "/wrong",
                Map.of("overwrite:path", "/public/$request.path.proxy"));
        RequestContext ctx = ctxFor("GET", "/wallet/balance", "balance",
                Map.of(), Map.of(), null, Map.of());

        new HttpProxyInvoker().invoke(integration, ctx);

        assertEquals("/public/balance", received.get().path());
    }

    @Test
    void hopByHopHeadersAreNotForwarded() {
        Integration integration = httpProxyIntegration(
                "http://127.0.0.1:" + backendPort + "/foo",
                null);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Connection", "close");
        headers.put("Transfer-Encoding", "chunked");
        headers.put("X-Custom", "kept");
        RequestContext ctx = ctxFor("GET", "/wallet/foo", "foo",
                headers, Map.of(), null, Map.of());

        new HttpProxyInvoker().invoke(integration, ctx);

        Headers received = this.received.get().headers();
        // We sent "Connection: close" inbound. The JDK HttpClient may set its own
        // Connection header (e.g. "Upgrade, HTTP2-Settings" for HTTP/2 negotiation),
        // but our inbound "close" must NOT pass through.
        String conn = received.getFirst("Connection");
        if (conn != null) {
            assertFalse(conn.toLowerCase().contains("close"),
                    "inbound Connection: close must not be forwarded; got: " + conn);
        }
        // Also, Transfer-Encoding from inbound must not be forwarded
        assertNull(received.getFirst("Transfer-encoding"),
                "Transfer-Encoding should be stripped (hop-by-hop)");
        assertEquals("kept", received.getFirst("X-Custom"));
    }

    @Test
    void responseStatusAndBodyPropagated() {
        backend.removeContext("/");
        backend.createContext("/", exchange -> {
            byte[] resp = "{\"err\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });

        Integration integration = httpProxyIntegration(
                "http://127.0.0.1:" + backendPort + "/foo", null);
        RequestContext ctx = ctxFor("GET", "/wallet/foo", "foo",
                Map.of(), Map.of(), null, Map.of());

        ProxyResult result = new HttpProxyInvoker().invoke(integration, ctx);
        assertEquals(404, result.statusCode());
        assertEquals("{\"err\":\"not found\"}", new String(result.body(), StandardCharsets.UTF_8));
    }

    @Test
    void backendUnreachableReturns502() {
        // Stop the backend to simulate connection refused
        backend.stop(0);

        Integration integration = httpProxyIntegration(
                "http://127.0.0.1:" + backendPort + "/foo", null);
        RequestContext ctx = ctxFor("GET", "/wallet/foo", "foo",
                Map.of(), Map.of(), null, Map.of());

        ProxyResult result = new HttpProxyInvoker().invoke(integration, ctx);
        assertEquals(502, result.statusCode());
        assertTrue(new String(result.body(), StandardCharsets.UTF_8).contains("Bad Gateway"));

        // Restart backend so @AfterEach doesn't NPE
        try { backend.start(); } catch (IllegalStateException ignored) {}
    }

    @Test
    void queryParamsForwardedAndAppendable() {
        Integration integration = httpProxyIntegration(
                "http://127.0.0.1:" + backendPort + "/foo",
                Map.of("append:querystring.user", "$context.authorizer.claims.userId"));
        RequestContext ctx = ctxFor("GET", "/wallet/foo", "foo",
                Map.of(),
                Map.of("page", "2"),
                null,
                Map.of("userId", "u-42"));

        new HttpProxyInvoker().invoke(integration, ctx);

        String q = received.get().query();
        assertNotNull(q);
        assertTrue(q.contains("page=2"));
        assertTrue(q.contains("user=u-42"));
    }
}
