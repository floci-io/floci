package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LambdaWebMetadataServerTest {

    private static final String PATH = "/2026-01-15/metadata/execution-environment";
    private static final String ZONE = "{\"AvailabilityZoneID\":\"use1-az1\"}\n";
    private static final String UNAUTHORIZED = "\"Client.Unauthorized\"\n";
    private static final String NOT_FOUND = "404 page not found\n";
    private static final String HTTP_DATE = "[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT";

    private final Vertx vertx = Vertx.vertx();
    private LambdaWebMetadataServer server;
    private int port;
    private String token;

    @BeforeEach
    void start() {
        ContainerReachableEndpoint endpoint = mock(ContainerReachableEndpoint.class);
        when(endpoint.baseUrl()).thenReturn("http://floci.test:4566");
        server = new LambdaWebMetadataServer(vertx, endpoint);
        token = server.register("us-east-1");
        String address = server.address();
        assertTrue(address.matches("floci\\.test:\\d+"), address);
        port = Integer.parseInt(address.substring(address.indexOf(':') + 1));
    }

    @AfterEach
    void stop() {
        server.stop();
        vertx.close();
    }

    @Test
    void theEnvironmentTokenGetsTheZoneWithAnyMethod() throws IOException {
        Answer ok = send(request("GET", PATH, "Bearer " + token));
        assertEquals("HTTP/1.1 200 OK", ok.status);
        assertEquals("application/json", ok.headers.get("Content-Type"));
        assertEquals("34", ok.headers.get("Content-Length"));
        assertTrue(ok.headers.get("Date").matches(HTTP_DATE), ok.headers.get("Date"));
        assertFalse(ok.headers.containsKey("Cache-Control"));
        assertEquals(ZONE, ok.body);

        for (String method : new String[] {"POST", "PUT", "DELETE", "PATCH", "OPTIONS", "FOO"}) {
            assertEquals(ZONE, send(request(method, PATH, "Bearer " + token)).body, method);
        }
        assertEquals(ZONE, send(request("GET", PATH + "?a=1", "Bearer " + token + " ")).body);
        assertEquals(ZONE, send(request("GET", "/2026-01-15/metadata/execution%2Denvironment", "Bearer " + token)).body);
        assertEquals(ZONE, send(request("GET", "http://example.com" + PATH, "Bearer " + token)).body);
        assertEquals(ZONE, send("GET " + PATH + " HTTP/1.0\r\nAuthorization: Bearer " + token + "\r\n\r\n").body);

        Answer head = send(request("HEAD", PATH, "Bearer " + token));
        assertEquals("HTTP/1.1 200 OK", head.status);
        assertEquals("34", head.headers.get("Content-Length"));
        assertEquals("", head.body);
    }

    @Test
    void anyOtherAuthorizationIsUnauthorized() throws IOException {
        for (String authorization : new String[] {null, "Bearer x" + token.substring(1), "bearer " + token,
                "Bearer  " + token, "Bearer\t" + token, token, "Bearer ", "Bearer " + token.toUpperCase()}) {
            Answer refused = send(request("GET", PATH, authorization));
            assertEquals("HTTP/1.1 401 Unauthorized", refused.status, authorization);
            assertEquals("application/json", refused.headers.get("Content-Type"));
            assertEquals("22", refused.headers.get("Content-Length"));
            assertEquals(UNAUTHORIZED, refused.body);
        }
        server.forget(token);
        assertEquals(UNAUTHORIZED, send(request("GET", PATH, "Bearer " + token)).body);
    }

    @Test
    void anotherPathIsNotFoundBeforeTheTokenIsChecked() throws IOException {
        for (String path : new String[] {"/", PATH + "/", PATH.replace("metadata", "Metadata"),
                "/2026-01-15/metadata%2Fexecution-environment", PATH + "#x", "/2018-06-01/runtime/invocation/next"}) {
            Answer missing = send(request("GET", path, null));
            assertEquals("HTTP/1.1 404 Not Found", missing.status, path);
            assertEquals("text/plain; charset=utf-8", missing.headers.get("Content-Type"));
            assertEquals("nosniff", missing.headers.get("X-Content-Type-Options"));
            assertEquals(NOT_FOUND, missing.body);
        }
        Answer head = send(request("HEAD", "/", null));
        assertEquals("19", head.headers.get("Content-Length"));
        assertEquals("", head.body);
    }

    @Test
    void anUncleanPathIsRedirectedToItsCleanForm() throws IOException {
        Answer get = send(request("GET", "/2026-01-15//metadata/execution-environment?a=1&b=%20", null));
        assertEquals("HTTP/1.1 307 Temporary Redirect", get.status);
        assertEquals("text/html; charset=utf-8", get.headers.get("Content-Type"));
        assertEquals(PATH + "?a=1&b=%20", get.headers.get("Location"));
        assertEquals("<a href=\"" + PATH + "?a=1&amp;b=%20\">Temporary Redirect</a>.\n\n", get.body);
        assertEquals("92", get.headers.get("Content-Length"));
        assertEquals(PATH, send(request("GET", "/2026-01-15/metadata/./execution-environment", null))
                .headers.get("Location"));
        assertEquals(PATH, send(request("GET", PATH + "/.", null)).headers.get("Location"));
        assertEquals("/x", send(request("GET", "//x", null)).headers.get("Location"));

        Answer post = send(request("POST", "//x", null));
        assertEquals("HTTP/1.1 307 Temporary Redirect", post.status);
        assertFalse(post.headers.containsKey("Content-Type"));
        assertEquals("0", post.headers.get("Content-Length"));

        Answer head = send(request("HEAD", "//x", null));
        assertEquals("text/html; charset=utf-8", head.headers.get("Content-Type"));
        assertFalse(head.headers.containsKey("Content-Length"));
    }

    @Test
    void anHttp11RequestWithoutHostIsBadRequest() throws IOException {
        Answer refused = send("GET " + PATH + " HTTP/1.1\r\nAuthorization: Bearer " + token + "\r\n\r\n");
        assertEquals("HTTP/1.1 400 Bad Request: missing required Host header", refused.status);
        assertEquals("text/plain; charset=utf-8", refused.headers.get("Content-Type"));
        assertFalse(refused.headers.containsKey("Date"));
        assertEquals("400 Bad Request: missing required Host header", refused.body);
    }

    private static String request(String method, String target, String authorization) {
        return method + " " + target + " HTTP/1.1\r\nHost: 169.254.100.1:9001\r\n"
                + (authorization == null ? "" : "Authorization: " + authorization + "\r\n")
                + "Connection: close\r\n\r\n";
    }

    private Answer send(String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            InputStream in = socket.getInputStream();
            String raw = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            int end = raw.indexOf("\r\n\r\n");
            String[] lines = raw.substring(0, end).split("\r\n");
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (int i = 1; i < lines.length; i++) {
                String[] header = lines[i].split(": ", 2);
                headers.put(header[0], header[1]);
            }
            return new Answer(lines[0], headers, raw.substring(end + 4));
        }
    }

    private record Answer(String status, Map<String, String> headers, String body) {
    }
}
