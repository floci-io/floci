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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The wire contract towards the real public pypi repository, exercised against a local {@code
 * HttpServer} fake standing in for it, named {@code fake} in these tests rather than the real
 * {@code public:pypi} connection name.
 */
class PypiExternalConnectionClientTest {

    private HttpServer server;
    private String serverBaseUrl;
    private PypiExternalConnectionClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        serverBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        Map<String, String> baseUrls = Map.of("fake", serverBaseUrl);
        client = new PypiExternalConnectionClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NORMAL).build(),
                baseUrls);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fetchSimpleIndexReturnsTheRealBytesOnA200AndSendsThePep691Accept() {
        byte[] content = "{\"files\":[]}".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> seenAccept = new AtomicReference<>();
        server.createContext("/simple/widget/", exchange -> {
            seenAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });

        Optional<PypiExternalConnectionClient.FetchedAsset> result = client.fetchSimpleIndex("fake", "widget");

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get().content());
        assertEquals("application/vnd.pypi.simple.v1+json", seenAccept.get());
    }

    @Test
    void fetchSimpleIndexNormalizesThePackageNameInThePath() {
        byte[] content = "{\"files\":[]}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/simple/widget-name/", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });

        Optional<PypiExternalConnectionClient.FetchedAsset> result = client.fetchSimpleIndex("fake", "Widget_Name");

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get().content());
    }

    @Test
    void fetchSimpleIndexReturnsEmptyOnA404() {
        server.createContext("/simple/widget/", exchange -> respondEmpty(exchange, 404));

        Optional<PypiExternalConnectionClient.FetchedAsset> result = client.fetchSimpleIndex("fake", "widget");

        assertFalse(result.isPresent());
    }

    @Test
    void fetchSimpleIndexReturnsEmptyForAnUnknownConnectionName() {
        Optional<PypiExternalConnectionClient.FetchedAsset> result =
                client.fetchSimpleIndex("public:pypi", "widget");

        assertFalse(result.isPresent());
    }

    @Test
    void fetchFileReturnsTheRealBytesOnA200FromAnAbsoluteUrl() {
        byte[] content = "sdist-content".getBytes(StandardCharsets.UTF_8);
        server.createContext("/packages/aa/bb/widget-1.0.0.tar.gz", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });

        Optional<PypiExternalConnectionClient.FetchedAsset> result = client.fetchFile("fake",
                serverBaseUrl + "/packages/aa/bb/widget-1.0.0.tar.gz");

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get().content());
    }

    /**
     * {@code fetchFile(..., Path)} is the streamed sibling a large sdist/wheel import actually
     * uses: unlike the {@code byte[]} overload above, it writes straight to a file via an {@link
     * AssetDigestingInputStream}-wrapped copy, so this confirms that wrapping still lands the
     * real bytes on disk and reports their real size, not just that the plain {@code byte[]}
     * path works.
     */
    @Test
    void fetchFileToAPathWritesTheRealBytesAndReportsTheirSize() throws IOException {
        byte[] content = "sdist-content".getBytes(StandardCharsets.UTF_8);
        server.createContext("/packages/aa/bb/widget-1.0.0.tar.gz", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        Path destination = Files.createTempFile("pypi-external-connection-test-", ".tar.gz");

        try {
            Optional<PypiExternalConnectionClient.FetchedFile> result = client.fetchFile("fake",
                    serverBaseUrl + "/packages/aa/bb/widget-1.0.0.tar.gz", destination);

            assertTrue(result.isPresent());
            assertEquals(content.length, result.get().size());
            assertArrayEquals(content, Files.readAllBytes(destination));
        } finally {
            Files.deleteIfExists(destination);
        }
    }

    @Test
    void fetchFileReturnsEmptyOnA404() {
        server.createContext("/packages/aa/bb/widget-1.0.0.tar.gz", exchange -> respondEmpty(exchange, 404));

        Optional<PypiExternalConnectionClient.FetchedAsset> result = client.fetchFile("fake",
                serverBaseUrl + "/packages/aa/bb/widget-1.0.0.tar.gz");

        assertFalse(result.isPresent());
    }

    @Test
    void fetchFileReturnsEmptyForAnUnknownConnectionNameEvenWhenTheUrlIsReachable() {
        byte[] content = "sdist-content".getBytes(StandardCharsets.UTF_8);
        server.createContext("/packages/aa/bb/widget-1.0.0.tar.gz", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });

        Optional<PypiExternalConnectionClient.FetchedAsset> result = client.fetchFile("public:pypi",
                serverBaseUrl + "/packages/aa/bb/widget-1.0.0.tar.gz");

        assertFalse(result.isPresent());
    }

    @Test
    void fetchFileReturnsEmptyWhenTheUrlsHostIsNotTheConnectionsOwnHostOrAnAdditionalTrustedOne() {
        server.createContext("/packages/aa/bb/widget-1.0.0.tar.gz", exchange -> {
            fail("should never be requested: its host is not trusted");
        });

        Optional<PypiExternalConnectionClient.FetchedAsset> result = client.fetchFile("fake",
                "http://evil.example/packages/aa/bb/widget-1.0.0.tar.gz");

        assertFalse(result.isPresent());
    }

    /**
     * The fix for a real finding: a host check alone says nothing about the scheme, so a
     * {@code http://} URL naming an otherwise-trusted host would still have passed it, sending
     * the real file's bytes in plaintext even though this connection's own configured base is
     * {@code https}. Scheme is compared against the connection's own base, not a hardcoded
     * {@code https}, which is exactly why {@code client}'s own http base elsewhere in this file
     * never needs this: only a connection actually configured for https enforces it.
     */
    @Test
    void fetchFileRefusesAnHttpUrlOnAnOtherwiseTrustedHostWhenTheConnectionIsConfiguredForHttps() {
        server.createContext("/packages/aa/bb/widget-1.0.0.tar.gz", exchange -> fail("should never be requested: "
                + "its scheme does not match the connection's own https base"));
        PypiExternalConnectionClient httpsConfigured = new PypiExternalConnectionClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Map.of("fake", "https://this-host-is-never-actually-requested.invalid"),
                Map.of("fake", Set.of("127.0.0.1")));

        Optional<PypiExternalConnectionClient.FetchedAsset> result = httpsConfigured.fetchFile("fake",
                serverBaseUrl + "/packages/aa/bb/widget-1.0.0.tar.gz");

        assertFalse(result.isPresent());
    }

    @Test
    void fetchFileTrustsAConnectionsOwnAdditionalFileHostEvenWhenItDiffersFromTheIndexHost() {
        byte[] content = "sdist-content".getBytes(StandardCharsets.UTF_8);
        server.createContext("/packages/aa/bb/widget-1.0.0.tar.gz", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        PypiExternalConnectionClient withAdditionalHost = new PypiExternalConnectionClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Map.of("fake", "http://this-host-is-never-actually-requested.invalid"),
                Map.of("fake", Set.of("127.0.0.1")));

        Optional<PypiExternalConnectionClient.FetchedAsset> result = withAdditionalHost.fetchFile("fake",
                serverBaseUrl + "/packages/aa/bb/widget-1.0.0.tar.gz");

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get().content());
    }

    @Test
    void fetchFileRefusesToFollowARedirectToAHostThatIsNotTrusted() {
        server.createContext("/packages/aa/bb/widget-1.0.0.tar.gz", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://evil.example/stolen.tar.gz");
            respondEmpty(exchange, 302);
        });

        Optional<PypiExternalConnectionClient.FetchedAsset> result = client.fetchFile("fake",
                serverBaseUrl + "/packages/aa/bb/widget-1.0.0.tar.gz");

        assertFalse(result.isPresent());
    }

    @Test
    void fetchFileFollowsARedirectThatStaysOnATrustedHost() {
        byte[] content = "sdist-content".getBytes(StandardCharsets.UTF_8);
        server.createContext("/packages/aa/bb/widget-1.0.0.tar.gz", exchange -> {
            exchange.getResponseHeaders().add("Location", serverBaseUrl + "/packages/cc/dd/widget-1.0.0.tar.gz");
            respondEmpty(exchange, 302);
        });
        server.createContext("/packages/cc/dd/widget-1.0.0.tar.gz", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });

        Optional<PypiExternalConnectionClient.FetchedAsset> result = client.fetchFile("fake",
                serverBaseUrl + "/packages/aa/bb/widget-1.0.0.tar.gz");

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get().content());
    }

    private static void respondEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
