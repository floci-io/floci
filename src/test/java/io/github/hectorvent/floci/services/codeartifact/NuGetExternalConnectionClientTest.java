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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire contract towards the real public NuGet repository, exercised against a local {@code
 * HttpServer} fake standing in for it, named {@code fake} in these tests rather than the real
 * {@code public:nuget-org} connection name.
 */
class NuGetExternalConnectionClientTest {

    private HttpServer server;
    private NuGetExternalConnectionClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        Map<String, String> baseUrls = Map.of("fake", "http://127.0.0.1:" + server.getAddress().getPort());
        client = new NuGetExternalConnectionClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NORMAL).build(),
                baseUrls);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fetchPackageReturnsTheRealBytesOnA200() throws IOException {
        byte[] content = "nupkg-content".getBytes(StandardCharsets.UTF_8);
        server.createContext("/widget/1.0.0/widget.1.0.0.nupkg", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });

        Optional<NuGetExternalConnectionClient.FetchedAsset> result = client.fetchPackage("fake", "Widget", "1.0.0");

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get().content());
    }

    @Test
    void fetchPackageLowercasesTheIdAndVersionInThePath() throws IOException {
        byte[] content = "nupkg-content".getBytes(StandardCharsets.UTF_8);
        server.createContext("/widget/1.0.0-beta/widget.1.0.0-beta.nupkg", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });

        Optional<NuGetExternalConnectionClient.FetchedAsset> result =
                client.fetchPackage("fake", "WIDGET", "1.0.0-BETA");

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get().content());
    }

    /**
     * Raised by Greptile's review: the real {@code api.nuget.org/v3-flatcontainer} only ever
     * stores one canonical path per version and 404s on an equivalent-but-not-canonical spelling
     * (confirmed directly against it: {@code 13.00.3} 404s where {@code 13.0.3} 200s), the same
     * normalization {@code NuGetVersions#normalize} already applies for matching BaGetter's own
     * listing. Requesting {@code 1.00.0} must still build the canonical {@code 1.0.0} path, not
     * the merely-lowercased {@code 1.00.0} the old code sent.
     */
    @Test
    void fetchPackageNormalizesAnEquivalentVersionSpellingToTheCanonicalPath() throws IOException {
        byte[] content = "nupkg-content".getBytes(StandardCharsets.UTF_8);
        server.createContext("/widget/1.0.0/widget.1.0.0.nupkg", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });

        Optional<NuGetExternalConnectionClient.FetchedAsset> result =
                client.fetchPackage("fake", "widget", "1.00.0");

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get().content());
    }

    @Test
    void fetchPackageReturnsEmptyOnA404() throws IOException {
        server.createContext("/widget/1.0.0/widget.1.0.0.nupkg", exchange -> respondEmpty(exchange, 404));

        Optional<NuGetExternalConnectionClient.FetchedAsset> result = client.fetchPackage("fake", "widget", "1.0.0");

        assertFalse(result.isPresent());
    }

    @Test
    void fetchPackageReturnsEmptyForAnUnknownConnectionName() {
        Optional<NuGetExternalConnectionClient.FetchedAsset> result =
                client.fetchPackage("public:nuget-org", "widget", "1.0.0");

        assertFalse(result.isPresent());
    }

    @Test
    void existsReturnsTrueOnA200HeadResponse() throws IOException {
        server.createContext("/widget/1.0.0/widget.1.0.0.nupkg", exchange -> respondEmpty(exchange, 200));

        assertTrue(client.exists("fake", "widget", "1.0.0"));
    }

    @Test
    void existsReturnsFalseOnA404HeadResponse() throws IOException {
        server.createContext("/widget/1.0.0/widget.1.0.0.nupkg", exchange -> respondEmpty(exchange, 404));

        assertFalse(client.exists("fake", "widget", "1.0.0"));
    }

    @Test
    void fetchPackageFollowsARealRedirectInsteadOfTreatingItAsMissing() throws IOException {
        byte[] content = "redirected-content".getBytes(StandardCharsets.UTF_8);
        server.createContext("/widget/1.0.0/widget.1.0.0.nupkg", exchange -> {
            exchange.getResponseHeaders().add("Location", "/moved/widget.1.0.0.nupkg");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.createContext("/moved/widget.1.0.0.nupkg", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });

        Optional<NuGetExternalConnectionClient.FetchedAsset> result = client.fetchPackage("fake", "widget", "1.0.0");

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get().content());
    }

    private static void respondEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
