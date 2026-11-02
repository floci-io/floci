package io.github.hectorvent.floci.services.codeartifact;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire contract towards the real public npm registry, exercised against a local {@code
 * HttpServer} fake standing in for it, named {@code fake} in these tests rather than the real
 * {@code public:npmjs} connection name.
 */
class NpmExternalConnectionClientTest {

    private HttpServer server;
    private NpmExternalConnectionClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        Map<String, String> baseUrls = Map.of("fake", "http://127.0.0.1:" + server.getAddress().getPort());
        client = new NpmExternalConnectionClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NORMAL).build(),
                baseUrls);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fetchPackumentReturnsTheRealBytesOnA200ForAnUnscopedPackage() throws IOException {
        byte[] content = "{\"name\":\"widget\"}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/widget", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });

        Optional<NpmExternalConnectionClient.FetchedAsset> result = client.fetchPackument("fake", null, "widget");

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get().content());
    }

    /**
     * Confirmed live against the real registry: a scoped package's single-package resource needs
     * its scope separator percent-encoded as {@code %2F} to reach the package itself rather than a
     * sub-path of it ({@code @scope/name} with a literal slash 404s this exact endpoint there).
     */
    @Test
    void fetchPackumentEscapesTheScopeSeparatorForAScopedPackage() throws IOException {
        byte[] content = "{\"name\":\"@scope/widget\"}".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> rawPath = new AtomicReference<>();
        // Registered at the decoded path: com.sun.net.httpserver.HttpServer matches a context
        // against the exchange's own decoded URI.getPath(), not its raw, still-percent-encoded
        // form, so a literal slash would reach this same context just as easily; the real
        // assertion below is on the raw path this handler actually received, not on which context
        // matched.
        server.createContext("/@scope/widget", exchange -> {
            rawPath.set(exchange.getRequestURI().getRawPath());
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });

        Optional<NpmExternalConnectionClient.FetchedAsset> result = client.fetchPackument("fake", "scope", "widget");

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get().content());
        assertEquals("/@scope%2Fwidget", rawPath.get());
    }

    @Test
    void fetchPackumentReturnsEmptyOnA404() throws IOException {
        server.createContext("/widget", exchange -> respondEmpty(exchange, 404));

        Optional<NpmExternalConnectionClient.FetchedAsset> result = client.fetchPackument("fake", null, "widget");

        assertFalse(result.isPresent());
    }

    @Test
    void fetchPackumentReturnsEmptyForAnUnknownConnectionName() {
        Optional<NpmExternalConnectionClient.FetchedAsset> result =
                client.fetchPackument("public:npmjs", null, "widget");

        assertFalse(result.isPresent());
    }

    @Test
    void fetchTarballReturnsTheRealBytesOnA200ForAnUnscopedPackage() throws IOException {
        byte[] content = "tarball-content".getBytes(StandardCharsets.UTF_8);
        server.createContext("/widget/-/widget-1.0.0.tgz", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });

        Optional<NpmExternalConnectionClient.FetchedAsset> result =
                client.fetchTarball("fake", null, "widget", "1.0.0");

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get().content());
    }

    /**
     * Confirmed live against the real registry: unlike the packument path, the tarball path keeps
     * a literal {@code /} between the scope and the bare name, and the filename itself never
     * carries the scope at all.
     */
    @Test
    void fetchTarballUsesALiteralSlashAndTheBareNameForAScopedPackage() throws IOException {
        byte[] content = "tarball-content".getBytes(StandardCharsets.UTF_8);
        server.createContext("/@scope/widget/-/widget-1.0.0.tgz", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });

        Optional<NpmExternalConnectionClient.FetchedAsset> result =
                client.fetchTarball("fake", "scope", "widget", "1.0.0");

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get().content());
    }

    @Test
    void fetchTarballReturnsEmptyOnA404() throws IOException {
        server.createContext("/widget/-/widget-1.0.0.tgz", exchange -> respondEmpty(exchange, 404));

        Optional<NpmExternalConnectionClient.FetchedAsset> result =
                client.fetchTarball("fake", null, "widget", "1.0.0");

        assertFalse(result.isPresent());
    }

    @Test
    void fetchTarballReturnsEmptyForAnUnknownConnectionName() {
        Optional<NpmExternalConnectionClient.FetchedAsset> result =
                client.fetchTarball("public:npmjs", null, "widget", "1.0.0");

        assertFalse(result.isPresent());
    }

    private static void respondEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
