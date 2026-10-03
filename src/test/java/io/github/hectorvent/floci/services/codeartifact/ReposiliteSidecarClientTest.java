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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
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
    void deployArtifactShortCircuitsToTwoHundredWithoutPuttingWhenContentIsByteIdentical() {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        List<String> methodsSeen = new CopyOnWriteArrayList<>();
        server.createContext("/dom--repo/com/example/a/1.0/a-1.0.jar", exchange -> {
            methodsSeen.add(exchange.getRequestMethod());
            respond(exchange, 200, "hello");
        });

        int status = client.deployArtifact("dom--repo", "com/example/a/1.0/a-1.0.jar", content);

        assertThat(status, is(200));
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

        int status = client.deployArtifact("dom--repo", "com/example/a/1.0/a-1.0.jar",
                "hello".getBytes(StandardCharsets.UTF_8));

        assertThat(status, is(409));
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

        int status = client.deployArtifact("dom--repo", "com/example/a/1.0/a-1.0.jar",
                "hello".getBytes(StandardCharsets.UTF_8));

        assertThat(status, is(200));
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

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
