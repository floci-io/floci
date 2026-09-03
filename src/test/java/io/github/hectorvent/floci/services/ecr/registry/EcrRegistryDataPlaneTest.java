package io.github.hectorvent.floci.services.ecr.registry;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ecr.EcrService;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** Unit tests for routing AWS-shaped ECR repository URIs through Floci's data plane. */
class EcrRegistryDataPlaneTest {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void hostnameStyleManifestWriteUsesInternalRegistryNamespace() {
        var request = EcrRegistryDataPlane.requestFor(
                "000000000000.dkr.ecr.us-east-1.localhost:4566",
                "/v2/platform/api/manifests/v1", null, "hostname").orElseThrow();

        assertEquals("000000000000", request.accountId());
        assertEquals("us-east-1", request.region());
        assertEquals("platform/api", request.repositoryName());
        assertEquals("000000000000/us-east-1/platform/api", request.storageRepositoryName());
        assertEquals("v1", request.tag());
        assertEquals("/v2/000000000000/us-east-1/platform/api/manifests/v1", request.backendUri());
    }

    @Test
    void digestManifestWriteDoesNotAcquireAnImmutableTagLock() {
        var request = EcrRegistryDataPlane.requestFor(
                "000000000000.dkr.ecr.us-east-1.localhost:4566",
                "/v2/platform/api/manifests/sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                null, "hostname").orElseThrow();

        assertNull(request.tag());
    }

    @Test
    void pathStyleRequestRetainsItsExistingInternalNamespace() {
        var request = EcrRegistryDataPlane.requestFor("localhost:4566",
                "/v2/000000000000/us-east-1/platform/api/blobs/uploads/", "mount=sha256:abc", "path").orElseThrow();

        assertEquals("platform/api", request.repositoryName());
        assertEquals("/v2/000000000000/us-east-1/platform/api/blobs/uploads/?mount=sha256:abc",
                request.backendUri());
    }

    @Test
    void mirrorNamespaceRoutesAnInNetworkK3sPullThroughTheSameDataPlane() {
        var request = EcrRegistryDataPlane.requestFor("floci:4566", "/v2/platform/api/manifests/v1",
                "ns=000000000000.dkr.ecr.us-east-1.localhost%3A4566", "hostname").orElseThrow();

        assertEquals("000000000000", request.accountId());
        assertEquals("us-east-1", request.region());
        assertEquals("platform/api", request.repositoryName());
        assertEquals("/v2/000000000000/us-east-1/platform/api/manifests/v1", request.backendUri());
    }

    @Test
    void unrelatedV2RouteDoesNotClaimTheRequest() {
        assertFalse(EcrRegistryDataPlane.requestFor("localhost:4566", "/v2/apis", null, "hostname").isPresent());
        assertTrue(EcrRegistryDataPlane.requestFor("000000000000.dkr.ecr.us-east-1.localhost:4566", "/v2/", null,
                "hostname").isPresent());
    }

    @Test
    void registryPingDoesNotAcquireARepositoryNamespace() {
        var hostnameRequest = EcrRegistryDataPlane.requestFor(
                "000000000000.dkr.ecr.us-east-1.localhost:4566", "/v2/", null, "hostname").orElseThrow();
        var pathRequest = EcrRegistryDataPlane.requestFor("localhost:4566", "/v2/", null, "path").orElseThrow();

        assertEquals("/v2/", hostnameRequest.backendUri());
        assertEquals("/v2/", pathRequest.backendUri());
    }

    @Test
    void uploadContinuationRetainsTheClientRepositoryNamespace() {
        var request = EcrRegistryDataPlane.requestFor(
                "000000000000.dkr.ecr.us-east-1.localhost:4566",
                "/v2/platform/api/blobs/uploads/", null, "hostname").orElseThrow();

        assertEquals("/v2/platform/api/blobs/uploads/upload-id",
                EcrRegistryDataPlane.externalLocation(
                        "/v2/000000000000/us-east-1/platform/api/blobs/uploads/upload-id", request));
    }

    @Test
    void immutableTagRejectsReplacementBeforeItReachesTheRegistry() throws Exception {
        AtomicBoolean tagExists = new AtomicBoolean();
        AtomicInteger manifestWrites = new AtomicInteger();
        AtomicReference<String> headPath = new AtomicReference<>();
        AtomicReference<String> manifestBody = new AtomicReference<>();
        AtomicReference<String> manifestPath = new AtomicReference<>();
        HttpServer registry = vertx.createHttpServer()
                .requestHandler(request -> {
                    if (request.method() == HttpMethod.HEAD) {
                        headPath.set(request.path());
                        request.response().setStatusCode(tagExists.get() ? 200 : 404)
                                .putHeader("Docker-Content-Digest", "sha256:existing")
                                .end();
                    } else if (request.method() == HttpMethod.PUT) {
                        request.bodyHandler(body -> {
                            manifestPath.set(request.path());
                            manifestBody.set(body.toString());
                            manifestWrites.incrementAndGet();
                            tagExists.set(true);
                            request.response().setStatusCode(201).end();
                        });
                    } else {
                        request.response().setStatusCode(405).end();
                    }
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        HttpServer dataPlane = null;
        try {
            EcrRegistryManager registryManager = Mockito.mock(EcrRegistryManager.class);
            EcrService ecrService = Mockito.mock(EcrService.class);
            EmulatorConfig config = Mockito.mock(EmulatorConfig.class, Mockito.RETURNS_DEEP_STUBS);
            when(config.services().ecr().enabled()).thenReturn(true);
            when(config.services().ecr().uriStyle()).thenReturn("hostname");
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://127.0.0.1:" + registry.actualPort()));
            when(ecrService.isImageTagImmutable("platform/api", "000000000000", "us-east-1"))
                    .thenReturn(true);
            when(ecrService.registryRepositoryName("platform/api", "000000000000", "us-east-1"))
                    .thenReturn("legacy/platform-api");

            Router router = Router.router(vertx);
            new EcrRegistryDataPlane(registryManager, ecrService, config, vertx).register(router);
            dataPlane = vertx.createHttpServer().requestHandler(router)
                    .listen(0, "127.0.0.1")
                    .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(201, putManifest(dataPlane.actualPort()).statusCode());
            assertEquals(400, putManifest(dataPlane.actualPort()).statusCode());
            assertEquals(1, manifestWrites.get());
            assertEquals("/v2/legacy/platform-api/manifests/v1", headPath.get());
            assertEquals("/v2/legacy/platform-api/manifests/v1", manifestPath.get());
            assertEquals("{}", manifestBody.get());
        } finally {
            if (dataPlane != null) {
                dataPlane.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
            registry.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    private io.vertx.core.http.HttpClientResponse putManifest(int port) throws Exception {
        HttpClient client = vertx.createHttpClient();
        return client.request(new RequestOptions()
                        .setHost("127.0.0.1")
                        .setPort(port)
                        .setMethod(HttpMethod.PUT)
                        .setURI("/v2/platform/api/manifests/v1"))
                .compose(request -> {
                    request.putHeader("Host", "000000000000.dkr.ecr.us-east-1.localhost:4566");
                    request.setChunked(true);
                    return request.send(Buffer.buffer("{}"));
                })
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }
}
