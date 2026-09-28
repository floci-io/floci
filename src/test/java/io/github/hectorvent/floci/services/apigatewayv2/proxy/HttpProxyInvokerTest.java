package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
    void appliesRequestParametersHostHeaderOverride() {
        Integration integration = httpProxyIntegration(
                "http://127.0.0.1:" + backendPort + "/public/{proxy}",
                Map.of("overwrite:header.Host", "lb.localhost.test"));
        RequestContext ctx = ctxFor("GET", "/wallet/balance", "balance",
                Map.of("Host", "client.example.test"), Map.of(), null, Map.of());

        new HttpProxyInvoker().invoke(integration, ctx);

        assertEquals("lb.localhost.test", received.get().headers().getFirst("Host"));
    }

    @Test
    void hostHeaderOverrideDecodesChunkedResponse() {
        backend.removeContext("/");
        backend.createContext("/", exchange -> {
            byte[] resp = "chunked-body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        Integration integration = httpProxyIntegration(
                "http://127.0.0.1:" + backendPort + "/public/{proxy}",
                Map.of("overwrite:header.Host", "lb.localhost.test"));
        RequestContext ctx = ctxFor("GET", "/wallet/balance", "balance",
                Map.of("Host", "client.example.test"), Map.of(), null, Map.of());

        ProxyResult result = new HttpProxyInvoker().invoke(integration, ctx);

        assertEquals(200, result.statusCode());
        assertEquals("chunked-body", new String(result.body(), StandardCharsets.UTF_8));
        assertFalse(result.headers().containsKey("Transfer-encoding"));
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

    @Test
    void rejectsLinkLocalMetadataTarget() {
        Integration integration = httpProxyIntegration("http://169.254.169.254/latest/meta-data/", null);
        RequestContext ctx = ctxFor("GET", "/wallet/x", "x", Map.of(), Map.of(), null, Map.of());

        ProxyResult result = new HttpProxyInvoker().invoke(integration, ctx);

        assertEquals(502, result.statusCode());
        assertTrue(new String(result.body(), StandardCharsets.UTF_8).contains("link-local or metadata address"),
                "the target must be rejected before any connection is attempted");
    }

    @Test
    void rejectsLinkLocalMetadataTargetWhenHostHeaderIsOverridden() {
        Integration integration = httpProxyIntegration("http://169.254.169.254/latest/meta-data/",
                Map.of("overwrite:header.Host", "lb.localhost.test"));
        RequestContext ctx = ctxFor("GET", "/wallet/x", "x", Map.of(), Map.of(), null, Map.of());

        ProxyResult result = new HttpProxyInvoker().invoke(integration, ctx);

        assertEquals(502, result.statusCode());
        assertTrue(new String(result.body(), StandardCharsets.UTF_8).contains("link-local or metadata address"),
                "the target must be rejected before any connection is attempted");
    }

    @Test
    void rejectsAwsIpv6MetadataTarget() {
        Integration integration = httpProxyIntegration("http://[fd00:ec2::254]/latest/meta-data/", null);
        RequestContext ctx = ctxFor("GET", "/wallet/x", "x", Map.of(), Map.of(), null, Map.of());

        ProxyResult result = new HttpProxyInvoker().invoke(integration, ctx);

        assertEquals(502, result.statusCode());
        assertTrue(new String(result.body(), StandardCharsets.UTF_8).contains("link-local or metadata address"),
                "the target must be rejected before any connection is attempted");
    }

    @Test
    void stillReachesLoopbackBackend() {
        Integration integration = httpProxyIntegration("http://127.0.0.1:" + backendPort + "/ok", null);
        RequestContext ctx = ctxFor("GET", "/wallet/x", "x", Map.of(), Map.of(), null, Map.of());

        ProxyResult result = new HttpProxyInvoker().invoke(integration, ctx);

        assertEquals(200, result.statusCode());
        assertNotNull(received.get(), "loopback backends must stay reachable");
    }

    @Test
    void connectsToTheAddressItCheckedInsteadOfResolvingTheNameAgain() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        HttpProxyInvoker invoker = new HttpProxyInvoker(host -> {
            if (lookups.incrementAndGet() == 1) {
                return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
            }
            return new InetAddress[] {InetAddress.getByName("169.254.169.254")};
        });
        Integration integration = httpProxyIntegration("http://rebind.example.test:" + backendPort + "/ok", null);
        RequestContext ctx = ctxFor("GET", "/wallet/x", "x", Map.of(), Map.of(), null, Map.of());

        ProxyResult result = invoker.invoke(integration, ctx);

        assertEquals(200, result.statusCode());
        assertEquals(1, lookups.get(), "the name must be resolved once, then the checked address used");
        assertEquals("rebind.example.test:" + backendPort, received.get().headers().getFirst("Host"));
    }

    @Test
    void rejectsANameThatResolvesToAMetadataAddress() {
        HttpProxyInvoker invoker = new HttpProxyInvoker(
                host -> new InetAddress[] {InetAddress.getByAddress(new byte[] {(byte) 169, (byte) 254, (byte) 169, (byte) 254})});
        Integration integration = httpProxyIntegration("http://metadata.example.test/latest/", null);
        RequestContext ctx = ctxFor("GET", "/wallet/x", "x", Map.of(), Map.of(), null, Map.of());

        ProxyResult result = invoker.invoke(integration, ctx);

        assertEquals(502, result.statusCode());
        assertTrue(new String(result.body(), StandardCharsets.UTF_8).contains("link-local or metadata address"));
    }

    @Test
    void headRequestToANamedHostDoesNotWaitForABody() {
        HttpProxyInvoker invoker = new HttpProxyInvoker(
                host -> new InetAddress[] {InetAddress.getByAddress(new byte[] {127, 0, 0, 1})});
        Integration integration = httpProxyIntegration("http://named.example.test:" + backendPort + "/ok", null);
        RequestContext ctx = ctxFor("HEAD", "/wallet/x", "x", Map.of(), Map.of(), null, Map.of());

        ProxyResult result = assertTimeoutPreemptively(
                Duration.ofSeconds(5), () -> invoker.invoke(integration, ctx));

        assertEquals(200, result.statusCode());
        assertEquals(0, result.body().length);
    }

    // API Gateway caps integration payloads at 10 MB and answers an oversized backend
    // response with 413 Request Entity Too Large.
    private static final int MAX_PAYLOAD_BYTES = 10 * 1024 * 1024;

    private void serveBody(int size, boolean chunked) {
        backend.removeContext("/");
        backend.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, chunked ? 0 : size);
            byte[] block = new byte[64 * 1024];
            try (var out = exchange.getResponseBody()) {
                for (int written = 0; written < size; written += block.length) {
                    out.write(block, 0, Math.min(block.length, size - written));
                }
            } catch (IOException ignored) {
                // the proxy may stop reading once the limit is exceeded
            }
        });
    }

    private ProxyResult invokeLargeResponse(boolean overrideHost) {
        return invokeLargeResponse(backendPort, overrideHost);
    }

    private ProxyResult invokeLargeResponse(int port, boolean overrideHost) {
        Integration integration = httpProxyIntegration(
                "http://127.0.0.1:" + port + "/large",
                overrideHost ? Map.of("overwrite:header.Host", "lb.localhost.test") : null);
        RequestContext ctx = ctxFor("GET", "/wallet/large", "large",
                Map.of(), Map.of(), null, Map.of());
        return new HttpProxyInvoker().invoke(integration, ctx);
    }

    @Test
    void responseOfExactlyTenMegabytesIsReturned() {
        serveBody(MAX_PAYLOAD_BYTES, false);

        ProxyResult result = invokeLargeResponse(false);

        assertEquals(200, result.statusCode());
        assertEquals(MAX_PAYLOAD_BYTES, result.body().length);
    }

    @Test
    void responseOverTenMegabytesIsRejectedAsTooLarge() {
        serveBody(MAX_PAYLOAD_BYTES + 1, false);

        assertEquals(413, invokeLargeResponse(false).statusCode());
    }

    @Test
    void responseOverTenMegabytesIsRejectedWhenHostIsOverridden() {
        serveBody(MAX_PAYLOAD_BYTES + 1, false);

        assertEquals(413, invokeLargeResponse(true).statusCode());
    }

    @Test
    void chunkedResponseOverTenMegabytesIsRejectedWhenHostIsOverridden() {
        serveBody(MAX_PAYLOAD_BYTES + 1, true);

        assertEquals(413, invokeLargeResponse(true).statusCode());
    }

    @Test
    void responseOfExactlyTenMegabytesIsReturnedWhenHostIsOverridden() {
        serveBody(MAX_PAYLOAD_BYTES, false);

        ProxyResult result = invokeLargeResponse(true);

        assertEquals(200, result.statusCode());
        assertEquals(MAX_PAYLOAD_BYTES, result.body().length);
    }

    @Test
    void chunkedResponseOfExactlyTenMegabytesIsReturnedWhenHostIsOverridden() {
        serveBody(MAX_PAYLOAD_BYTES, true);

        ProxyResult result = invokeLargeResponse(true);

        assertEquals(200, result.statusCode());
        assertEquals(MAX_PAYLOAD_BYTES, result.body().length);
    }

    @Test
    void closeDelimitedResponseOfExactlyTenMegabytesIsReturnedWhenHostIsOverridden() throws Exception {
        try (ServerSocket server = serveCloseDelimitedBody(MAX_PAYLOAD_BYTES)) {
            ProxyResult result = invokeLargeResponse(server.getLocalPort(), true);

            assertEquals(200, result.statusCode());
            assertEquals(MAX_PAYLOAD_BYTES, result.body().length);
        }
    }

    @Test
    void closeDelimitedResponseOverTenMegabytesIsRejectedWhenHostIsOverridden() throws Exception {
        try (ServerSocket server = serveCloseDelimitedBody(MAX_PAYLOAD_BYTES + 1)) {
            assertEquals(413, invokeLargeResponse(server.getLocalPort(), true).statusCode());
        }
    }

    /** A backend that sends neither Content-Length nor chunked encoding and ends the body by closing. */
    private static ServerSocket serveCloseDelimitedBody(int size) throws IOException {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                InputStream in = socket.getInputStream();
                int matched = 0;
                byte[] terminator = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
                while (matched < terminator.length) {
                    int b = in.read();
                    if (b < 0) {
                        return;
                    }
                    matched = b == terminator[matched] ? matched + 1 : (b == terminator[0] ? 1 : 0);
                }
                OutputStream out = socket.getOutputStream();
                out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                byte[] block = new byte[64 * 1024];
                for (int written = 0; written < size; written += block.length) {
                    out.write(block, 0, Math.min(block.length, size - written));
                }
            } catch (IOException ignored) {
                // the proxy may stop reading once the limit is exceeded
            }
        });
        thread.setDaemon(true);
        thread.start();
        return server;
    }
}
