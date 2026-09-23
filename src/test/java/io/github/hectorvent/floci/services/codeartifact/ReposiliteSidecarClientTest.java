package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The wire contract Floci owns towards the Reposilite sidecar: provisioning a repository through
 * its {@code maven} settings domain, and deploying/fetching/checking artifacts through it.
 * Reposilite's own behaviour (Maven repository semantics) is not re-tested here.
 */
class ReposiliteSidecarClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> settingsBody = new AtomicReference<>("{\"repositories\":[]}");
    private HttpServer server;
    private ReposiliteSidecarClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        ReposiliteSidecarManager manager = mock(ReposiliteSidecarManager.class);
        when(manager.ensureReady()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        when(manager.basicAuthHeader()).thenReturn("Basic dGVzdDp0ZXN0");
        client = new ReposiliteSidecarClient(manager, mapper);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void ensureRepositoryCreatesAMissingRepository() throws Exception {
        AtomicReference<JsonNode> putBody = new AtomicReference<>();
        server.createContext("/api/settings/domain/maven", exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            if ("PUT".equals(exchange.getRequestMethod())) {
                putBody.set(mapper.readTree(exchange.getRequestBody()));
                respond(exchange, 200, "{}");
            } else {
                respond(exchange, 200, settingsBody.get());
            }
        });
        server.createContext("/api/maven/details/dom--repo/.floci-repository-ready-probe",
                exchange -> respond(exchange, 404, "{\"status\":404,\"message\":\"File not found\"}"));

        client.ensureRepository("dom--repo");

        assertThat(requestedPaths, hasSize(2));
        JsonNode created = putBody.get().path("repositories").get(0);
        assertThat(created.path("id").asText(), equalTo("dom--repo"));
        assertThat(created.path("visibility").asText(), equalTo("PUBLIC"));
        assertFalse(created.path("redeployment").asBoolean());
    }

    @Test
    void ensureRepositoryFailsClearlyWhenTheSidecarNeverRecognizesTheNewRepository() {
        // Settings accept the repository (200) but the sidecar never actually instantiates it,
        // exactly what happens when Reposilite silently rejects an otherwise well-formed entry
        // (a repository id over its own 64-character limit was a real case this caught).
        server.createContext("/api/settings/domain/maven", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "{}");
            } else {
                respond(exchange, 200, settingsBody.get());
            }
        });
        server.createContext("/api/maven/details/dom--repo/.floci-repository-ready-probe",
                exchange -> respond(exchange, 404, "{\"status\":404,\"message\":\"Repository dom--repo not found\"}"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.ensureRepository("dom--repo"));
        assertThat(e.getMessage(), containsString("did not become servable"));
    }

    @Test
    void ensureRepositoryIsIdempotentForAnExistingRepository() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        AtomicReference<String> lastMethod = new AtomicReference<>();
        server.createContext("/api/settings/domain/maven", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            respond(exchange, 200, settingsBody.get());
        });

        client.ensureRepository("dom--repo");

        assertThat(lastMethod.get(), equalTo("GET"));
    }

    @Test
    void deployArtifactPutsTheBytesAndReturnsTheStatus() throws Exception {
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>();
        server.createContext("/dom--repo/com/example/a/1.0/a-1.0.jar", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "");
        });

        int status = client.deployArtifact("dom--repo", "com/example/a/1.0/a-1.0.jar", "hello".getBytes(StandardCharsets.UTF_8));

        assertThat(status, is(200));
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), received.get());
        assertThat(authHeader.get(), equalTo("Basic dGVzdDp0ZXN0"));
    }

    @Test
    void fetchArtifactReturnsBytesAndContentTypeOnSuccess() {
        server.createContext("/dom--repo/com/example/a/1.0/a-1.0.jar", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
            respond(exchange, 200, "jar-bytes");
        });

        Optional<ReposiliteSidecarClient.FetchedArtifact> artifact =
                client.fetchArtifact("dom--repo", "com/example/a/1.0/a-1.0.jar");

        assertTrue(artifact.isPresent());
        assertArrayEquals("jar-bytes".getBytes(StandardCharsets.UTF_8), artifact.get().content());
        assertThat(artifact.get().contentType(), equalTo("application/java-archive"));
    }

    @Test
    void fetchArtifactIsEmptyOnNotFound() {
        server.createContext("/dom--repo/missing.jar", exchange -> respond(exchange, 404, ""));

        Optional<ReposiliteSidecarClient.FetchedArtifact> artifact = client.fetchArtifact("dom--repo", "missing.jar");

        assertTrue(artifact.isEmpty());
    }

    @Test
    void artifactExistsReflectsAHeadResponse() {
        server.createContext("/dom--repo/present.jar", exchange -> respond(exchange, 200, ""));
        server.createContext("/dom--repo/absent.jar", exchange -> respond(exchange, 404, ""));

        assertTrue(client.artifactExists("dom--repo", "present.jar"));
        assertFalse(client.artifactExists("dom--repo", "absent.jar"));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
