package io.floci.aws.services.codeartifact;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire contract towards the real public {@code crates.io} registry, exercised against a
 * local {@code HttpServer} fake standing in for it, named {@code fake} in these tests rather than
 * the real {@code public:crates-io} connection name, mirroring {@link
 * NuGetExternalConnectionClientTest}'s own shape.
 */
class CargoExternalConnectionClientTest {

    private HttpServer server;
    private CargoExternalConnectionClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new CargoExternalConnectionClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NORMAL).build(),
                Map.of("fake", baseUrl), Map.of("fake", baseUrl));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fetchSparseIndexReturnsTheRealBytesOnA200() throws IOException {
        byte[] content = "{\"name\":\"widget\",\"vers\":\"1.0.0\"}\n".getBytes(StandardCharsets.UTF_8);
        server.createContext("/wi/dg/widget", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });

        Optional<byte[]> result = client.fetchSparseIndex("fake", "widget");

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get());
    }

    /** Confirms the real RFC 2789 bucketing rule for each name-length case, not just the 4+
     * character case the one example above covers. */
    @Test
    void fetchSparseIndexUsesTheRealBucketingPathForEveryNameLength() throws IOException {
        server.createContext("/1/a", exchange -> respond(exchange, 200, "one"));
        server.createContext("/2/ab", exchange -> respond(exchange, 200, "two"));
        server.createContext("/3/a/abc", exchange -> respond(exchange, 200, "three"));
        server.createContext("/wi/dg/widget", exchange -> respond(exchange, 200, "four-plus"));

        assertEquals("one", new String(client.fetchSparseIndex("fake", "a").orElseThrow(), StandardCharsets.UTF_8));
        assertEquals("two", new String(client.fetchSparseIndex("fake", "ab").orElseThrow(), StandardCharsets.UTF_8));
        assertEquals("three",
                new String(client.fetchSparseIndex("fake", "abc").orElseThrow(), StandardCharsets.UTF_8));
        assertEquals("four-plus",
                new String(client.fetchSparseIndex("fake", "widget").orElseThrow(), StandardCharsets.UTF_8));
    }

    @Test
    void fetchSparseIndexReturnsEmptyOnA404() throws IOException {
        server.createContext("/wi/dg/widget", exchange -> respondEmpty(exchange, 404));

        assertFalse(client.fetchSparseIndex("fake", "widget").isPresent());
    }

    @Test
    void fetchSparseIndexReturnsEmptyForAnUnknownConnectionName() {
        assertFalse(client.fetchSparseIndex("public:crates-io", "widget").isPresent());
    }

    @Test
    void fetchCrateWritesTheRealBytesAndReportsTheirRealSizeAndSha256() throws IOException, NoSuchAlgorithmException {
        byte[] content = "crate-bytes".getBytes(StandardCharsets.UTF_8);
        server.createContext("/widget/1.0.0/download", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        Path destination = Files.createTempFile("cargo-external-connection-test-", ".crate");

        try {
            Optional<CargoExternalConnectionClient.FetchedFile> result =
                    client.fetchCrate("fake", "widget", "1.0.0", destination);

            assertTrue(result.isPresent());
            assertEquals(content.length, result.get().size());
            assertArrayEquals(content, Files.readAllBytes(destination));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String expectedSha256 = HexFormat.of().formatHex(digest.digest(content));
            assertEquals(expectedSha256, result.get().sha256());
        } finally {
            Files.deleteIfExists(destination);
        }
    }

    @Test
    void fetchCrateReturnsEmptyOnA404() throws IOException {
        server.createContext("/widget/1.0.0/download", exchange -> respondEmpty(exchange, 404));
        Path destination = Files.createTempFile("cargo-external-connection-test-", ".crate");

        try {
            assertFalse(client.fetchCrate("fake", "widget", "1.0.0", destination).isPresent());
        } finally {
            Files.deleteIfExists(destination);
        }
    }

    @Test
    void fetchCrateReturnsEmptyForAnUnknownConnectionName() throws IOException {
        Path destination = Files.createTempFile("cargo-external-connection-test-", ".crate");

        try {
            assertFalse(client.fetchCrate("public:crates-io", "widget", "1.0.0", destination).isPresent());
        } finally {
            Files.deleteIfExists(destination);
        }
    }

    @Test
    void fetchCrateFollowsARealRedirectInsteadOfTreatingItAsMissing() throws IOException {
        byte[] content = "redirected-crate-bytes".getBytes(StandardCharsets.UTF_8);
        server.createContext("/widget/1.0.0/download", exchange -> {
            exchange.getResponseHeaders().add("Location", "/moved/widget-1.0.0.crate");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.createContext("/moved/widget-1.0.0.crate", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        Path destination = Files.createTempFile("cargo-external-connection-test-", ".crate");

        try {
            Optional<CargoExternalConnectionClient.FetchedFile> result =
                    client.fetchCrate("fake", "widget", "1.0.0", destination);

            assertTrue(result.isPresent());
            assertArrayEquals(content, Files.readAllBytes(destination));
        } finally {
            Files.deleteIfExists(destination);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void respondEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
