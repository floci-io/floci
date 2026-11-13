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
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire contract towards a public Maven repository, exercised against a local {@code
 * HttpServer} fake standing in for one, named {@code fake} in these tests rather than any of the
 * 5 real {@code public:maven-*} connection names.
 */
class MavenExternalConnectionClientTest {

    private HttpServer server;
    private MavenExternalConnectionClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        Map<String, String> baseUrls = Map.of("fake", "http://127.0.0.1:" + server.getAddress().getPort());
        client = new MavenExternalConnectionClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NORMAL).build(),
                baseUrls);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fetchAssetReturnsTheRealBytesOnA200() throws IOException {
        byte[] content = "pom-content".getBytes(StandardCharsets.UTF_8);
        server.createContext("/com/example/widget/1.0/widget-1.0.pom", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });

        Optional<MavenExternalConnectionClient.FetchedAsset> result =
                client.fetchAsset("fake", "com/example/widget/1.0/widget-1.0.pom");

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get().content());
    }

    /**
     * {@code fetchAsset(..., Path)} is the streamed sibling a large Maven release import actually
     * uses: unlike the {@code byte[]} overload above, it writes straight to a file via an {@link
     * AssetDigestingInputStream}-wrapped copy, so this confirms that wrapping still lands the real
     * bytes on disk and reports their real size, not just that the plain {@code byte[]} path works.
     */
    @Test
    void fetchAssetToAPathWritesTheRealBytesAndReportsTheirSize() throws IOException {
        byte[] content = "pom-content".getBytes(StandardCharsets.UTF_8);
        server.createContext("/com/example/widget/1.0/widget-1.0.pom", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        Path destination = Files.createTempFile("maven-external-connection-test-", ".pom");

        try {
            Optional<MavenExternalConnectionClient.FetchedFile> result =
                    client.fetchAsset("fake", "com/example/widget/1.0/widget-1.0.pom", destination);

            assertTrue(result.isPresent());
            assertEquals(content.length, result.get().size());
            assertArrayEquals(content, Files.readAllBytes(destination));
        } finally {
            Files.deleteIfExists(destination);
        }
    }

    @Test
    void fetchAssetReturnsEmptyOnA404() throws IOException {
        server.createContext("/com/example/widget/1.0/widget-1.0.pom",
                exchange -> respondEmpty(exchange, 404));

        Optional<MavenExternalConnectionClient.FetchedAsset> result =
                client.fetchAsset("fake", "com/example/widget/1.0/widget-1.0.pom");

        assertFalse(result.isPresent());
    }

    @Test
    void fetchAssetReturnsEmptyForAnUnknownConnectionName() {
        Optional<MavenExternalConnectionClient.FetchedAsset> result =
                client.fetchAsset("public:maven-central", "com/example/widget/1.0/widget-1.0.pom");

        assertFalse(result.isPresent());
    }

    @Test
    void existsReturnsTrueOnA200HeadResponse() throws IOException {
        server.createContext("/com/example/widget/1.0/widget-1.0.jar",
                exchange -> respondEmpty(exchange, 200));

        assertTrue(client.exists("fake", "com/example/widget/1.0/widget-1.0.jar"));
    }

    @Test
    void existsReturnsFalseOnA404HeadResponse() throws IOException {
        server.createContext("/com/example/widget/1.0/widget-1.0.jar",
                exchange -> respondEmpty(exchange, 404));

        assertFalse(client.exists("fake", "com/example/widget/1.0/widget-1.0.jar"));
    }

    @Test
    void fetchAssetEncodesAFragmentCharacterInTheFilenameInsteadOfTruncatingThePath() throws IOException {
        byte[] content = "jar-content".getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getRawPath().contains("%23")) {
                exchange.sendResponseHeaders(200, content.length);
                exchange.getResponseBody().write(content);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });

        Optional<MavenExternalConnectionClient.FetchedAsset> result =
                client.fetchAsset("fake", "com/example/widget/1.0/widget-1.0#build.jar");

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get().content());
    }

    @Test
    void fetchAssetFollowsARealRedirectInsteadOfTreatingItAsMissing() throws IOException {
        byte[] content = "redirected-content".getBytes(StandardCharsets.UTF_8);
        server.createContext("/com/example/widget/1.0/widget-1.0.jar", exchange -> {
            exchange.getResponseHeaders().add("Location", "/moved/widget-1.0.jar");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.createContext("/moved/widget-1.0.jar", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });

        Optional<MavenExternalConnectionClient.FetchedAsset> result =
                client.fetchAsset("fake", "com/example/widget/1.0/widget-1.0.jar");

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get().content());
    }

    @Test
    void existsFollowsARealRedirectInsteadOfTreatingItAsMissing() throws IOException {
        server.createContext("/com/example/widget/1.0/widget-1.0.jar", exchange -> {
            exchange.getResponseHeaders().add("Location", "/moved/widget-1.0.jar");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.createContext("/moved/widget-1.0.jar", exchange -> respondEmpty(exchange, 200));

        assertTrue(client.exists("fake", "com/example/widget/1.0/widget-1.0.jar"));
    }

    private static void respondEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
