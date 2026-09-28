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
import java.util.ArrayList;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private ReposiliteSidecarManager manager;
    private ReposiliteSidecarClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        manager = mock(ReposiliteSidecarManager.class);
        when(manager.ensureReady()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        when(manager.basicAuthHeader()).thenReturn("Basic dGVzdDp0ZXN0");
        // Most tests here exercise a sidecar that is already up; the one test for the opposite
        // case (never started) overrides this back to false itself.
        when(manager.isStarted()).thenReturn(true);
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
    void interfaceEnsureReadyProvisionsTheRepositoryAndReturnsTheSidecarBaseUrl() throws Exception {
        server.createContext("/api/settings/domain/maven", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) {
                exchange.getRequestBody().readAllBytes();
                respond(exchange, 200, "{}");
            } else {
                respond(exchange, 200, settingsBody.get());
            }
        });
        server.createContext("/api/maven/details/dom--repo/.floci-repository-ready-probe",
                exchange -> respond(exchange, 404, "{\"status\":404,\"message\":\"File not found\"}"));

        // publicUrl is unused for Maven (no self-referential URLs to rewrite); passing a value
        // anyway to prove it is accepted without error, not just null.
        String baseUrl = client.ensureReady("dom--repo", "http://localhost:4566/codeartifact/maven/dom/repo/");

        assertThat(baseUrl, equalTo("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @Test
    void releaseRepositoryDeletesTopLevelEntriesThenRemovesTheSettingsEntry() throws Exception {
        settingsBody.set("{\"repositories\":["
                + "{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false},"
                + "{\"id\":\"other--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        List<String> deletedEntries = new CopyOnWriteArrayList<>();
        server.createContext("/api/maven/details/dom--repo", exchange ->
                respond(exchange, 200, "{\"name\":\"dom--repo\",\"type\":\"DIRECTORY\",\"files\":["
                        + "{\"name\":\"com\",\"type\":\"DIRECTORY\"},"
                        + "{\"name\":\"org\",\"type\":\"DIRECTORY\"}]}"));
        server.createContext("/dom--repo/com", exchange -> {
            deletedEntries.add("com");
            respond(exchange, 200, "");
        });
        server.createContext("/dom--repo/org", exchange -> {
            deletedEntries.add("org");
            respond(exchange, 200, "");
        });
        AtomicReference<JsonNode> putBody = new AtomicReference<>();
        server.createContext("/api/settings/domain/maven", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) {
                putBody.set(mapper.readTree(exchange.getRequestBody()));
                respond(exchange, 200, "{}");
            } else {
                respond(exchange, 200, settingsBody.get());
            }
        });

        client.releaseRepository("dom--repo");

        assertThat(deletedEntries, hasSize(2));
        assertTrue(deletedEntries.contains("com"));
        assertTrue(deletedEntries.contains("org"));
        List<String> remainingIds = new ArrayList<>();
        putBody.get().path("repositories").forEach(node -> remainingIds.add(node.path("id").asText()));
        assertThat(remainingIds, equalTo(List.of("other--repo")));
    }

    @Test
    void releaseRepositoryRemovesTheSettingsEntryForARegisteredButNeverPublishedToRepository() throws Exception {
        // Distinct from "never provisioned" (404): this repository is registered but has zero
        // files, the real shape Reposilite returns for one nobody ever published to.
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/maven/details/dom--repo",
                exchange -> respond(exchange, 200, "{\"name\":\"dom--repo\",\"type\":\"DIRECTORY\",\"files\":[]}"));
        AtomicReference<JsonNode> putBody = new AtomicReference<>();
        server.createContext("/api/settings/domain/maven", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) {
                putBody.set(mapper.readTree(exchange.getRequestBody()));
                respond(exchange, 200, "{}");
            } else {
                respond(exchange, 200, settingsBody.get());
            }
        });

        client.releaseRepository("dom--repo");

        assertThat(putBody.get().path("repositories").size(), is(0));
    }

    @Test
    void releaseRepositoryIsANoOpForANeverProvisionedRepository() {
        server.createContext("/api/maven/details/never-repo",
                exchange -> respond(exchange, 404, "{\"status\":404,\"message\":\"Repository never-repo not found\"}"));
        AtomicReference<String> settingsMethod = new AtomicReference<>();
        server.createContext("/api/settings/domain/maven", exchange -> {
            settingsMethod.set(exchange.getRequestMethod());
            respond(exchange, 200, settingsBody.get());
        });

        client.releaseRepository("never-repo");

        // Only ever GETs the current list to check membership; never PUTs a settings change for a
        // repository that was never in it.
        assertThat(settingsMethod.get(), equalTo("GET"));
    }

    @Test
    void releaseRepositoryDoesNotStartTheSidecarWhenItWasNeverStarted() {
        // Every CodeArtifact repository gets a Maven sidecar id at creation regardless of whether
        // it is ever used through Maven, so DeleteRepository calls this unconditionally; without
        // this guard, deleting any repository at all would start the shared Reposilite container
        // just to look for content that was never there.
        when(manager.isStarted()).thenReturn(false);

        client.releaseRepository("never-repo");

        verify(manager, never()).ensureReady();
    }

    @Test
    void releaseRepositoryDoesNotUnregisterTheRepositoryWhenListingItsFilesFails() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/maven/details/dom--repo",
                exchange -> respond(exchange, 500, "{\"status\":500,\"message\":\"internal error\"}"));
        AtomicReference<String> settingsMethod = new AtomicReference<>();
        server.createContext("/api/settings/domain/maven", exchange -> {
            settingsMethod.set(exchange.getRequestMethod());
            respond(exchange, 200, settingsBody.get());
        });

        // A non-404 failure while listing means the repository's real content is unknown, not
        // empty: proceeding to remove it from settings anyway would orphan whatever was never
        // listed, unreachable afterward, the same failure shape release exists to prevent.
        assertThrows(IllegalStateException.class, () -> client.releaseRepository("dom--repo"));
        assertThat(settingsMethod.get() == null || "GET".equals(settingsMethod.get()), is(true));
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
