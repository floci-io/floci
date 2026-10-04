package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.AuthorizationTokenScope;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for routing npm registry traffic through Floci's data plane to the per-repository
 * Verdaccio container. {@link VerdaccioSidecarManager} is mocked throughout, so no container ever
 * actually starts; the round-trip proxy tests point it at a fake upstream HTTP server instead. The
 * real container-creation path (config injection, {@code VERDACCIO_PUBLIC_URL}) is covered by the
 * Docker-gated npm client integration test.
 */
class CodeArtifactNpmDataPlaneTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DOMAIN = "dom";
    private static final String REPOSITORY = "repo";
    private static final String NPM_REPOSITORY_ID = "npm-repo-id-1";

    private Vertx vertx;
    private HttpServer upstream;
    private HttpServer dataPlane;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        client = vertx.createHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dataPlane != null) {
            dataPlane.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
        if (upstream != null) {
            upstream.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void requestForParsesDomainRepositoryAndRest() {
        CodeArtifactNpmDataPlane.NpmRequest request =
                CodeArtifactNpmDataPlane.requestFor("/codeartifact/npm/dom/repo/lodash").orElseThrow();
        assertEquals("dom", request.domain());
        assertEquals("repo", request.repository());
        assertEquals("/lodash", request.rest());
    }

    @Test
    void requestForDefaultsToRootForTheBareRepositoryPath() {
        CodeArtifactNpmDataPlane.NpmRequest request =
                CodeArtifactNpmDataPlane.requestFor("/codeartifact/npm/dom/repo").orElseThrow();
        assertEquals("/", request.rest());
    }

    @Test
    void requestForRejectsAPathOutsideTheNpmPrefix() {
        assertTrue(CodeArtifactNpmDataPlane.requestFor("/codeartifact/maven/dom/repo/x").isEmpty());
    }

    @Test
    void missingTokenIsRejectedWithoutStartingAContainer() throws Exception {
        CodeArtifactService service = mock(CodeArtifactService.class);
        VerdaccioSidecarManager verdaccioManager = mock(VerdaccioSidecarManager.class);
        startDataPlane(service, verdaccioManager);

        HttpResponse response = get("/codeartifact/npm/" + DOMAIN + "/" + REPOSITORY + "/lodash", null);

        assertEquals(401, response.statusCode());
        assertEquals("Bearer", response.headers().get("www-authenticate"));
    }

    @Test
    void disabledCodeArtifactServiceIsNotServedByThisRoute() throws Exception {
        CodeArtifactService service = mock(CodeArtifactService.class);
        VerdaccioSidecarManager verdaccioManager = mock(VerdaccioSidecarManager.class);
        startDataPlane(service, verdaccioManager, false);

        HttpResponse response = get("/codeartifact/npm/" + DOMAIN + "/" + REPOSITORY + "/lodash", "good-token");

        assertEquals(404, response.statusCode());
        verifyNoInteractions(service, verdaccioManager);
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        CodeArtifactService service = mock(CodeArtifactService.class);
        when(service.resolveAuthorizationToken(anyString(), anyString())).thenReturn(Optional.empty());
        VerdaccioSidecarManager verdaccioManager = mock(VerdaccioSidecarManager.class);
        startDataPlane(service, verdaccioManager);

        HttpResponse response = get("/codeartifact/npm/" + DOMAIN + "/" + REPOSITORY + "/lodash", "not-a-real-token");

        assertEquals(401, response.statusCode());
    }

    @Test
    void unknownRepositoryIsNotFound() throws Exception {
        CodeArtifactService service = mock(CodeArtifactService.class);
        when(service.resolveAuthorizationToken("good-token", DOMAIN))
                .thenReturn(Optional.of(new AuthorizationTokenScope("000000000000", "us-east-1")));
        when(service.ensureFormatContainerId("npm", "us-east-1", DOMAIN, "000000000000", REPOSITORY))
                .thenThrow(new AwsException("ResourceNotFoundException", "no such repository", 404));
        VerdaccioSidecarManager verdaccioManager = mock(VerdaccioSidecarManager.class);
        startDataPlane(service, verdaccioManager);

        HttpResponse response = get("/codeartifact/npm/" + DOMAIN + "/" + REPOSITORY + "/lodash", "good-token");

        assertEquals(404, response.statusCode());
    }

    @Test
    void aValidRequestIsProxiedToTheRepositorysContainerWithTheClientAuthorizationStripped() throws Exception {
        AtomicReference<String> upstreamPath = new AtomicReference<>();
        AtomicReference<String> upstreamAuthHeader = new AtomicReference<>();
        upstream = vertx.createHttpServer()
                .requestHandler(request -> {
                    upstreamPath.set(request.path() + (request.query() != null ? "?" + request.query() : ""));
                    upstreamAuthHeader.set(request.getHeader("Authorization"));
                    request.response().putHeader("Content-Type", "application/json")
                            .setStatusCode(200).end("{\"name\":\"lodash\"}");
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        CodeArtifactService service = mock(CodeArtifactService.class);
        when(service.resolveAuthorizationToken("good-token", DOMAIN))
                .thenReturn(Optional.of(new AuthorizationTokenScope("000000000000", "us-east-1")));
        when(service.ensureFormatContainerId("npm", "us-east-1", DOMAIN, "000000000000", REPOSITORY))
                .thenReturn(NPM_REPOSITORY_ID);
        VerdaccioSidecarManager verdaccioManager = mock(VerdaccioSidecarManager.class);
        when(verdaccioManager.ensureReady(eq(NPM_REPOSITORY_ID), anyString()))
                .thenReturn("http://127.0.0.1:" + upstream.actualPort());
        startDataPlane(service, verdaccioManager);

        HttpResponse response = get("/codeartifact/npm/" + DOMAIN + "/" + REPOSITORY + "/lodash", "good-token");

        assertEquals(200, response.statusCode());
        assertEquals("{\"name\":\"lodash\"}", response.body());
        assertEquals("/lodash", upstreamPath.get());
        assertNull(upstreamAuthHeader.get());
    }

    @Test
    void aPublishPutStreamsTheBodyThrough() throws Exception {
        AtomicReference<String> upstreamBody = new AtomicReference<>();
        AtomicReference<HttpMethod> upstreamMethod = new AtomicReference<>();
        upstream = vertx.createHttpServer()
                .requestHandler(request -> {
                    upstreamMethod.set(request.method());
                    request.bodyHandler(body -> {
                        upstreamBody.set(body.toString());
                        request.response().setStatusCode(201).end();
                    });
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        CodeArtifactService service = mock(CodeArtifactService.class);
        when(service.resolveAuthorizationToken("good-token", DOMAIN))
                .thenReturn(Optional.of(new AuthorizationTokenScope("000000000000", "us-east-1")));
        when(service.ensureFormatContainerId("npm", "us-east-1", DOMAIN, "000000000000", REPOSITORY))
                .thenReturn(NPM_REPOSITORY_ID);
        VerdaccioSidecarManager verdaccioManager = mock(VerdaccioSidecarManager.class);
        when(verdaccioManager.ensureReady(eq(NPM_REPOSITORY_ID), anyString()))
                .thenReturn("http://127.0.0.1:" + upstream.actualPort());
        startDataPlane(service, verdaccioManager);

        HttpResponse response = put("/codeartifact/npm/" + DOMAIN + "/" + REPOSITORY + "/my-pkg", "good-token",
                "{\"name\":\"my-pkg\"}");

        assertEquals(201, response.statusCode());
        assertEquals(HttpMethod.PUT, upstreamMethod.get());
        assertEquals("{\"name\":\"my-pkg\"}", upstreamBody.get());
    }

    /**
     * Confirmed against the exact pinned Verdaccio image that
     * republishing a just-published, byte-identical tarball comes back {@code 409}, never a
     * success, unlike real AWS CodeArtifact's own documented idempotent-republish contract (same
     * rule Maven and PyPI already implement). Verdaccio must never see this PUT at all: the
     * preflight has to confirm the match and respond itself before ever reaching the backend.
     */
    @Test
    void aRepublishOfByteIdenticalContentShortCircuitsWithoutReachingTheBackend() throws Exception {
        byte[] content = "tarball bytes".getBytes(StandardCharsets.UTF_8);
        AtomicReference<Boolean> upstreamReceivedPut = new AtomicReference<>(false);
        upstream = vertx.createHttpServer()
                .requestHandler(request -> {
                    if (request.method() == HttpMethod.PUT) {
                        upstreamReceivedPut.set(true);
                    }
                    request.response().setStatusCode(500).end();
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        CodeArtifactService service = mock(CodeArtifactService.class);
        when(service.resolveAuthorizationToken("good-token", DOMAIN))
                .thenReturn(Optional.of(new AuthorizationTokenScope("000000000000", "us-east-1")));
        when(service.ensureFormatContainerId("npm", "us-east-1", DOMAIN, "000000000000", REPOSITORY))
                .thenReturn(NPM_REPOSITORY_ID);
        VerdaccioSidecarManager verdaccioManager = mock(VerdaccioSidecarManager.class);
        when(verdaccioManager.ensureReady(eq(NPM_REPOSITORY_ID), anyString()))
                .thenReturn("http://127.0.0.1:" + upstream.actualPort());
        givenStoredVersion(verdaccioManager, null, "my-pkg", "1.0.0", "my-pkg-1.0.0.tgz", content, Map.of());
        startDataPlane(service, verdaccioManager);

        HttpResponse response = put("/codeartifact/npm/" + DOMAIN + "/" + REPOSITORY + "/my-pkg", "good-token",
                publishEnvelope("my-pkg", "1.0.0", "my-pkg-1.0.0.tgz", content));

        assertEquals(200, response.statusCode());
        assertEquals(false, upstreamReceivedPut.get());
    }

    /**
     * Same finding, the other required half: a version that already exists but with genuinely
     * different content must still reach the backend and fail the way Verdaccio's own (correctly
     * strict) check says it should, not be silently treated the same as the identical-content case.
     */
    @Test
    void aRepublishOfDifferentContentStillForwardsToTheBackendAndItsConflictResponseWins() throws Exception {
        byte[] existingContent = "old tarball bytes".getBytes(StandardCharsets.UTF_8);
        byte[] newContent = "new tarball bytes".getBytes(StandardCharsets.UTF_8);
        AtomicReference<Boolean> upstreamReceivedPut = new AtomicReference<>(false);
        upstream = vertx.createHttpServer()
                .requestHandler(request -> {
                    if (request.method() == HttpMethod.PUT) {
                        upstreamReceivedPut.set(true);
                    }
                    request.response().setStatusCode(409).end();
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        CodeArtifactService service = mock(CodeArtifactService.class);
        when(service.resolveAuthorizationToken("good-token", DOMAIN))
                .thenReturn(Optional.of(new AuthorizationTokenScope("000000000000", "us-east-1")));
        when(service.ensureFormatContainerId("npm", "us-east-1", DOMAIN, "000000000000", REPOSITORY))
                .thenReturn(NPM_REPOSITORY_ID);
        VerdaccioSidecarManager verdaccioManager = mock(VerdaccioSidecarManager.class);
        when(verdaccioManager.ensureReady(eq(NPM_REPOSITORY_ID), anyString()))
                .thenReturn("http://127.0.0.1:" + upstream.actualPort());
        givenStoredVersion(verdaccioManager, null, "my-pkg", "1.0.0", "my-pkg-1.0.0.tgz", existingContent, Map.of());
        startDataPlane(service, verdaccioManager);

        HttpResponse response = put("/codeartifact/npm/" + DOMAIN + "/" + REPOSITORY + "/my-pkg", "good-token",
                publishEnvelope("my-pkg", "1.0.0", "my-pkg-1.0.0.tgz", newContent));

        assertEquals(409, response.statusCode());
        assertEquals(true, upstreamReceivedPut.get());
    }

    /**
     * The non-republish baseline: a version Verdaccio has never seen before must always reach the
     * backend, same as today, since there is nothing yet to compare against.
     */
    @Test
    void aPublishOfAGenuinelyNewVersionStillForwardsToTheBackend() throws Exception {
        AtomicReference<Boolean> upstreamReceivedPut = new AtomicReference<>(false);
        upstream = vertx.createHttpServer()
                .requestHandler(request -> {
                    if (request.method() == HttpMethod.PUT) {
                        upstreamReceivedPut.set(true);
                    }
                    request.response().setStatusCode(201).end();
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        CodeArtifactService service = mock(CodeArtifactService.class);
        when(service.resolveAuthorizationToken("good-token", DOMAIN))
                .thenReturn(Optional.of(new AuthorizationTokenScope("000000000000", "us-east-1")));
        when(service.ensureFormatContainerId("npm", "us-east-1", DOMAIN, "000000000000", REPOSITORY))
                .thenReturn(NPM_REPOSITORY_ID);
        VerdaccioSidecarManager verdaccioManager = mock(VerdaccioSidecarManager.class);
        when(verdaccioManager.ensureReady(eq(NPM_REPOSITORY_ID), anyString()))
                .thenReturn("http://127.0.0.1:" + upstream.actualPort());
        startDataPlane(service, verdaccioManager);

        HttpResponse response = put("/codeartifact/npm/" + DOMAIN + "/" + REPOSITORY + "/my-pkg", "good-token",
                publishEnvelope("my-pkg", "1.0.0", "my-pkg-1.0.0.tgz", "fresh bytes".getBytes(StandardCharsets.UTF_8)));

        assertEquals(201, response.statusCode());
        assertEquals(true, upstreamReceivedPut.get());
    }

    /**
     * The identity used to look up an existing match comes from the URL the publish was sent to,
     * not the envelope's own {@code name} field. A mismatched body must not be checked against
     * another package's stored copy; the stub here holds a match for that other package, and the
     * PUT must still reach the backend.
     */
    @Test
    void aPublishEnvelopeNameThatDisagreesWithTheUrlIsNotTreatedAsAConfirmableRepublish() throws Exception {
        byte[] content = "tarball bytes".getBytes(StandardCharsets.UTF_8);
        AtomicReference<Boolean> upstreamReceivedPut = new AtomicReference<>(false);
        upstream = vertx.createHttpServer()
                .requestHandler(request -> {
                    if (request.method() == HttpMethod.PUT) {
                        upstreamReceivedPut.set(true);
                    }
                    request.response().setStatusCode(201).end();
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        CodeArtifactService service = mock(CodeArtifactService.class);
        when(service.resolveAuthorizationToken("good-token", DOMAIN))
                .thenReturn(Optional.of(new AuthorizationTokenScope("000000000000", "us-east-1")));
        when(service.ensureFormatContainerId("npm", "us-east-1", DOMAIN, "000000000000", REPOSITORY))
                .thenReturn(NPM_REPOSITORY_ID);
        VerdaccioSidecarManager verdaccioManager = mock(VerdaccioSidecarManager.class);
        when(verdaccioManager.ensureReady(eq(NPM_REPOSITORY_ID), anyString()))
                .thenReturn("http://127.0.0.1:" + upstream.actualPort());
        // A match exists for "other-pkg", the envelope's own (wrong) name, but the URL is
        // publishing to "my-pkg": this must never be consulted for this request at all.
        givenStoredVersion(verdaccioManager, null, "other-pkg", "1.0.0", "other-pkg-1.0.0.tgz", content, Map.of());
        startDataPlane(service, verdaccioManager);

        HttpResponse response = put("/codeartifact/npm/" + DOMAIN + "/" + REPOSITORY + "/my-pkg", "good-token",
                publishEnvelope("other-pkg", "1.0.0", "other-pkg-1.0.0.tgz", content));

        assertEquals(201, response.statusCode());
        assertEquals(true, upstreamReceivedPut.get());
    }

    /**
     * Confirms the URL-derived identity {@link #aPublishEnvelopeNameThatDisagreesWithTheUrlIsNotTreatedAsAConfirmableRepublish}
     * relies on actually decodes a scoped package's {@code %2F} correctly: the scope separator
     * arrives percent-encoded in the URL (Netty leaves it that way specifically so it is never
     * confused with a real path separator), so this exercises the one, targeted decode that
     * undoes it.
     */
    @Test
    void aRepublishOfAScopedPackageWithByteIdenticalContentShortCircuitsWithoutReachingTheBackend() throws Exception {
        byte[] content = "scoped tarball bytes".getBytes(StandardCharsets.UTF_8);
        AtomicReference<Boolean> upstreamReceivedPut = new AtomicReference<>(false);
        upstream = vertx.createHttpServer()
                .requestHandler(request -> {
                    if (request.method() == HttpMethod.PUT) {
                        upstreamReceivedPut.set(true);
                    }
                    request.response().setStatusCode(500).end();
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        CodeArtifactService service = mock(CodeArtifactService.class);
        when(service.resolveAuthorizationToken("good-token", DOMAIN))
                .thenReturn(Optional.of(new AuthorizationTokenScope("000000000000", "us-east-1")));
        when(service.ensureFormatContainerId("npm", "us-east-1", DOMAIN, "000000000000", REPOSITORY))
                .thenReturn(NPM_REPOSITORY_ID);
        VerdaccioSidecarManager verdaccioManager = mock(VerdaccioSidecarManager.class);
        when(verdaccioManager.ensureReady(eq(NPM_REPOSITORY_ID), anyString()))
                .thenReturn("http://127.0.0.1:" + upstream.actualPort());
        givenStoredVersion(verdaccioManager, "myscope", "my-pkg", "1.0.0", "my-pkg-1.0.0.tgz", content, Map.of());
        startDataPlane(service, verdaccioManager);

        HttpResponse response = put("/codeartifact/npm/" + DOMAIN + "/" + REPOSITORY + "/@myscope%2Fmy-pkg",
                "good-token", publishEnvelope("@myscope/my-pkg", "1.0.0", "my-pkg-1.0.0.tgz", content));

        assertEquals(200, response.statusCode());
        assertEquals(false, upstreamReceivedPut.get());
    }

    /**
     * The metadata can claim an integrity the stored bytes do not have, for instance a tarball that
     * was damaged or lost behind an intact packument. The retry must not be acknowledged on that claim.
     */
    @Test
    void aRepublishWhoseStoredTarballDoesNotHashToItsIntegrityFallsThroughToTheBackend() throws Exception {
        byte[] content = "tarball bytes".getBytes(StandardCharsets.UTF_8);
        AtomicReference<Boolean> upstreamReceivedPut = new AtomicReference<>(false);
        upstream = vertx.createHttpServer()
                .requestHandler(request -> {
                    if (request.method() == HttpMethod.PUT) {
                        upstreamReceivedPut.set(true);
                    }
                    request.response().setStatusCode(409).end();
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        CodeArtifactService service = mock(CodeArtifactService.class);
        when(service.resolveAuthorizationToken("good-token", DOMAIN))
                .thenReturn(Optional.of(new AuthorizationTokenScope("000000000000", "us-east-1")));
        when(service.ensureFormatContainerId("npm", "us-east-1", DOMAIN, "000000000000", REPOSITORY))
                .thenReturn(NPM_REPOSITORY_ID);
        VerdaccioSidecarManager verdaccioManager = mock(VerdaccioSidecarManager.class);
        when(verdaccioManager.ensureReady(eq(NPM_REPOSITORY_ID), anyString()))
                .thenReturn("http://127.0.0.1:" + upstream.actualPort());
        givenStoredVersion(verdaccioManager, null, "my-pkg", "1.0.0", "my-pkg-1.0.0.tgz", content, Map.of());
        when(verdaccioManager.storedTarballIntegrity(anyString(), isNull(), eq("my-pkg"), eq("my-pkg-1.0.0.tgz")))
                .thenReturn(Optional.of("sha512-" + Base64.getEncoder().encodeToString(new byte[64])));
        startDataPlane(service, verdaccioManager);

        HttpResponse response = put("/codeartifact/npm/" + DOMAIN + "/" + REPOSITORY + "/my-pkg", "good-token",
                publishEnvelope("my-pkg", "1.0.0", "my-pkg-1.0.0.tgz", content));

        assertEquals(409, response.statusCode());
        assertEquals(true, upstreamReceivedPut.get());
    }

    /**
     * Every "can't confirm an identical republish" path must fall through to the real backend
     * rather than short-circuit: malformed JSON, a version whose tarball has no matching attachment,
     * and a sidecar lookup that fails outright.
     */
    @Test
    void aPublishThatCannotBeConfirmedAsARepublishFallsThroughToTheBackend() throws Exception {
        assertForwardedWhenNotConfirmable("not json at all", verdaccioManager -> { });
    }

    @Test
    void aPublishWhoseVersionHasNoMatchingAttachmentFallsThroughToTheBackend() throws Exception {
        String envelopeWithoutAttachment = "{\"name\":\"my-pkg\",\"versions\":{\"1.0.0\":{\"name\":\"my-pkg\","
                + "\"version\":\"1.0.0\",\"dist\":{\"tarball\":\"http://ignored/my-pkg-1.0.0.tgz\"}}},"
                + "\"_attachments\":{}}";
        assertForwardedWhenNotConfirmable(envelopeWithoutAttachment, verdaccioManager ->
                givenStoredVersion(verdaccioManager, null, "my-pkg", "1.0.0", "my-pkg-1.0.0.tgz", new byte[0],
                        Map.of()));
    }

    @Test
    void aPublishWhoseSidecarLookupFailsFallsThroughToTheBackend() throws Exception {
        assertForwardedWhenNotConfirmable(
                publishEnvelope("my-pkg", "1.0.0", "my-pkg-1.0.0.tgz", "tarball bytes".getBytes(StandardCharsets.UTF_8)),
                verdaccioManager -> when(verdaccioManager.fetchPackageDocument(anyString(), isNull(), eq("my-pkg")))
                        .thenThrow(new IllegalStateException("sidecar unreachable")));
    }

    private void assertForwardedWhenNotConfirmable(String body, Consumer<VerdaccioSidecarManager> arrange)
            throws Exception {
        AtomicReference<Boolean> upstreamReceivedPut = new AtomicReference<>(false);
        upstream = vertx.createHttpServer()
                .requestHandler(request -> {
                    if (request.method() == HttpMethod.PUT) {
                        upstreamReceivedPut.set(true);
                    }
                    request.response().setStatusCode(201).end();
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        CodeArtifactService service = mock(CodeArtifactService.class);
        when(service.resolveAuthorizationToken("good-token", DOMAIN))
                .thenReturn(Optional.of(new AuthorizationTokenScope("000000000000", "us-east-1")));
        when(service.ensureFormatContainerId("npm", "us-east-1", DOMAIN, "000000000000", REPOSITORY))
                .thenReturn(NPM_REPOSITORY_ID);
        VerdaccioSidecarManager verdaccioManager = mock(VerdaccioSidecarManager.class);
        when(verdaccioManager.ensureReady(eq(NPM_REPOSITORY_ID), anyString()))
                .thenReturn("http://127.0.0.1:" + upstream.actualPort());
        arrange.accept(verdaccioManager);
        startDataPlane(service, verdaccioManager);

        HttpResponse response = put("/codeartifact/npm/" + DOMAIN + "/" + REPOSITORY + "/my-pkg", "good-token", body);

        assertEquals(201, response.statusCode());
        assertEquals(true, upstreamReceivedPut.get());
    }

    /**
     * Arranges a package that already exists on the backend: its metadata document (with
     * {@code storedTags} and one version whose tarball is {@code filename}) and that tarball's bytes.
     */
    private static void givenStoredVersion(VerdaccioSidecarManager verdaccioManager, String namespace, String name,
            String version, String filename, byte[] storedContent, Map<String, String> storedTags) {
        when(verdaccioManager.fetchPackageDocument(anyString(), eq(namespace), eq(name)))
                .thenReturn(Optional.of(storedDocument(name, version, filename, storedContent, storedTags)));
        when(verdaccioManager.storedTarballIntegrity(anyString(), eq(namespace), eq(name), eq(filename)))
                .thenReturn(Optional.of(integrity(storedContent)));
    }

    /**
     * A publish far beyond any in-memory size still gets the idempotent answer: the envelope is
     * spooled and hashed as a stream, so its size never has to fit on heap.
     */
    @Test
    void aLargeIdenticalRepublishIsStillConfirmedAsIdempotent() throws Exception {
        byte[] content = new byte[70 * 1024 * 1024];
        new Random(11).nextBytes(content);
        AtomicReference<Boolean> upstreamReceivedPut = new AtomicReference<>(false);
        upstream = vertx.createHttpServer()
                .requestHandler(request -> {
                    if (request.method() == HttpMethod.PUT) {
                        upstreamReceivedPut.set(true);
                    }
                    request.response().setStatusCode(409).end();
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        CodeArtifactService service = mock(CodeArtifactService.class);
        when(service.resolveAuthorizationToken("good-token", DOMAIN))
                .thenReturn(Optional.of(new AuthorizationTokenScope("000000000000", "us-east-1")));
        when(service.ensureFormatContainerId("npm", "us-east-1", DOMAIN, "000000000000", REPOSITORY))
                .thenReturn(NPM_REPOSITORY_ID);
        VerdaccioSidecarManager verdaccioManager = mock(VerdaccioSidecarManager.class);
        when(verdaccioManager.ensureReady(eq(NPM_REPOSITORY_ID), anyString()))
                .thenReturn("http://127.0.0.1:" + upstream.actualPort());
        givenStoredVersion(verdaccioManager, null, "big-pkg", "1.0.0", "big-pkg-1.0.0.tgz", content, Map.of());
        startDataPlane(service, verdaccioManager);

        HttpResponse response = put("/codeartifact/npm/" + DOMAIN + "/" + REPOSITORY + "/big-pkg", "good-token",
                publishEnvelope("big-pkg", "1.0.0", "big-pkg-1.0.0.tgz", content));

        assertEquals(200, response.statusCode());
        assertEquals(false, upstreamReceivedPut.get());
    }

    private static ObjectNode storedDocument(String name, String version, String filename, byte[] content,
            Map<String, String> storedTags) {
        ObjectNode document = JSON.createObjectNode();
        document.put("name", name);
        ObjectNode tags = document.putObject("dist-tags");
        storedTags.forEach(tags::put);
        ObjectNode dist = document.putObject("versions").putObject(version).putObject("dist");
        dist.put("tarball", "http://ignored/" + filename);
        dist.put("integrity", integrity(content));
        return document;
    }

    private static String integrity(byte[] content) {
        try {
            return "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse putUnchecked(String path, String body) {
        try {
            return put(path, "good-token", body);
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Four publishes hold their preflight permits while blocked in the sidecar lookup. A fifth
     * identical publish must wait for one rather than bypass the check: bypassing would send it
     * straight to Verdaccio, which answers 409 for an existing version, so the same republish would
     * succeed or fail depending on load. The upstream answers 409 so that a bypass would show up.
     */
    @Test
    void aPublishArrivingWhilePreflightsAreFullWaitsForAPermitInsteadOfBypassingTheCheck() throws Exception {
        byte[] content = "tarball bytes".getBytes(StandardCharsets.UTF_8);
        String envelope = publishEnvelope("my-pkg", "1.0.0", "my-pkg-1.0.0.tgz", content);
        AtomicReference<Boolean> upstreamReceivedPut = new AtomicReference<>(false);
        upstream = vertx.createHttpServer()
                .requestHandler(request -> {
                    if (request.method() == HttpMethod.PUT) {
                        upstreamReceivedPut.set(true);
                    }
                    request.response().setStatusCode(409).end();
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        CodeArtifactService service = mock(CodeArtifactService.class);
        when(service.resolveAuthorizationToken("good-token", DOMAIN))
                .thenReturn(Optional.of(new AuthorizationTokenScope("000000000000", "us-east-1")));
        when(service.ensureFormatContainerId("npm", "us-east-1", DOMAIN, "000000000000", REPOSITORY))
                .thenReturn(NPM_REPOSITORY_ID);
        VerdaccioSidecarManager verdaccioManager = mock(VerdaccioSidecarManager.class);
        when(verdaccioManager.ensureReady(eq(NPM_REPOSITORY_ID), anyString()))
                .thenReturn("http://127.0.0.1:" + upstream.actualPort());
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        ObjectNode document = storedDocument("my-pkg", "1.0.0", "my-pkg-1.0.0.tgz", content, Map.of());
        when(verdaccioManager.storedTarballIntegrity(anyString(), isNull(), eq("my-pkg"), eq("my-pkg-1.0.0.tgz")))
                .thenReturn(Optional.of(integrity(content)));
        when(verdaccioManager.fetchPackageDocument(anyString(), isNull(), eq("my-pkg"))).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Optional.of(document);
        });
        startDataPlane(service, verdaccioManager);

        String path = "/codeartifact/npm/" + DOMAIN + "/" + REPOSITORY + "/my-pkg";
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<CompletableFuture<HttpResponse>> held = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                held.add(CompletableFuture.supplyAsync(() -> putUnchecked(path, envelope), pool));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<HttpResponse> overflow = CompletableFuture.supplyAsync(
                    () -> putUnchecked(path, envelope), pool);

            assertThrows(TimeoutException.class, () -> overflow.get(300, TimeUnit.MILLISECONDS));
            release.countDown();

            for (CompletableFuture<HttpResponse> response : held) {
                assertEquals(200, response.get(10, TimeUnit.SECONDS).statusCode());
            }
            assertEquals(200, overflow.get(10, TimeUnit.SECONDS).statusCode());
            assertEquals(false, upstreamReceivedPut.get());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static String publishEnvelope(String name, String version, String filename, byte[] content) {
        return publishEnvelopeWithDistTags(name, version, filename, content, null);
    }

    private static String publishEnvelopeWithDistTags(String name, String version, String filename, byte[] content,
            String distTagsJson) {
        String base64Content = Base64.getEncoder().encodeToString(content);
        String distTags = distTagsJson == null ? "" : ",\"dist-tags\":" + distTagsJson;
        return "{\"name\":\"" + name + "\",\"versions\":{\"" + version + "\":{\"name\":\"" + name
                + "\",\"version\":\"" + version + "\",\"dist\":{\"tarball\":\"http://ignored/" + filename
                + "\",\"integrity\":\"" + integrity(content) + "\"}}}" + distTags + ",\"_attachments\":{\"" + filename
                + "\":{\"content_type\":\"application/octet-stream\","
                + "\"data\":\"" + base64Content + "\",\"length\":" + content.length + "}}}";
    }

    /**
     * Republishes byte-identical content whose envelope sends {@code distTagsJson}, against a
     * package whose tags Verdaccio already has as {@code storedTags}. The backend always answers 409
     * so a fall-through is visible as that response, and a short-circuit as no PUT reaching it.
     */
    private HttpResponse republishWithDistTags(String distTagsJson, Map<String, String> storedTags,
            AtomicReference<Boolean> upstreamReceivedPut) throws Exception {
        byte[] content = "tarball bytes".getBytes(StandardCharsets.UTF_8);
        upstream = vertx.createHttpServer()
                .requestHandler(request -> {
                    if (request.method() == HttpMethod.PUT) {
                        upstreamReceivedPut.set(true);
                    }
                    request.response().setStatusCode(409).end();
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        CodeArtifactService service = mock(CodeArtifactService.class);
        when(service.resolveAuthorizationToken("good-token", DOMAIN))
                .thenReturn(Optional.of(new AuthorizationTokenScope("000000000000", "us-east-1")));
        when(service.ensureFormatContainerId("npm", "us-east-1", DOMAIN, "000000000000", REPOSITORY))
                .thenReturn(NPM_REPOSITORY_ID);
        VerdaccioSidecarManager verdaccioManager = mock(VerdaccioSidecarManager.class);
        when(verdaccioManager.ensureReady(eq(NPM_REPOSITORY_ID), anyString()))
                .thenReturn("http://127.0.0.1:" + upstream.actualPort());
        givenStoredVersion(verdaccioManager, null, "my-pkg", "1.0.0", "my-pkg-1.0.0.tgz", content, storedTags);
        startDataPlane(service, verdaccioManager);

        return put("/codeartifact/npm/" + DOMAIN + "/" + REPOSITORY + "/my-pkg", "good-token",
                publishEnvelopeWithDistTags("my-pkg", "1.0.0", "my-pkg-1.0.0.tgz", content, distTagsJson));
    }

    @Test
    void aRepublishWhoseTagsAlreadyPointWhereItSendsThemStillShortCircuits() throws Exception {
        AtomicReference<Boolean> upstreamReceivedPut = new AtomicReference<>(false);
        HttpResponse response = republishWithDistTags("{\"latest\":\"1.0.0\"}", Map.of("latest", "1.0.0"),
                upstreamReceivedPut);

        assertEquals(200, response.statusCode());
        assertEquals(false, upstreamReceivedPut.get());
    }

    @Test
    void aRepublishThatWouldMoveATagToAnotherVersionFallsThroughToTheBackend() throws Exception {
        AtomicReference<Boolean> upstreamReceivedPut = new AtomicReference<>(false);
        HttpResponse response = republishWithDistTags("{\"latest\":\"1.0.0\"}", Map.of("latest", "0.9.0"),
                upstreamReceivedPut);

        assertEquals(409, response.statusCode());
        assertEquals(true, upstreamReceivedPut.get());
    }

    @Test
    void aRepublishThatAddsATagTheServerDoesNotHaveYetFallsThroughToTheBackend() throws Exception {
        AtomicReference<Boolean> upstreamReceivedPut = new AtomicReference<>(false);
        HttpResponse response = republishWithDistTags("{\"beta\":\"1.0.0\"}", Map.of("latest", "1.0.0"),
                upstreamReceivedPut);

        assertEquals(409, response.statusCode());
        assertEquals(true, upstreamReceivedPut.get());
    }

    private void startDataPlane(CodeArtifactService service, VerdaccioSidecarManager verdaccioManager)
            throws Exception {
        startDataPlane(service, verdaccioManager, true);
    }

    private void startDataPlane(CodeArtifactService service, VerdaccioSidecarManager verdaccioManager,
                                 boolean codeArtifactEnabled) throws Exception {
        when(verdaccioManager.publicUrl(DOMAIN, REPOSITORY)).thenReturn("http://localhost:4566/codeartifact/npm/"
                + DOMAIN + "/" + REPOSITORY + "/");
        ServiceConfigAccess serviceConfigAccess = mock(ServiceConfigAccess.class);
        when(serviceConfigAccess.isEnabled("codeartifact")).thenReturn(codeArtifactEnabled);
        Router router = Router.router(vertx);
        new CodeArtifactNpmDataPlane(service, verdaccioManager, serviceConfigAccess, vertx, new ObjectMapper())
                .register(router);
        dataPlane = vertx.createHttpServer().requestHandler(router)
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private HttpResponse get(String path, String bearerToken) throws Exception {
        return request(HttpMethod.GET, path, bearerToken, null);
    }

    private HttpResponse put(String path, String bearerToken, String body) throws Exception {
        return request(HttpMethod.PUT, path, bearerToken, body);
    }

    /**
     * Composes request, send and body-read into one Vert.x future chain rather than blocking with
     * {@code .get()} between each step: blocking to obtain the response and only then calling
     * {@link HttpClientResponse#body()} leaves a window, between the response arriving on the
     * event loop and this JUnit thread waking back up and attaching to it, where a small, fast
     * local response (this fake upstream's whole body arrives in one write) can finish delivering
     * before anything is listening for it, so {@code body()} sees an already-ended stream with
     * nothing left to replay. Chaining with {@code compose} attaches the body read from inside the
     * same event-loop callback that receives the response, before that window can ever open, and
     * leaves exactly one blocking {@code .get()} at the very end.
     */
    private HttpResponse request(HttpMethod method, String path, String bearerToken, String body) throws Exception {
        RequestOptions options = new RequestOptions()
                .setHost("127.0.0.1")
                .setPort(dataPlane.actualPort())
                .setMethod(method)
                .setURI(path);
        Future<HttpResponse> responseFuture = client.request(options)
                .compose(req -> {
                    if (bearerToken != null) {
                        req.putHeader("Authorization", "Bearer " + bearerToken);
                    }
                    return body != null ? req.send(body) : req.send();
                })
                .compose(resp -> resp.body().map(buffer -> {
                    Map<String, String> headers = new HashMap<>();
                    resp.headers().forEach(h -> headers.put(h.getKey().toLowerCase(), h.getValue()));
                    return new HttpResponse(resp.statusCode(), buffer.toString(StandardCharsets.UTF_8), headers);
                }));
        // 6 seconds, not the 2 used elsewhere in this file: this single wait now covers every
        // stage of the chain above (request creation, send, body read) rather than one stage each
        // getting its own 2-second budget the way three separate blocking calls used to, so it
        // needs the combined allowance to avoid trading the body-read flake this replaced for a
        // tighter timeout on a busy runner.
        return responseFuture.toCompletionStage().toCompletableFuture().get(6, TimeUnit.SECONDS);
    }

    private record HttpResponse(int statusCode, String body, Map<String, String> headers) {}
}
