package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.XmlParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        ReposiliteSidecarClient.DeployOutcome outcome = client.deployArtifact("dom--repo",
                "com/example/a/1.0/a-1.0.jar", "hello".getBytes(StandardCharsets.UTF_8));

        assertThat(outcome.status(), is(200));
        assertTrue(outcome.wroteNewContent());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), received.get());
        assertThat(authHeader.get(), equalTo("Basic dGVzdDp0ZXN0"));
    }

    @Test
    void deployArtifactShortCircuitsToTwoHundredWithoutPuttingWhenContentIsByteIdentical() {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        List<String> methodsSeen = new CopyOnWriteArrayList<>();
        server.createContext("/dom--repo/com/example/a/1.0/a-1.0.jar", exchange -> {
            methodsSeen.add(exchange.getRequestMethod());
            respond(exchange, 200, "hello");
        });

        ReposiliteSidecarClient.DeployOutcome outcome = client.deployArtifact("dom--repo",
                "com/example/a/1.0/a-1.0.jar", content);

        assertThat(outcome.status(), is(200));
        assertFalse(outcome.wroteNewContent());
        assertThat(methodsSeen, equalTo(List.of("GET")));
    }

    @Test
    void deployArtifactStillPutsWhenExistingContentDiffers() {
        AtomicReference<byte[]> putBody = new AtomicReference<>();
        List<String> methodsSeen = new CopyOnWriteArrayList<>();
        server.createContext("/dom--repo/com/example/a/1.0/a-1.0.jar", exchange -> {
            methodsSeen.add(exchange.getRequestMethod());
            if ("PUT".equals(exchange.getRequestMethod())) {
                putBody.set(exchange.getRequestBody().readAllBytes());
                // Reposilite's real redeployment:false response for a genuine content mismatch;
                // this must pass straight through unchanged.
                respond(exchange, 409, "{\"status\":409,\"message\":\"Redeployment is not allowed\"}");
            } else {
                respond(exchange, 200, "goodbye");
            }
        });

        ReposiliteSidecarClient.DeployOutcome outcome = client.deployArtifact("dom--repo",
                "com/example/a/1.0/a-1.0.jar", "hello".getBytes(StandardCharsets.UTF_8));

        assertThat(outcome.status(), is(409));
        assertFalse(outcome.wroteNewContent());
        assertThat(methodsSeen, equalTo(List.of("GET", "PUT")));
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), putBody.get());
    }

    @Test
    void deployArtifactStillPutsWhenThePreflightCheckFails() {
        AtomicReference<byte[]> putBody = new AtomicReference<>();
        List<String> methodsSeen = new CopyOnWriteArrayList<>();
        server.createContext("/dom--repo/com/example/a/1.0/a-1.0.jar", exchange -> {
            methodsSeen.add(exchange.getRequestMethod());
            if ("PUT".equals(exchange.getRequestMethod())) {
                putBody.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, "");
            } else {
                // Simulates a dropped connection on the preflight GET (a timeout, a transient
                // network error): closing without ever sending a response is what the client sees
                // as an IOException, not a clean 4xx/5xx. The PUT must still go through. The JDK's
                // own HttpClient transparently retries an idempotent GET once on this kind of
                // connection-level failure before giving up, so more than one GET landing here
                // first is expected and not itself part of what this test is checking.
                exchange.close();
            }
        });

        ReposiliteSidecarClient.DeployOutcome outcome = client.deployArtifact("dom--repo",
                "com/example/a/1.0/a-1.0.jar", "hello".getBytes(StandardCharsets.UTF_8));

        assertThat(outcome.status(), is(200));
        assertTrue(outcome.wroteNewContent());
        assertTrue(methodsSeen.stream().allMatch(m -> m.equals("GET") || m.equals("PUT")));
        assertThat(methodsSeen.get(methodsSeen.size() - 1), equalTo("PUT"));
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), putBody.get());
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
    void fetchPackageVersionAssetBuildsTheGavPathFromTheGroupIdArtifactIdVersionAndFilename() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        server.createContext("/dom--repo/com/example/nested/a/1.0/a-1.0.jar", exchange -> respond(exchange, 200, "jar-bytes"));

        Optional<byte[]> result = client.fetchPackageVersionAsset("dom--repo", "dom", "repo", "com.example.nested",
                "a", "1.0", "a-1.0.jar");

        assertTrue(result.isPresent());
        assertArrayEquals("jar-bytes".getBytes(StandardCharsets.UTF_8), result.get());
    }

    /**
     * The fix for a real finding: building the request URI with a plain string concatenation
     * would let a {@code #} in a caller-supplied asset name get interpreted as a URI fragment and
     * silently dropped from the actual wire request, truncating it to a shorter, real filename.
     * Checks the path the backend actually receives, not just the outcome: {@code com.sun.net.
     * httpserver.HttpServer}'s own context matching turns out to be looser than a real HTTP
     * server's (it matches a registered {@code .../b-1.0.jar} against a request path of
     * {@code .../b-1.0.jar#forged} too), so asserting on the response alone wouldn't actually
     * distinguish the fixed behavior from the bug this is checking for.
     */
    @Test
    void fetchPackageVersionAssetDoesNotTreatAHashInTheAssetNameAsAUriFragment() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        AtomicReference<String> observedPath = new AtomicReference<>();
        server.createContext("/dom--repo/com/example/b/1.0/b-1.0.jar", exchange -> {
            observedPath.set(exchange.getRequestURI().getPath());
            respond(exchange, 404, "");
        });

        client.fetchPackageVersionAsset("dom--repo", "dom", "repo", "com.example", "b", "1.0", "b-1.0.jar#forged");

        assertThat(observedPath.get(), equalTo("/dom--repo/com/example/b/1.0/b-1.0.jar#forged"));
    }

    @Test
    void fetchPackageVersionAssetDoesNotTreatAQuestionMarkInTheAssetNameAsAQueryString() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        AtomicReference<String> observedPath = new AtomicReference<>();
        AtomicReference<String> observedQuery = new AtomicReference<>();
        server.createContext("/dom--repo/com/example/b/1.0/b-1.0.jar", exchange -> {
            observedPath.set(exchange.getRequestURI().getPath());
            observedQuery.set(exchange.getRequestURI().getQuery());
            respond(exchange, 404, "");
        });

        client.fetchPackageVersionAsset("dom--repo", "dom", "repo", "com.example", "b", "1.0", "b-1.0.jar?evil=1");

        assertThat(observedPath.get(), equalTo("/dom--repo/com/example/b/1.0/b-1.0.jar?evil=1"));
        assertThat(observedQuery.get(), is(nullValue()));
    }

    @Test
    void fetchPackageVersionAssetReturnsEmptyWhenTheBackendHasNoSuchFile() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        server.createContext("/dom--repo/com/example/a/1.0/missing.jar", exchange -> respond(exchange, 404, ""));

        Optional<byte[]> result = client.fetchPackageVersionAsset("dom--repo", "dom", "repo", "com.example", "a",
                "1.0", "missing.jar");

        assertTrue(result.isEmpty());
    }

    /**
     * The fix for a real finding: building the request URI from only the base URL's scheme and
     * authority, dropping any path it has, would break a pre-configured
     * {@code FLOCI_SERVICES_CODEARTIFACT_MAVEN_URL} that points at Reposilite behind its own path
     * prefix. Setup still used the full configured URL in that case, but every subsequent GET/PUT
     * would go to the server root instead and fail.
     */
    @Test
    void fetchPackageVersionAssetPreservesABaseUrlPathPrefix() {
        when(manager.ensureReady()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/reposilite-prefix");
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/reposilite-prefix/api/settings/domain/maven",
                exchange -> respond(exchange, 200, settingsBody.get()));
        server.createContext("/reposilite-prefix/dom--repo/com/example/a/1.0/a-1.0.jar",
                exchange -> respond(exchange, 200, "jar-bytes"));

        Optional<byte[]> result = client.fetchPackageVersionAsset("dom--repo", "dom", "repo", "com.example", "a",
                "1.0", "a-1.0.jar");

        assertTrue(result.isPresent());
        assertArrayEquals("jar-bytes".getBytes(StandardCharsets.UTF_8), result.get());
    }

    /**
     * The fix for a real finding on top of the previous one: decoding the configured prefix with
     * {@code getPath()} and handing it to the URI constructor alongside the new path would
     * re-encode the whole thing together, turning a deliberately-escaped {@code %2F} (one opaque
     * segment containing a literal slash) back into a real {@code /}. Something in front of the
     * sidecar that treats those two differently would then see a different path than the one that
     * was actually configured. Checks the raw path the backend receives on the wire, since by the
     * time {@code com.sun.net.httpserver.HttpServer} hands back a matched context, its own
     * {@code getPath()} would already show the decoded form either way.
     */
    @Test
    void fetchPackageVersionAssetPreservesAPercentEncodedCharacterInTheBaseUrlPrefix() {
        when(manager.ensureReady())
                .thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/reposilite%2Fv1");
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/reposilite/v1/api/settings/domain/maven",
                exchange -> respond(exchange, 200, settingsBody.get()));
        AtomicReference<String> observedRawPath = new AtomicReference<>();
        server.createContext("/reposilite/v1/dom--repo/com/example/a/1.0/a-1.0.jar", exchange -> {
            observedRawPath.set(exchange.getRequestURI().getRawPath());
            respond(exchange, 200, "jar-bytes");
        });

        Optional<byte[]> result = client.fetchPackageVersionAsset("dom--repo", "dom", "repo", "com.example", "a",
                "1.0", "a-1.0.jar");

        assertTrue(result.isPresent());
        assertThat(observedRawPath.get(), equalTo("/reposilite%2Fv1/dom--repo/com/example/a/1.0/a-1.0.jar"));
    }

    /**
     * Same class of bug as the path-prefix case above, but for the authority component: a
     * percent-encoded character within userinfo (e.g. {@code user%2Ftenant@host}) must survive
     * untouched. {@code getAuthority()} decodes it before the URI is rebuilt, which would send
     * the request to a different authority than the one actually configured. Unlike the path
     * case, this isn't observable over the wire through a live HTTP call (userinfo never reaches
     * the server), so this calls the private helper directly via reflection.
     */
    @Test
    void requestUriPreservesAPercentEncodedCharacterInTheBaseUrlAuthority() throws Exception {
        Method requestUri = ReposiliteSidecarClient.class.getDeclaredMethod("requestUri", String.class, String.class);
        requestUri.setAccessible(true);

        URI uri = (URI) requestUri.invoke(null, "http://user%2Ftenant@example.com", "/dom--repo/a-1.0.jar");

        assertThat(uri.getRawAuthority(), equalTo("user%2Ftenant@example.com"));
    }

    /**
     * The fix for a real finding: {@code fetchPackageVersionAsset} used to build its GAV path
     * with a plain {@code +} concatenation of four independently caller-supplied coordinates. A
     * {@code /} inside {@code assetName} would then be indistinguishable from one of the real
     * separators between groupId/artifactId/version/filename, letting it splice in extra path
     * segments. Fixed by percent-encoding each coordinate on its own before joining them, so a
     * {@code /} inside one of them survives only as a literal {@code %2F} within one segment.
     */
    @Test
    void fetchPackageVersionAssetTreatsASlashInTheAssetNameAsLiteralNotAStructuralSeparator() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        AtomicReference<String> observedRawPath = new AtomicReference<>();
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getRawPath().startsWith("/dom--repo/")) {
                observedRawPath.set(exchange.getRequestURI().getRawPath());
            }
            respond(exchange, 404, "");
        });

        Optional<byte[]> result = client.fetchPackageVersionAsset("dom--repo", "dom", "repo", "com.example", "a",
                "1.0", "../other.jar");

        assertTrue(result.isEmpty());
        assertThat(observedRawPath.get(), equalTo("/dom--repo/com/example/a/1.0/..%2Fother.jar"));
    }

    /**
     * Same finding, the other half of it: a coordinate that is exactly {@code ..} (no slash at
     * all, so the fix above wouldn't catch it on its own) would sit as a real dot-segment between
     * two of this method's own {@code /} separators. Fixed by also escaping the dots themselves
     * whenever a coordinate's entire value is {@code .} or {@code ..}.
     */
    @Test
    void fetchPackageVersionAssetTreatsADotDotVersionAsLiteralNotADotSegment() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        AtomicReference<String> observedRawPath = new AtomicReference<>();
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getRawPath().startsWith("/dom--repo/")) {
                observedRawPath.set(exchange.getRequestURI().getRawPath());
            }
            respond(exchange, 404, "");
        });

        Optional<byte[]> result = client.fetchPackageVersionAsset("dom--repo", "dom", "repo", "com.example", "a",
                "..", "a-1.0.jar");

        assertTrue(result.isEmpty());
        assertThat(observedRawPath.get(), equalTo("/dom--repo/com/example/a/%2E%2E/a-1.0.jar"));
    }

    @Test
    void artifactExistsReflectsAHeadResponse() {
        server.createContext("/dom--repo/present.jar", exchange -> respond(exchange, 200, ""));
        server.createContext("/dom--repo/absent.jar", exchange -> respond(exchange, 404, ""));

        assertTrue(client.artifactExists("dom--repo", "present.jar"));
        assertFalse(client.artifactExists("dom--repo", "absent.jar"));
    }

    @Test
    void packageExistsReflectsWhetherTheGroupArtifactDirectoryHasAVersionSubdirectory() {
        server.createContext("/api/maven/details/dom--repo/com/example/present/",
                exchange -> respond(exchange, 200, "{\"files\":["
                        + "{\"name\":\"1.0\",\"type\":\"DIRECTORY\"}]}"));
        server.createContext("/api/maven/details/dom--repo/com/example/absent/", exchange -> respond(exchange, 404,
                "{\"status\":404,\"message\":\"File not found\"}"));

        assertTrue(client.packageExists("dom--repo", "dom", "repo", "com.example", "present"));
        assertFalse(client.packageExists("dom--repo", "dom", "repo", "com.example", "absent"));
    }

    @Test
    void packageExistsIsFalseForAGroupArtifactDirectoryThatHasOnlyAMergedMetadataFileAndNoRealVersion() {
        server.createContext("/api/maven/details/dom--repo/com/example/metadata-only/",
                exchange -> respond(exchange, 200, "{\"files\":["
                        + "{\"name\":\"maven-metadata.xml\",\"type\":\"FILE\"}]}"));

        assertFalse(client.packageExists("dom--repo", "dom", "repo", "com.example", "metadata-only"));
    }

    @Test
    void packageExistsThrowsRatherThanReportingAbsentOnASidecarServerError() {
        server.createContext("/api/maven/details/dom--repo/com/example/broken/", exchange -> respond(exchange, 500,
                "{\"status\":500,\"message\":\"internal error\"}"));

        assertThrows(IllegalStateException.class, () -> client.packageExists("dom--repo", "dom", "repo",
                "com.example", "broken"));
    }

    @Test
    void deletePackageSendsADeleteToTheGroupArtifactDirectory() {
        AtomicReference<String> deletedPath = new AtomicReference<>();
        server.createContext("/dom--repo/com/example/gone/", exchange -> {
            deletedPath.set(exchange.getRequestURI().toString());
            respond(exchange, 200, "");
        });

        client.deletePackage("dom--repo", "dom", "repo", "com.example", "gone");

        assertThat(deletedPath.get(), equalTo("/dom--repo/com/example/gone/"));
    }

    @Test
    void deletePackageThrowsWhenTheSidecarAnswersWithAnUnexpectedStatus() {
        server.createContext("/dom--repo/com/example/weird/", exchange -> respond(exchange, 500, ""));

        assertThrows(IllegalStateException.class, () -> client.deletePackage("dom--repo", "dom", "repo",
                "com.example", "weird"));
    }

    @Test
    void listPackageVersionAssetNamesReturnsOnlyTheFileEntriesFromTheVersionDirectory() {
        server.createContext("/api/maven/details/dom--repo/com/example/a/1.0",
                exchange -> respond(exchange, 200, "{\"name\":\"1.0\",\"type\":\"DIRECTORY\",\"files\":["
                        + "{\"name\":\"a-1.0.jar\",\"type\":\"FILE\"},"
                        + "{\"name\":\"a-1.0.pom\",\"type\":\"FILE\"}]}"));

        List<String> names = client.listPackageVersionAssetNames("dom--repo", "dom", "repo", "com.example", "a",
                "1.0");

        assertThat(names, equalTo(List.of("a-1.0.jar", "a-1.0.pom")));
    }

    @Test
    void listPackageVersionAssetNamesIsEmptyForAMissingVersion() {
        server.createContext("/api/maven/details/dom--repo/com/example/a/9.9",
                exchange -> respond(exchange, 404, "{\"status\":404,\"message\":\"File not found\"}"));

        assertThat(client.listPackageVersionAssetNames("dom--repo", "dom", "repo", "com.example", "a", "9.9"),
                equalTo(List.of()));
    }

    @Test
    void listPackageVersionAssetNamesThrowsOnAnUnexpectedStatus() {
        server.createContext("/api/maven/details/dom--repo/com/example/a/1.0",
                exchange -> respond(exchange, 500, "{\"status\":500,\"message\":\"internal error\"}"));

        assertThrows(IllegalStateException.class,
                () -> client.listPackageVersionAssetNames("dom--repo", "dom", "repo", "com.example", "a", "1.0"));
    }

    /**
     * A transient error on one version's own details page (auth failure, Reposilite briefly
     * unavailable) must surface as a real error, the same way a non-404 failure on the
     * artifact-level listing just above already does, rather than being treated identically to
     * "this version has no files yet" and silently reporting no publish time for it.
     */
    @Test
    void listPackageVersionsThrowsWhenAVersionsDetailsPageFailsWithARealError() {
        server.createContext("/api/maven/details/dom--repo/com/example/a",
                exchange -> respond(exchange, 200, "{\"name\":\"a\",\"type\":\"DIRECTORY\",\"files\":["
                        + "{\"name\":\"1.0\",\"type\":\"DIRECTORY\"}]}"));
        server.createContext("/api/maven/details/dom--repo/com/example/a/1.0",
                exchange -> respond(exchange, 500, "{\"status\":500,\"message\":\"internal error\"}"));

        assertThrows(IllegalStateException.class,
                () -> client.listPackageVersions("dom--repo", "dom", "repo", "com.example", "a"));
    }

    @Test
    void storePackageVersionAssetPutsTheBytesDirectlyEvenWhenDifferingContentAlreadyExists() throws Exception {
        // deployArtifact's own redeployment:false preflight would refuse this; storePackageVersionAsset
        // never GETs first and must PUT unconditionally, the way CopyPackageVersions' own
        // delete-then-store overwrite sequence relies on.
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        List<String> methodsSeen = new CopyOnWriteArrayList<>();
        AtomicReference<byte[]> putBody = new AtomicReference<>();
        server.createContext("/dom--repo/com/example/a/1.0/a-1.0.jar", exchange -> {
            methodsSeen.add(exchange.getRequestMethod());
            putBody.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "");
        });

        client.storePackageVersionAsset("dom--repo", "dom", "repo", "com.example", "a", "1.0", "a-1.0.jar",
                "hello".getBytes(StandardCharsets.UTF_8));

        assertThat(methodsSeen, equalTo(List.of("PUT")));
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), putBody.get());
    }

    @Test
    void storePackageVersionAssetThrowsOnAnUnexpectedStatus() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        server.createContext("/dom--repo/com/example/a/1.0/a-1.0.jar", exchange -> respond(exchange, 500, ""));

        assertThrows(IllegalStateException.class, () -> client.storePackageVersionAsset("dom--repo", "dom", "repo",
                "com.example", "a", "1.0", "a-1.0.jar", "hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void deletePackageVersionAssetDeletesTheExactGavPath() {
        AtomicReference<String> deletedMethod = new AtomicReference<>();
        server.createContext("/dom--repo/com/example/a/1.0/a-1.0.jar", exchange -> {
            deletedMethod.set(exchange.getRequestMethod());
            respond(exchange, 200, "");
        });

        client.deletePackageVersionAsset("dom--repo", "dom", "repo", "com.example", "a", "1.0", "a-1.0.jar");

        assertThat(deletedMethod.get(), equalTo("DELETE"));
    }

    @Test
    void deletePackageVersionAssetThrowsOnAnUnexpectedStatus() {
        server.createContext("/dom--repo/com/example/a/1.0/a-1.0.jar", exchange -> respond(exchange, 500, ""));

        assertThrows(IllegalStateException.class, () -> client.deletePackageVersionAsset("dom--repo", "dom", "repo",
                "com.example", "a", "1.0", "a-1.0.jar"));
    }

    @Test
    void recordVersionAvailableCreatesANewMetadataFileWhenNoneExists() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        AtomicReference<byte[]> putBody = new AtomicReference<>();
        server.createContext("/dom--repo/com/example/a/maven-metadata.xml", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 404, "");
            } else {
                putBody.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, "");
            }
        });

        client.recordVersionAvailable("dom--repo", "dom", "repo", "com.example", "a", "1.0");

        String xml = new String(putBody.get(), StandardCharsets.UTF_8);
        assertEquals("com.example", XmlParser.extractFirst(xml, "groupId", null));
        assertEquals("a", XmlParser.extractFirst(xml, "artifactId", null));
        assertEquals("1.0", XmlParser.extractFirst(xml, "latest", null));
        assertEquals("1.0", XmlParser.extractFirst(xml, "release", null));
        assertEquals(List.of("1.0"), XmlParser.extractAll(xml, "version"));
        assertTrue(XmlParser.extractFirst(xml, "lastUpdated", "").matches("\\d{14}"));
    }

    @Test
    void recordVersionAvailableAppendsToExistingVersionsAndAdvancesReleaseForANonSnapshot() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        String existing = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>1.0</latest><release>1.0</release><versions><version>1.0</version></versions>"
                + "<lastUpdated>20260101000000</lastUpdated></versioning></metadata>";
        AtomicReference<byte[]> putBody = new AtomicReference<>();
        server.createContext("/dom--repo/com/example/a/maven-metadata.xml", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, existing);
            } else {
                putBody.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, "");
            }
        });

        client.recordVersionAvailable("dom--repo", "dom", "repo", "com.example", "a", "2.0");

        String xml = new String(putBody.get(), StandardCharsets.UTF_8);
        assertEquals(List.of("1.0", "2.0"), XmlParser.extractAll(xml, "version"));
        assertEquals("2.0", XmlParser.extractFirst(xml, "latest", null));
        assertEquals("2.0", XmlParser.extractFirst(xml, "release", null));
    }

    @Test
    void recordVersionAvailableLeavesTheExistingReleaseUntouchedForASnapshotVersion() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        String existing = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>1.0</latest><release>1.0</release><versions><version>1.0</version></versions>"
                + "<lastUpdated>20260101000000</lastUpdated></versioning></metadata>";
        AtomicReference<byte[]> putBody = new AtomicReference<>();
        server.createContext("/dom--repo/com/example/a/maven-metadata.xml", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, existing);
            } else {
                putBody.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, "");
            }
        });

        client.recordVersionAvailable("dom--repo", "dom", "repo", "com.example", "a", "2.0-SNAPSHOT");

        String xml = new String(putBody.get(), StandardCharsets.UTF_8);
        assertEquals(List.of("1.0", "2.0-SNAPSHOT"), XmlParser.extractAll(xml, "version"));
        assertEquals("2.0-SNAPSHOT", XmlParser.extractFirst(xml, "latest", null));
        assertEquals("1.0", XmlParser.extractFirst(xml, "release", null));
    }

    @Test
    void mergeExternalConnectionReleaseVersionsCreatesMetadataFromTheExternalSourceWhenNoneExistsLocally() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        String metadataPath = "/dom--repo/com/example/a/maven-metadata.xml";
        AtomicReference<byte[]> putBody = new AtomicReference<>();
        server.createContext(metadataPath, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 404, "");
            } else if (metadataPath.equals(exchange.getRequestURI().getPath())) {
                putBody.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, "");
            } else {
                respond(exchange, 200, "");
            }
        });
        String externalXml = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>1.2</latest><release>1.2</release><versions><version>1.0</version>"
                + "<version>1.1</version><version>1.2</version></versions></versioning></metadata>";

        boolean merged = client.mergeExternalConnectionReleaseVersions("dom--repo", "com.example", "a",
                externalXml.getBytes(StandardCharsets.UTF_8), v -> true);

        assertTrue(merged);
        String xml = new String(putBody.get(), StandardCharsets.UTF_8);
        assertEquals("com.example", XmlParser.extractFirst(xml, "groupId", null));
        assertEquals("a", XmlParser.extractFirst(xml, "artifactId", null));
        assertEquals("1.2", XmlParser.extractFirst(xml, "latest", null));
        assertEquals("1.2", XmlParser.extractFirst(xml, "release", null));
        assertEquals(List.of("1.0", "1.1", "1.2"), XmlParser.extractAll(xml, "version"));
    }

    @Test
    void mergeExternalConnectionReleaseVersionsWritesMatchingSha1AndMd5Checksums() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        String metadataPath = "/dom--repo/com/example/a/maven-metadata.xml";
        AtomicReference<byte[]> putBody = new AtomicReference<>();
        AtomicReference<byte[]> sha1Body = new AtomicReference<>();
        AtomicReference<byte[]> md5Body = new AtomicReference<>();
        server.createContext(metadataPath, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 404, "");
            } else if (metadataPath.equals(exchange.getRequestURI().getPath())) {
                putBody.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, "");
            } else {
                respond(exchange, 200, "");
            }
        });
        server.createContext(metadataPath + ".sha1", exchange -> {
            sha1Body.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "");
        });
        server.createContext(metadataPath + ".md5", exchange -> {
            md5Body.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "");
        });
        String externalXml = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>1.0</latest><release>1.0</release><versions><version>1.0</version></versions>"
                + "</versioning></metadata>";

        client.mergeExternalConnectionReleaseVersions("dom--repo", "com.example", "a",
                externalXml.getBytes(StandardCharsets.UTF_8), v -> true);

        assertEquals(Digests.sha1Hex(putBody.get()), new String(sha1Body.get(), StandardCharsets.UTF_8));
        assertEquals(Digests.md5Hex(putBody.get()), new String(md5Body.get(), StandardCharsets.UTF_8));
    }

    @Test
    void mergeExternalConnectionReleaseVersionsExcludesAVersionTheVersionAllowedPredicateRejects() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        String metadataPath = "/dom--repo/com/example/a/maven-metadata.xml";
        AtomicReference<byte[]> putBody = new AtomicReference<>();
        server.createContext(metadataPath, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 404, "");
            } else if (metadataPath.equals(exchange.getRequestURI().getPath())) {
                putBody.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, "");
            } else {
                respond(exchange, 200, "");
            }
        });
        String externalXml = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>1.1</latest><release>1.1</release><versions><version>1.0</version>"
                + "<version>1.1</version></versions></versioning></metadata>";

        // Simulates a version this repository already knows is Disposed: a public repository's
        // own metadata has no idea that happened, so a merge must never let a rejected version
        // back in just because the public source still lists it.
        boolean merged = client.mergeExternalConnectionReleaseVersions("dom--repo", "com.example", "a",
                externalXml.getBytes(StandardCharsets.UTF_8), v -> !v.equals("1.1"));

        assertTrue(merged);
        String xml = new String(putBody.get(), StandardCharsets.UTF_8);
        assertEquals(List.of("1.0"), XmlParser.extractAll(xml, "version"));
        assertEquals("1.0", XmlParser.extractFirst(xml, "latest", null));
        assertEquals("1.0", XmlParser.extractFirst(xml, "release", null));
    }

    /**
     * {@link io.github.hectorvent.floci.services.codeartifact.ReposiliteSidecarClient#fetchArtifact}
     * returns {@code empty()} for a transient server error too, not just a confirmed 404: a merge
     * that read the existing local metadata through that method could mistake the error for "no
     * local metadata yet" and silently replace a real, locally-published package's {@code latest}/
     * {@code release} with the external source's own guess at them. The merge must surface the
     * failure instead.
     */
    @Test
    void mergeExternalConnectionReleaseVersionsThrowsOnATransientReadErrorInsteadOfTreatingItAsMissing() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        server.createContext("/dom--repo/com/example/a/maven-metadata.xml", exchange -> respond(exchange, 500, ""));
        String externalXml = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>1.0</latest><release>1.0</release><versions><version>1.0</version></versions>"
                + "</versioning></metadata>";

        assertThrows(IllegalStateException.class, () -> client.mergeExternalConnectionReleaseVersions("dom--repo",
                "com.example", "a", externalXml.getBytes(StandardCharsets.UTF_8), v -> true));
    }

    /**
     * A locally-published version has no reliable way to be compared against whatever the public
     * repository's own metadata declares as its latest/release (Maven version strings don't sort
     * by any universal rule), so a merge must only ever add the versions the local copy doesn't
     * already have, never move {@code latest}/{@code release} away from what a real local publish
     * already recorded.
     */
    @Test
    void mergeExternalConnectionReleaseVersionsAppendsMissingVersionsWithoutOverridingAnExistingLocalLatest() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        String existing = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>2.0</latest><release>2.0</release><versions><version>2.0</version></versions>"
                + "<lastUpdated>20260101000000</lastUpdated></versioning></metadata>";
        String metadataPath = "/dom--repo/com/example/a/maven-metadata.xml";
        AtomicReference<byte[]> putBody = new AtomicReference<>();
        server.createContext(metadataPath, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, existing);
            } else if (metadataPath.equals(exchange.getRequestURI().getPath())) {
                putBody.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, "");
            } else {
                respond(exchange, 200, "");
            }
        });
        String externalXml = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>1.1</latest><release>1.1</release><versions><version>1.0</version>"
                + "<version>1.1</version></versions></versioning></metadata>";

        boolean merged = client.mergeExternalConnectionReleaseVersions("dom--repo", "com.example", "a",
                externalXml.getBytes(StandardCharsets.UTF_8), v -> true);

        assertTrue(merged);
        String xml = new String(putBody.get(), StandardCharsets.UTF_8);
        assertEquals(List.of("2.0", "1.0", "1.1"), XmlParser.extractAll(xml, "version"));
        assertEquals("2.0", XmlParser.extractFirst(xml, "latest", null));
        assertEquals("2.0", XmlParser.extractFirst(xml, "release", null));
    }

    @Test
    void mergeExternalConnectionReleaseVersionsFiltersOutSnapshotVersionsFromTheExternalSource() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        String metadataPath = "/dom--repo/com/example/a/maven-metadata.xml";
        AtomicReference<byte[]> putBody = new AtomicReference<>();
        server.createContext(metadataPath, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 404, "");
            } else if (metadataPath.equals(exchange.getRequestURI().getPath())) {
                putBody.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, "");
            } else {
                respond(exchange, 200, "");
            }
        });
        String externalXml = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>1.1-SNAPSHOT</latest><versions><version>1.0</version>"
                + "<version>1.1-SNAPSHOT</version></versions></versioning></metadata>";

        boolean merged = client.mergeExternalConnectionReleaseVersions("dom--repo", "com.example", "a",
                externalXml.getBytes(StandardCharsets.UTF_8), v -> true);

        assertTrue(merged);
        String xml = new String(putBody.get(), StandardCharsets.UTF_8);
        assertEquals(List.of("1.0"), XmlParser.extractAll(xml, "version"));
        assertEquals("1.0", XmlParser.extractFirst(xml, "latest", null));
    }

    @Test
    void mergeExternalConnectionReleaseVersionsIsANoOpWhenEveryExternalVersionIsAlreadyListedLocally() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        String existing = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>1.0</latest><release>1.0</release><versions><version>1.0</version></versions>"
                + "<lastUpdated>20260101000000</lastUpdated></versioning></metadata>";
        String metadataPath = "/dom--repo/com/example/a/maven-metadata.xml";
        AtomicReference<byte[]> putBody = new AtomicReference<>();
        server.createContext(metadataPath, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, existing);
            } else if (metadataPath.equals(exchange.getRequestURI().getPath())) {
                putBody.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, "");
            } else {
                respond(exchange, 200, "");
            }
        });
        String externalXml = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>1.0</latest><release>1.0</release><versions><version>1.0</version></versions></"
                + "versioning></metadata>";

        boolean merged = client.mergeExternalConnectionReleaseVersions("dom--repo", "com.example", "a",
                externalXml.getBytes(StandardCharsets.UTF_8), v -> true);

        assertFalse(merged);
        assertNull(putBody.get());
    }

    /**
     * Raised by Greptile's review: a prior attempt whose metadata write succeeded but whose
     * checksum write then failed would otherwise leave a stale checksum in place forever, since
     * every later request for this exact package also finds nothing new to add and takes this
     * same no-op path. The repair must happen every time this path is taken, not just once.
     */
    @Test
    void mergeExternalConnectionReleaseVersionsRepairsChecksumsEvenWhenItIsANoOp() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        String existing = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>1.0</latest><release>1.0</release><versions><version>1.0</version></versions>"
                + "<lastUpdated>20260101000000</lastUpdated></versioning></metadata>";
        String metadataPath = "/dom--repo/com/example/a/maven-metadata.xml";
        AtomicReference<byte[]> sha1Body = new AtomicReference<>();
        AtomicReference<byte[]> md5Body = new AtomicReference<>();
        server.createContext(metadataPath, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, existing);
            } else {
                respond(exchange, 200, "");
            }
        });
        server.createContext(metadataPath + ".sha1", exchange -> {
            sha1Body.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "");
        });
        server.createContext(metadataPath + ".md5", exchange -> {
            md5Body.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "");
        });
        String externalXml = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>1.0</latest><release>1.0</release><versions><version>1.0</version></versions></"
                + "versioning></metadata>";

        boolean merged = client.mergeExternalConnectionReleaseVersions("dom--repo", "com.example", "a",
                externalXml.getBytes(StandardCharsets.UTF_8), v -> true);

        assertFalse(merged);
        assertEquals(Digests.sha1Hex(existing.getBytes(StandardCharsets.UTF_8)),
                new String(sha1Body.get(), StandardCharsets.UTF_8));
        assertEquals(Digests.md5Hex(existing.getBytes(StandardCharsets.UTF_8)),
                new String(md5Body.get(), StandardCharsets.UTF_8));
    }

    /**
     * {@link io.github.hectorvent.floci.services.codeartifact.ReposiliteSidecarClient#recordVersionDisposed}'s
     * dispose-time counterpart to {@link #recordVersionAvailableAppendsToExistingVersionsAndAdvancesReleaseForANonSnapshot}:
     * removing a non-latest version must leave {@code latest}/{@code release} exactly as they
     * were, since the metadata's own append-order list is untouched past the removed entry.
     */
    @Test
    void recordVersionDisposedRemovesTheVersionAndLeavesLatestAndReleaseUntouchedWhenItWasNeitherOne() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        String existing = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>2.0</latest><release>2.0</release><versions><version>1.0</version>"
                + "<version>2.0</version></versions><lastUpdated>20260101000000</lastUpdated></versioning>"
                + "</metadata>";
        String metadataPath = "/dom--repo/com/example/a/maven-metadata.xml";
        AtomicReference<byte[]> putBody = new AtomicReference<>();
        server.createContext(metadataPath, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, existing);
            } else if (metadataPath.equals(exchange.getRequestURI().getPath())) {
                putBody.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, "");
            } else {
                // This context path-matches the checksum siblings this emulator now also
                // rewrites alongside the metadata file itself (see
                // ReposiliteSidecarClient#recordVersionDisposed), so only the exact metadata
                // path's own PUT is captured into putBody.
                respond(exchange, 200, "");
            }
        });

        client.recordVersionDisposed("dom--repo", "com.example", "a", "1.0");

        String xml = new String(putBody.get(), StandardCharsets.UTF_8);
        assertEquals(List.of("2.0"), XmlParser.extractAll(xml, "version"));
        assertEquals("2.0", XmlParser.extractFirst(xml, "latest", null));
        assertEquals("2.0", XmlParser.extractFirst(xml, "release", null));
    }

    /**
     * Removing the current {@code latest}/{@code release} version itself must recompute both from
     * whatever remains, in the same append order the metadata already preserves: the last
     * surviving entry becomes the new {@code latest}, and the last surviving non-{@code -SNAPSHOT}
     * entry becomes the new {@code release} (here, that is the same entry since neither remaining
     * version is a snapshot).
     */
    @Test
    void recordVersionDisposedRecomputesLatestAndReleaseWhenTheDisposedVersionWasTheLatest() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        String existing = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>3.0</latest><release>3.0</release><versions><version>1.0</version>"
                + "<version>2.0</version><version>3.0</version></versions>"
                + "<lastUpdated>20260101000000</lastUpdated></versioning></metadata>";
        String metadataPath = "/dom--repo/com/example/a/maven-metadata.xml";
        AtomicReference<byte[]> putBody = new AtomicReference<>();
        server.createContext(metadataPath, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, existing);
            } else if (metadataPath.equals(exchange.getRequestURI().getPath())) {
                putBody.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, "");
            } else {
                // This context path-matches the checksum siblings this emulator now also
                // rewrites alongside the metadata file itself (see
                // ReposiliteSidecarClient#recordVersionDisposed), so only the exact metadata
                // path's own PUT is captured into putBody.
                respond(exchange, 200, "");
            }
        });

        client.recordVersionDisposed("dom--repo", "com.example", "a", "3.0");

        String xml = new String(putBody.get(), StandardCharsets.UTF_8);
        assertEquals(List.of("1.0", "2.0"), XmlParser.extractAll(xml, "version"));
        assertEquals("2.0", XmlParser.extractFirst(xml, "latest", null));
        assertEquals("2.0", XmlParser.extractFirst(xml, "release", null));
    }

    /**
     * {@code release} skips back past a surviving {@code -SNAPSHOT} entry to the last real release,
     * the same rule {@link #recordVersionAvailableLeavesTheExistingReleaseUntouchedForASnapshotVersion}
     * already proves for the add path.
     */
    @Test
    void recordVersionDisposedSkipsASnapshotWhenRecomputingRelease() {
        settingsBody.set("{\"repositories\":[{\"id\":\"dom--repo\",\"visibility\":\"PUBLIC\",\"redeployment\":false}]}");
        server.createContext("/api/settings/domain/maven", exchange -> respond(exchange, 200, settingsBody.get()));
        String existing = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>2.0-SNAPSHOT</latest><release>1.0</release><versions><version>1.0</version>"
                + "<version>2.0-SNAPSHOT</version></versions><lastUpdated>20260101000000</lastUpdated>"
                + "</versioning></metadata>";
        String metadataPath = "/dom--repo/com/example/a/maven-metadata.xml";
        AtomicReference<byte[]> putBody = new AtomicReference<>();
        server.createContext(metadataPath, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, existing);
            } else if (metadataPath.equals(exchange.getRequestURI().getPath())) {
                putBody.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, "");
            } else {
                // This context path-matches the checksum siblings this emulator now also
                // rewrites alongside the metadata file itself (see
                // ReposiliteSidecarClient#recordVersionDisposed), so only the exact metadata
                // path's own PUT is captured into putBody.
                respond(exchange, 200, "");
            }
        });

        client.recordVersionDisposed("dom--repo", "com.example", "a", "1.0");

        String xml = new String(putBody.get(), StandardCharsets.UTF_8);
        assertEquals(List.of("2.0-SNAPSHOT"), XmlParser.extractAll(xml, "version"));
        assertEquals("2.0-SNAPSHOT", XmlParser.extractFirst(xml, "latest", null));
        assertNull(XmlParser.extractFirst(xml, "release", null), "no real release survives, so release must be "
                + "omitted rather than naming a snapshot");
    }

    /**
     * Raised by Greptile's review: a real {@code mvn deploy} always also PUTs {@code
     * maven-metadata.xml.sha1}/{@code .md5}, so {@link
     * io.github.hectorvent.floci.services.codeartifact.ReposiliteSidecarClient#recordVersionDisposed}'s
     * own rewrite of the metadata must carry fresh checksums of whatever it just wrote, not leave
     * the pre-dispose checksums standing over content that no longer matches them.
     */
    @Test
    void recordVersionDisposedRewritesTheSha1AndMd5ChecksumsToMatchTheNewMetadata() {
        String existing = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>2.0</latest><release>2.0</release><versions><version>1.0</version>"
                + "<version>2.0</version></versions><lastUpdated>20260101000000</lastUpdated></versioning>"
                + "</metadata>";
        String metadataPath = "/dom--repo/com/example/a/maven-metadata.xml";
        AtomicReference<byte[]> putBody = new AtomicReference<>();
        AtomicReference<byte[]> sha1Body = new AtomicReference<>();
        AtomicReference<byte[]> md5Body = new AtomicReference<>();
        server.createContext(metadataPath, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, existing);
            } else if (metadataPath.equals(exchange.getRequestURI().getPath())) {
                putBody.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, "");
            } else {
                respond(exchange, 200, "");
            }
        });
        server.createContext(metadataPath + ".sha1", exchange -> {
            sha1Body.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "");
        });
        server.createContext(metadataPath + ".md5", exchange -> {
            md5Body.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "");
        });

        client.recordVersionDisposed("dom--repo", "com.example", "a", "1.0");

        assertEquals(Digests.sha1Hex(putBody.get()), new String(sha1Body.get(), StandardCharsets.UTF_8));
        assertEquals(Digests.md5Hex(putBody.get()), new String(md5Body.get(), StandardCharsets.UTF_8));
    }

    /** The counterpart to the rewrite case above: the metadata PUT itself failing must not be swallowed. */
    @Test
    void recordVersionDisposedThrowsWhenTheMetadataRewriteItselfFails() {
        String existing = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>2.0</latest><release>2.0</release><versions><version>1.0</version>"
                + "<version>2.0</version></versions><lastUpdated>20260101000000</lastUpdated></versioning>"
                + "</metadata>";
        server.createContext("/dom--repo/com/example/a/maven-metadata.xml", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, existing);
            } else {
                respond(exchange, 500, "");
            }
        });

        assertThrows(IllegalStateException.class,
                () -> client.recordVersionDisposed("dom--repo", "com.example", "a", "1.0"));
    }

    /**
     * Greptile's review again: {@link
     * io.github.hectorvent.floci.services.codeartifact.ReposiliteSidecarClient#recordVersionDisposed}
     * also removes the two checksum siblings when the metadata file itself is removed outright
     * (every version disposed), not just the metadata file.
     */
    @Test
    void recordVersionDisposedDeletesTheChecksumSiblingsWhenTheMetadataFileIsDeleted() {
        String existing = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>1.0</latest><release>1.0</release><versions><version>1.0</version></versions>"
                + "<lastUpdated>20260101000000</lastUpdated></versioning></metadata>";
        String metadataPath = "/dom--repo/com/example/a/maven-metadata.xml";
        List<String> deletedPaths = new CopyOnWriteArrayList<>();
        server.createContext(metadataPath, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, existing);
            } else {
                deletedPaths.add(exchange.getRequestURI().getPath());
                respond(exchange, 200, "");
            }
        });
        server.createContext(metadataPath + ".sha1", exchange -> {
            // deleteArtifactIfPresent checks existence with a HEAD first; only the real DELETE
            // that follows is the one this test cares about recording.
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deletedPaths.add(exchange.getRequestURI().getPath());
            }
            respond(exchange, 200, "");
        });
        server.createContext(metadataPath + ".md5", exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deletedPaths.add(exchange.getRequestURI().getPath());
            }
            respond(exchange, 200, "");
        });

        client.recordVersionDisposed("dom--repo", "com.example", "a", "1.0");

        assertThat(deletedPaths, containsInAnyOrder(metadataPath, metadataPath + ".sha1", metadataPath + ".md5"));
    }

    /** A checksum sibling missing entirely (never pushed, or a sidecar restored from an older snapshot) is a no-op. */
    @Test
    void recordVersionDisposedToleratesAMissingChecksumSiblingOnDelete() {
        String existing = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>1.0</latest><release>1.0</release><versions><version>1.0</version></versions>"
                + "<lastUpdated>20260101000000</lastUpdated></versioning></metadata>";
        String metadataPath = "/dom--repo/com/example/a/maven-metadata.xml";
        server.createContext(metadataPath, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, existing);
            } else {
                respond(exchange, 200, "");
            }
        });
        server.createContext(metadataPath + ".sha1", exchange -> respond(exchange, 404, ""));
        server.createContext(metadataPath + ".md5", exchange -> respond(exchange, 404, ""));

        assertDoesNotThrow(() -> client.recordVersionDisposed("dom--repo", "com.example", "a", "1.0"));
    }

    /**
     * Raised by Greptile's review: a HEAD failing with something other than a confirmed 404 (a
     * genuine sidecar error, not a clean "it's not there" answer) must not be treated the same as
     * a confirmed-absent checksum; silently skipping the delete there would report a successful
     * disposal while the sidecar's own real error was never surfaced or retried.
     */
    @Test
    void recordVersionDisposedThrowsWhenTheChecksumExistenceCheckFails() {
        String existing = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>1.0</latest><release>1.0</release><versions><version>1.0</version></versions>"
                + "<lastUpdated>20260101000000</lastUpdated></versioning></metadata>";
        String metadataPath = "/dom--repo/com/example/a/maven-metadata.xml";
        server.createContext(metadataPath, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, existing);
            } else {
                respond(exchange, 200, "");
            }
        });
        server.createContext(metadataPath + ".sha1", exchange -> respond(exchange, 500, ""));
        server.createContext(metadataPath + ".md5", exchange -> respond(exchange, 200, ""));

        assertThrows(IllegalStateException.class,
                () -> client.recordVersionDisposed("dom--repo", "com.example", "a", "1.0"));
    }

    /**
     * Raised by Greptile's review: a retry whose first attempt already deleted the metadata file
     * itself, but then failed on exactly a checksum delete, used to hit the {@code existing.isEmpty()}
     * early return and report success leaving that checksum behind, since there was no metadata
     * left for it to compare against. The checksum siblings are now cleaned up on this path too.
     */
    @Test
    void recordVersionDisposedCleansUpLingeringChecksumsEvenWhenTheMetadataFileIsAlreadyGone() {
        String metadataPath = "/dom--repo/com/example/a/maven-metadata.xml";
        List<String> deletedPaths = new CopyOnWriteArrayList<>();
        server.createContext(metadataPath, exchange -> respond(exchange, 404, ""));
        server.createContext(metadataPath + ".sha1", exchange -> {
            // deleteArtifactIfPresent checks existence with a HEAD first; only the real DELETE
            // that follows is the one this test cares about recording.
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deletedPaths.add(exchange.getRequestURI().getPath());
            }
            respond(exchange, 200, "");
        });
        server.createContext(metadataPath + ".md5", exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deletedPaths.add(exchange.getRequestURI().getPath());
            }
            respond(exchange, 200, "");
        });

        client.recordVersionDisposed("dom--repo", "com.example", "a", "1.0");

        assertThat(deletedPaths, containsInAnyOrder(metadataPath + ".sha1", metadataPath + ".md5"));
    }

    /**
     * Raised by Greptile's review: {@code fetchArtifact} itself returns empty for every non-200
     * response, 500 included, which must not be read as "the metadata file is confirmed gone."
     * A transient read failure here must surface, not silently report a successful disposal while
     * the metadata on disk still lists the version this was supposed to remove.
     */
    @Test
    void recordVersionDisposedThrowsWhenTheMetadataReadFails() {
        String metadataPath = "/dom--repo/com/example/a/maven-metadata.xml";
        server.createContext(metadataPath, exchange -> respond(exchange, 500, ""));

        assertThrows(IllegalStateException.class,
                () -> client.recordVersionDisposed("dom--repo", "com.example", "a", "1.0"));
    }

    /**
     * A metadata file that would end up naming no versions at all is deleted outright, matching
     * how a package that never had a version published has no metadata file at all.
     */
    @Test
    void recordVersionDisposedDeletesTheMetadataFileWhenNoVersionsAreLeft() {
        String existing = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>1.0</latest><release>1.0</release><versions><version>1.0</version></versions>"
                + "<lastUpdated>20260101000000</lastUpdated></versioning></metadata>";
        AtomicReference<String> deletedMethod = new AtomicReference<>();
        server.createContext("/dom--repo/com/example/a/maven-metadata.xml", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, existing);
            } else {
                deletedMethod.set(exchange.getRequestMethod());
                respond(exchange, 200, "");
            }
        });

        client.recordVersionDisposed("dom--repo", "com.example", "a", "1.0");

        assertEquals("DELETE", deletedMethod.get());
    }

    /**
     * A version the metadata never named to begin with (already removed by an earlier call, or
     * disposed before {@code maven-metadata.xml} ever existed) leaves the metadata itself
     * untouched: nothing is written back to that exact path.
     *
     * <p>Raised by Greptile's review: this same early return used to skip the checksum siblings
     * too, so a retry whose first attempt removed the version but then failed on exactly the
     * checksum write never got another chance to repair it. The checksums are now repaired every
     * time this returns here, idempotently (the metadata content itself did not change), not just
     * the one call that happened to also rewrite the metadata.
     */
    @Test
    void recordVersionDisposedIsANoOpWhenTheMetadataNeverNamedTheVersion() {
        String existing = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>1.0</latest><release>1.0</release><versions><version>1.0</version></versions>"
                + "<lastUpdated>20260101000000</lastUpdated></versioning></metadata>";
        String metadataPath = "/dom--repo/com/example/a/maven-metadata.xml";
        AtomicReference<String> writeMethod = new AtomicReference<>();
        AtomicReference<byte[]> sha1Body = new AtomicReference<>();
        AtomicReference<byte[]> md5Body = new AtomicReference<>();
        server.createContext(metadataPath, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, existing);
            } else if (metadataPath.equals(exchange.getRequestURI().getPath())) {
                writeMethod.set(exchange.getRequestMethod());
                respond(exchange, 200, "");
            } else {
                respond(exchange, 200, "");
            }
        });
        server.createContext(metadataPath + ".sha1", exchange -> {
            sha1Body.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "");
        });
        server.createContext(metadataPath + ".md5", exchange -> {
            md5Body.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "");
        });

        client.recordVersionDisposed("dom--repo", "com.example", "a", "9.9");

        assertNull(writeMethod.get(), "expected no PUT/DELETE of the metadata file itself");
        assertEquals(Digests.sha1Hex(existing.getBytes(StandardCharsets.UTF_8)),
                new String(sha1Body.get(), StandardCharsets.UTF_8));
        assertEquals(Digests.md5Hex(existing.getBytes(StandardCharsets.UTF_8)),
                new String(md5Body.get(), StandardCharsets.UTF_8));
    }

    /**
     * Raised by Greptile's review: {@code deployArtifact}'s own idempotent short-circuit (the
     * checksum already on the sidecar matches byte for byte, so nothing is actually sent) also
     * reports {@code wroteNewContent() == false}, the exact same signal a genuine failure gives.
     * A disposal retry whose {@code .sha1} already landed on an earlier attempt, but then failed
     * on {@code .md5}, must not treat that already-correct, unchanged {@code .sha1} as a failure
     * all over again: that would stop the retry before it ever reached {@code .md5} a second
     * time, getting stuck on the one checksum that was already fine.
     */
    @Test
    void recordVersionDisposedRepairsTheRemainingChecksumEvenWhenTheOtherAlreadyMatches() {
        String existing = "<metadata><groupId>com.example</groupId><artifactId>a</artifactId><versioning>"
                + "<latest>1.0</latest><release>1.0</release><versions><version>1.0</version></versions>"
                + "<lastUpdated>20260101000000</lastUpdated></versioning></metadata>";
        String metadataPath = "/dom--repo/com/example/a/maven-metadata.xml";
        String correctSha1 = Digests.sha1Hex(existing.getBytes(StandardCharsets.UTF_8));
        AtomicReference<byte[]> md5Body = new AtomicReference<>();
        server.createContext(metadataPath, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, existing);
            } else {
                respond(exchange, 200, "");
            }
        });
        server.createContext(metadataPath + ".sha1", exchange -> {
            // Already correct: a GET returns exactly what the retry is about to compute and PUT,
            // so deployArtifact's own existingArtifactMatches short-circuits (200, wroteNewContent
            // false) without ever reaching a real PUT here.
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, correctSha1);
            } else {
                respond(exchange, 200, "");
            }
        });
        server.createContext(metadataPath + ".md5", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 404, "");
            } else {
                md5Body.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, "");
            }
        });

        assertDoesNotThrow(() -> client.recordVersionDisposed("dom--repo", "com.example", "a", "9.9"));

        assertEquals(Digests.md5Hex(existing.getBytes(StandardCharsets.UTF_8)),
                new String(md5Body.get(), StandardCharsets.UTF_8));
    }

    /**
     * Raised by hectorvent's review: every CodeArtifact repository gets a maven sidecar id at
     * creation regardless of whether it is ever actually used through maven, so {@code
     * ListPackages} with no format filter must not start the one shared Reposilite container just
     * to walk a repository that has never used maven. No context is registered for {@code
     * /api/maven/details/dom--repo/} at all here: a real walk would have nothing to connect to and
     * throw.
     */
    @Test
    void listPackagesReturnsEmptyWithoutStartingTheSharedContainerThatWasNeverStarted() {
        when(manager.isStarted()).thenReturn(false);

        List<RepositorySidecarManager.PackageIdentifier> packages = client.listPackages("dom--repo", "dom", "repo");

        assertThat(packages, hasSize(0));
    }

    /**
     * Raised by Greptile's review: {@code com/foo} is both {@code com:foo}'s own version directory
     * ({@code 1.0.0}) and a further groupId segment for {@code com.foo:bar}'s own package
     * ({@code bar}). {@code bar} is listed before {@code 1.0.0} here deliberately, since an earlier
     * version of {@link ReposiliteSidecarClient#listPackages} peeked only the first child, so with
     * {@code bar} first it fell through to treating every child (including the real version
     * directory {@code 1.0.0}) as a further groupId segment, recursed into {@code 1.0.0} where it
     * found no subdirectories and silently returned, and never recorded {@code com:foo} at all.
     */
    @Test
    void listPackagesFindsBothAPackageAndASiblingGroupSharingItsDirectory() {
        server.createContext("/api/maven/details/dom--repo/",
                exchange -> respond(exchange, 200, "{\"name\":\"dom--repo\",\"type\":\"DIRECTORY\",\"files\":["
                        + "{\"name\":\"com\",\"type\":\"DIRECTORY\"}]}"));
        server.createContext("/api/maven/details/dom--repo/com/",
                exchange -> respond(exchange, 200, "{\"name\":\"com\",\"type\":\"DIRECTORY\",\"files\":["
                        + "{\"name\":\"foo\",\"type\":\"DIRECTORY\"}]}"));
        server.createContext("/api/maven/details/dom--repo/com/foo/",
                exchange -> respond(exchange, 200, "{\"name\":\"foo\",\"type\":\"DIRECTORY\",\"files\":["
                        + "{\"name\":\"bar\",\"type\":\"DIRECTORY\"},"
                        + "{\"name\":\"1.0.0\",\"type\":\"DIRECTORY\"}]}"));
        server.createContext("/api/maven/details/dom--repo/com/foo/1.0.0/",
                exchange -> respond(exchange, 200, "{\"name\":\"1.0.0\",\"type\":\"DIRECTORY\",\"files\":["
                        + "{\"name\":\"foo-1.0.0.jar\",\"type\":\"FILE\"}]}"));
        server.createContext("/api/maven/details/dom--repo/com/foo/bar/",
                exchange -> respond(exchange, 200, "{\"name\":\"bar\",\"type\":\"DIRECTORY\",\"files\":["
                        + "{\"name\":\"1.0.0\",\"type\":\"DIRECTORY\"}]}"));
        server.createContext("/api/maven/details/dom--repo/com/foo/bar/1.0.0/",
                exchange -> respond(exchange, 200, "{\"name\":\"1.0.0\",\"type\":\"DIRECTORY\",\"files\":["
                        + "{\"name\":\"bar-1.0.0.jar\",\"type\":\"FILE\"}]}"));

        List<RepositorySidecarManager.PackageIdentifier> packages = client.listPackages("dom--repo", "dom", "repo");

        assertThat(packages, containsInAnyOrder(
                new RepositorySidecarManager.PackageIdentifier("com", "foo"),
                new RepositorySidecarManager.PackageIdentifier("com.foo", "bar")));
    }

    /**
     * Raised by Greptile's review: {@code com/example/tool} has zero version directories of its
     * own (a native PUT straight to its {@code maven-metadata.xml}, with no real version content
     * ever written under it, the same artifact-level-only shape {@code recordVersionAvailable}'s
     * own javadoc already describes), so peeking it from its parent sees no subdirectories at all
     * and, before this fix, treated that as proof the parent ({@code com:example}) was the real
     * package, recording a package that does not exist and skipping the real one,
     * {@code com.example:tool}, entirely.
     */
    @Test
    void listPackagesRecognizesAnArtifactDirectoryWithNoVersionsOfItsOwnThroughItsOwnMetadata() {
        server.createContext("/api/maven/details/dom--repo/",
                exchange -> respond(exchange, 200, "{\"name\":\"dom--repo\",\"type\":\"DIRECTORY\",\"files\":["
                        + "{\"name\":\"com\",\"type\":\"DIRECTORY\"}]}"));
        server.createContext("/api/maven/details/dom--repo/com/",
                exchange -> respond(exchange, 200, "{\"name\":\"com\",\"type\":\"DIRECTORY\",\"files\":["
                        + "{\"name\":\"example\",\"type\":\"DIRECTORY\"}]}"));
        server.createContext("/api/maven/details/dom--repo/com/example/",
                exchange -> respond(exchange, 200, "{\"name\":\"example\",\"type\":\"DIRECTORY\",\"files\":["
                        + "{\"name\":\"tool\",\"type\":\"DIRECTORY\"}]}"));
        server.createContext("/api/maven/details/dom--repo/com/example/tool/",
                exchange -> respond(exchange, 200, "{\"name\":\"tool\",\"type\":\"DIRECTORY\",\"files\":["
                        + "{\"name\":\"maven-metadata.xml\",\"type\":\"FILE\"}]}"));

        List<RepositorySidecarManager.PackageIdentifier> packages = client.listPackages("dom--repo", "dom", "repo");

        assertThat(packages, containsInAnyOrder(new RepositorySidecarManager.PackageIdentifier("com.example",
                "tool")));
    }

    /**
     * Raised by Greptile's review of the fix above: a real Maven snapshot version directory also
     * holds its own {@code maven-metadata.xml} (listing that version's timestamped builds),
     * alongside its real artifact files, so excluding every {@code maven-metadata.xml}-holding
     * child from being version-shaped, as that fix did, wrongly recursed into {@code
     * 1.0-SNAPSHOT} as though it were a further groupId segment, adding the nonexistent package
     * {@code com.example.tool:1.0-SNAPSHOT} instead of the real one, {@code com.example:tool}.
     */
    @Test
    void listPackagesTreatsASnapshotVersionDirectoryAsVersionShapedDespiteItsOwnMetadata() {
        server.createContext("/api/maven/details/dom--repo/",
                exchange -> respond(exchange, 200, "{\"name\":\"dom--repo\",\"type\":\"DIRECTORY\",\"files\":["
                        + "{\"name\":\"com\",\"type\":\"DIRECTORY\"}]}"));
        server.createContext("/api/maven/details/dom--repo/com/",
                exchange -> respond(exchange, 200, "{\"name\":\"com\",\"type\":\"DIRECTORY\",\"files\":["
                        + "{\"name\":\"example\",\"type\":\"DIRECTORY\"}]}"));
        server.createContext("/api/maven/details/dom--repo/com/example/",
                exchange -> respond(exchange, 200, "{\"name\":\"example\",\"type\":\"DIRECTORY\",\"files\":["
                        + "{\"name\":\"tool\",\"type\":\"DIRECTORY\"}]}"));
        server.createContext("/api/maven/details/dom--repo/com/example/tool/",
                exchange -> respond(exchange, 200, "{\"name\":\"tool\",\"type\":\"DIRECTORY\",\"files\":["
                        + "{\"name\":\"1.0-SNAPSHOT\",\"type\":\"DIRECTORY\"}]}"));
        server.createContext("/api/maven/details/dom--repo/com/example/tool/1.0-SNAPSHOT/",
                exchange -> respond(exchange, 200, "{\"name\":\"1.0-SNAPSHOT\",\"type\":\"DIRECTORY\",\"files\":["
                        + "{\"name\":\"maven-metadata.xml\",\"type\":\"FILE\"},"
                        + "{\"name\":\"maven-metadata.xml.sha1\",\"type\":\"FILE\"},"
                        + "{\"name\":\"tool-1.0-SNAPSHOT.jar\",\"type\":\"FILE\"}]}"));

        List<RepositorySidecarManager.PackageIdentifier> packages = client.listPackages("dom--repo", "dom", "repo");

        assertThat(packages, containsInAnyOrder(new RepositorySidecarManager.PackageIdentifier("com.example",
                "tool")));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
