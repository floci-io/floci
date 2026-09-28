package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.config.EmulatorConfig;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    private void startDataPlane(CodeArtifactService service, VerdaccioSidecarManager verdaccioManager)
            throws Exception {
        startDataPlane(service, verdaccioManager, true);
    }

    private void startDataPlane(CodeArtifactService service, VerdaccioSidecarManager verdaccioManager,
                                 boolean codeArtifactEnabled) throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        ServiceConfigAccess serviceConfigAccess = mock(ServiceConfigAccess.class);
        when(serviceConfigAccess.isEnabled("codeartifact")).thenReturn(codeArtifactEnabled);
        Router router = Router.router(vertx);
        new CodeArtifactNpmDataPlane(service, verdaccioManager, serviceConfigAccess, config, vertx).register(router);
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
