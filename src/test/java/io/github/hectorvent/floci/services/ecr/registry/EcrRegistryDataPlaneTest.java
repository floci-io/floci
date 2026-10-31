package io.github.hectorvent.floci.services.ecr.registry;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ecr.EcrService;
import io.github.hectorvent.floci.testutil.FreePorts;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Unit tests for routing AWS-shaped ECR repository URIs through Floci's data plane. */
class EcrRegistryDataPlaneTest {

    private Vertx vertx;
    private Semaphore repositoryWriteLock;
    private CountDownLatch repositoryWriteLockRefused;

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
        EcrRegistryDataPlane.RegistryRequest request = EcrRegistryDataPlane.requestFor(
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
    void hostnameStyleWithTlsDomainMatchesAndCapturesAccountAndRegion() {
        EcrRegistryDataPlane.RegistryRequest request = EcrRegistryDataPlane.requestFor(
                "123456789012.dkr.ecr.eu-west-1.localhost.floci.io:4566",
                "/v2/team/app/manifests/v2", null, "hostname").orElseThrow();

        assertEquals("123456789012", request.accountId());
        assertEquals("eu-west-1", request.region());
        assertEquals("team/app", request.repositoryName());
        assertEquals("123456789012/eu-west-1/team/app", request.storageRepositoryName());
        assertEquals("v2", request.tag());
        assertEquals("/v2/123456789012/eu-west-1/team/app/manifests/v2", request.backendUri());

        EcrRegistryDataPlane.RegistryRequest requestNoPort = EcrRegistryDataPlane.requestFor(
                "123456789012.dkr.ecr.eu-west-1.localhost.floci.io",
                "/v2/team/app/tags/list", null, "hostname").orElseThrow();

        assertEquals("123456789012", requestNoPort.accountId());
        assertEquals("eu-west-1", requestNoPort.region());
        assertEquals("/v2/123456789012/eu-west-1/team/app/tags/list", requestNoPort.backendUri());
    }

    @Test
    void malformedAccountOrNonEcrHostIsRejected() {
        // Less than 12 digits
        assertFalse(EcrRegistryDataPlane.requestFor(
                "12345.dkr.ecr.us-east-1.localhost.floci.io:4566",
                "/v2/repo/manifests/latest", null, "hostname").isPresent());
        // Non-numeric account
        assertFalse(EcrRegistryDataPlane.requestFor(
                "abcdefghijkl.dkr.ecr.us-east-1.localhost.floci.io:4566",
                "/v2/repo/manifests/latest", null, "hostname").isPresent());
        // Non-ECR subdomain
        assertFalse(EcrRegistryDataPlane.requestFor(
                "s3.localhost.floci.io:4566",
                "/v2/repo/manifests/latest", null, "hostname").isPresent());
        // Default host
        assertFalse(EcrRegistryDataPlane.requestFor(
                "localhost.floci.io:4566",
                "/v2/repo/manifests/latest", null, "hostname").isPresent());
    }

    @Test
    void digestManifestWriteDoesNotAcquireAnImmutableTagLock() {
        EcrRegistryDataPlane.RegistryRequest request = EcrRegistryDataPlane.requestFor(
                "000000000000.dkr.ecr.us-east-1.localhost:4566",
                "/v2/platform/api/manifests/sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                null, "hostname").orElseThrow();

        assertNull(request.tag());
    }

    @Test
    void pathStyleRequestRetainsItsExistingInternalNamespace() {
        EcrRegistryDataPlane.RegistryRequest request = EcrRegistryDataPlane.requestFor("localhost:4566",
                "/v2/000000000000/us-east-1/platform/api/blobs/uploads/", "mount=sha256:abc", "path").orElseThrow();

        assertEquals("platform/api", request.repositoryName());
        assertEquals("/v2/000000000000/us-east-1/platform/api/blobs/uploads/?mount=sha256:abc",
                request.backendUri());
    }

    @Test
    void mirrorNamespaceRoutesAnInNetworkK3sPullThroughTheSameDataPlane() {
        EcrRegistryDataPlane.RegistryRequest request = EcrRegistryDataPlane.requestFor("floci:4566", "/v2/platform/api/manifests/v1",
                "ns=000000000000.dkr.ecr.us-east-1.localhost%3A4566", "hostname").orElseThrow();

        assertEquals("000000000000", request.accountId());
        assertEquals("us-east-1", request.region());
        assertEquals("platform/api", request.repositoryName());
        assertEquals("/v2/000000000000/us-east-1/platform/api/manifests/v1", request.backendUri());
    }

    /**
     * The address Floci hands the Docker daemon for the ECS and Lambda images it pulls is path
     * style on localhost in either URI style: a daemon with the containerd image store reaches
     * only localhost and 127.0.0.1 over plain HTTP, and takes {@code *.localhost} for HTTPS.
     */
    @Test
    void pathStyleOnALoopbackHostIsClaimedInHostnameStyle() {
        EcrRegistryDataPlane.RegistryRequest request = EcrRegistryDataPlane.requestFor("localhost:4566",
                "/v2/000000000000/us-east-1/platform/api/manifests/v1", null, "hostname").orElseThrow();

        assertEquals("000000000000", request.accountId());
        assertEquals("us-east-1", request.region());
        assertEquals("platform/api", request.repositoryName());
        assertEquals("v1", request.tag());
        assertEquals("/v2/000000000000/us-east-1/platform/api/manifests/v1", request.backendUri());
        assertTrue(EcrRegistryDataPlane.requestFor("127.0.0.1:443",
                "/v2/000000000000/us-east-1/platform/api/blobs/sha256:abc", null, "hostname").isPresent());
        assertTrue(EcrRegistryDataPlane.requestFor("[::1]:4566", "/v2/", null, "hostname").isPresent());
        assertTrue(EcrRegistryDataPlane.requestFor("localhost:4566", "/v2/", null, "hostname").isPresent());
    }

    @Test
    void pathStyleOnAnotherHostIsNotClaimedInHostnameStyle() {
        assertFalse(EcrRegistryDataPlane.requestFor("floci:4566",
                "/v2/000000000000/us-east-1/platform/api/manifests/v1", null, "hostname").isPresent());
        assertFalse(EcrRegistryDataPlane.requestFor("localhost.floci.io:4566",
                "/v2/000000000000/us-east-1/platform/api/manifests/v1", null, "hostname").isPresent());
        assertFalse(EcrRegistryDataPlane.requestFor("localhost:4566", "/v2/apis", null, "hostname").isPresent());
    }

    @Test
    void unrelatedV2RouteDoesNotClaimTheRequest() {
        assertFalse(EcrRegistryDataPlane.requestFor("localhost:4566", "/v2/apis", null, "hostname").isPresent());
        assertTrue(EcrRegistryDataPlane.requestFor("000000000000.dkr.ecr.us-east-1.localhost:4566", "/v2/", null,
                "hostname").isPresent());
    }

    @Test
    void tlsMirrorNamespacePreservesTheAccountAndRegion() {
        EcrRegistryDataPlane.RegistryRequest request = EcrRegistryDataPlane.requestFor(
                "floci:4566", "/v2/team/app/manifests/v1",
                "ns=123456789012.dkr.ecr.eu-west-1.localhost.floci.io%3A4566", "hostname").orElseThrow();

        assertEquals("123456789012", request.accountId());
        assertEquals("eu-west-1", request.region());
        assertEquals("/v2/123456789012/eu-west-1/team/app/manifests/v1", request.backendUri());
    }

    @Test
    void tlsPathStyleMirrorPreservesTheRepositoryNamespace() {
        EcrRegistryDataPlane.RegistryRequest request = EcrRegistryDataPlane.requestFor(
                "floci:4566", "/v2/123456789012/eu-west-1/team/app/manifests/v1",
                "ns=localhost.floci.io%3A4566", "path").orElseThrow();

        assertEquals("123456789012", request.accountId());
        assertEquals("eu-west-1", request.region());
        assertEquals("team/app", request.repositoryName());
        assertEquals("/v2/123456789012/eu-west-1/team/app/manifests/v1", request.backendUri());
    }

    @Test
    void registryPingDoesNotAcquireARepositoryNamespace() {
        EcrRegistryDataPlane.RegistryRequest hostnameRequest = EcrRegistryDataPlane.requestFor(
                "000000000000.dkr.ecr.us-east-1.localhost:4566", "/v2/", null, "hostname").orElseThrow();
        EcrRegistryDataPlane.RegistryRequest pathRequest = EcrRegistryDataPlane.requestFor("localhost:4566", "/v2/", null, "path").orElseThrow();

        assertEquals("/v2/", hostnameRequest.backendUri());
        assertEquals("/v2/", pathRequest.backendUri());
    }

    @Test
    void uploadContinuationRetainsTheClientRepositoryNamespace() {
        EcrRegistryDataPlane.RegistryRequest request = EcrRegistryDataPlane.requestFor(
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
            dataPlane = dataPlane(registry, true);

            assertEquals(201, putManifest(dataPlane.actualPort()).statusCode());

            ManifestResponse rejected = putManifest(dataPlane.actualPort());
            assertEquals(400, rejected.statusCode());
            JsonObject json = new JsonObject(rejected.body());
            JsonArray errors = json.getJsonArray("errors");
            assertNotNull(errors);
            assertEquals(1, errors.size());
            JsonObject error = errors.getJsonObject(0);
            assertEquals("TAG_INVALID", error.getString("code"));
            assertEquals("The image tag v1 already exists in the platform/api repository and cannot be overwritten because the tag is immutable.",
                    error.getString("message"));

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

    @Test
    void immutableTagRejectsReplacementOverTlsDataPlane() throws Exception {
        AtomicBoolean tagExists = new AtomicBoolean();
        HttpServer registry = vertx.createHttpServer()
                .requestHandler(request -> {
                    if (request.method() == HttpMethod.HEAD) {
                        request.response().setStatusCode(tagExists.get() ? 200 : 404)
                                .putHeader("Docker-Content-Digest", "sha256:existing")
                                .end();
                    } else if (request.method() == HttpMethod.PUT) {
                        tagExists.set(true);
                        request.response().setStatusCode(201).end();
                    } else {
                        request.response().setStatusCode(200).end();
                    }
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        HttpServer dataPlane = null;
        try {
            dataPlane = dataPlane(registry, true);

            String tlsHost = "000000000000.dkr.ecr.us-east-1.localhost.floci.io:4566";
            assertEquals(201, putManifest(dataPlane.actualPort(), tlsHost).statusCode());

            ManifestResponse rejected = putManifest(dataPlane.actualPort(), tlsHost);
            assertEquals(400, rejected.statusCode());
            JsonObject json = new JsonObject(rejected.body());
            JsonArray errors = json.getJsonArray("errors");
            assertNotNull(errors);
            assertEquals(1, errors.size());
            JsonObject error = errors.getJsonObject(0);
            assertEquals("TAG_INVALID", error.getString("code"));
        } finally {
            if (dataPlane != null) {
                dataPlane.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
            registry.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void repeatedAcceptHeadersAllReachTheRegistry() throws Exception {
        AtomicReference<List<String>> received = new AtomicReference<>();
        HttpServer registry = vertx.createHttpServer()
                .requestHandler(request -> {
                    received.set(request.headers().getAll("Accept"));
                    request.response().setStatusCode(200).end();
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        HttpServer dataPlane = null;
        try {
            dataPlane = dataPlane(registry, false);

            List<String> sent = List.of(
                    "application/vnd.docker.distribution.manifest.v2+json",
                    "application/vnd.docker.distribution.manifest.list.v2+json",
                    "application/vnd.oci.image.manifest.v1+json",
                    "application/vnd.oci.image.index.v1+json");
            int status = vertx.createHttpClient().request(new RequestOptions()
                            .setHost("127.0.0.1")
                            .setPort(dataPlane.actualPort())
                            .setMethod(HttpMethod.GET)
                            .setURI("/v2/platform/api/manifests/v1"))
                    .compose(request -> {
                        request.putHeader("Host", "000000000000.dkr.ecr.us-east-1.localhost:4566");
                        sent.forEach(mediaType -> request.headers().add("Accept", mediaType));
                        return request.send();
                    })
                    .map(response -> response.statusCode())
                    .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(200, status);
            assertEquals(sent, received.get());
        } finally {
            if (dataPlane != null) {
                dataPlane.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
            registry.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    /**
     * A manifest push takes its repository's write lock before anything of it reaches the
     * registry, the same lock a BatchDeleteImage holds while it drops a shared manifest and
     * writes it back under its surviving tags: a push that arrives during such a delete waits
     * for it instead of landing between the drop and the write-back, where the restore would
     * overwrite it. Reads take no lock and answer while the push waits.
     */
    @Test
    void aManifestPushWaitsForTheRepositoryWriteLockAndCompletesAfterIt() throws Exception {
        AtomicInteger manifestWrites = new AtomicInteger();
        HttpServer registry = vertx.createHttpServer()
                .requestHandler(request -> {
                    if (request.method() == HttpMethod.PUT) {
                        request.bodyHandler(body -> {
                            manifestWrites.incrementAndGet();
                            request.response().setStatusCode(201).end();
                        });
                    } else if (request.method() == HttpMethod.HEAD) {
                        request.response().setStatusCode(404).end();
                    } else {
                        request.response().setStatusCode(200).end();
                    }
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        HttpServer dataPlane = null;
        HttpClient client = null;
        try {
            dataPlane = dataPlane(registry, false);
            client = vertx.createHttpClient();
            assertTrue(repositoryWriteLock.tryAcquire(), "the test takes the lock a delete would hold");

            Future<HttpClientResponse> push = client.request(new RequestOptions()
                            .setHost("127.0.0.1")
                            .setPort(dataPlane.actualPort())
                            .setMethod(HttpMethod.PUT)
                            .setURI("/v2/platform/api/manifests/v1"))
                    .compose(request -> {
                        request.putHeader("Host", "000000000000.dkr.ecr.us-east-1.localhost:4566");
                        request.setChunked(true);
                        return request.send(Buffer.buffer("{}"));
                    });

            Integer readStatus = client.request(new RequestOptions()
                            .setHost("127.0.0.1")
                            .setPort(dataPlane.actualPort())
                            .setMethod(HttpMethod.GET)
                            .setURI("/v2/platform/api/manifests/v1"))
                    .compose(request -> {
                        request.putHeader("Host", "000000000000.dkr.ecr.us-east-1.localhost:4566");
                        return request.send().map(HttpClientResponse::statusCode);
                    })
                    .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(200, readStatus, "a read answers while a write waits for the lock");

            assertTrue(repositoryWriteLockRefused.await(5, TimeUnit.SECONDS),
                    "the push is parked waiting for the repository's write lock");
            assertEquals(0, manifestWrites.get(),
                    "the push must not reach the registry while the repository's delete holds the lock");

            repositoryWriteLock.release();
            HttpClientResponse response = push.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(201, response.statusCode());
            assertEquals(1, manifestWrites.get());
            assertTrue(acquiredWithin(repositoryWriteLock, 5_000),
                    "the exchange returns the write lock with its answer");
        } finally {
            if (client != null) {
                client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
            if (dataPlane != null) {
                dataPlane.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
            registry.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    /**
     * Two manifest PUTs to one repository on one HTTP/2 connection: the first holds the
     * repository's write lock and is parked mid-body at the registry, the second waits for the
     * lock. The second push's body must not sit unread in the connection's flow-control
     * window, where it would keep the first push's remaining bytes from ever arriving: the
     * first could not finish, would never release the lock, and both would wait forever. The
     * data plane drains a waiting manifest body, so the second push's whole body reaches the
     * data plane while the first is still parked, and both pushes then complete, in order,
     * once the registry answers.
     */
    @Test
    void concurrentManifestPushesOnOneConnectionDoNotDeadlock() throws Exception {
        int parkedBodyBytes = 4 * 1024 * 1024;
        int waitingBodyBytes = 2 * 1024 * 1024;
        CountDownLatch parkedArrived = new CountDownLatch(1);
        CountDownLatch parkedAnswered = new CountDownLatch(1);
        AtomicReference<HttpServerRequest> parkedRequest = new AtomicReference<>();
        AtomicInteger order = new AtomicInteger();
        AtomicInteger parkedAnsweredAt = new AtomicInteger(-1);
        AtomicInteger waitingArrivedAt = new AtomicInteger(-1);
        AtomicInteger registryParkedBodyBytes = new AtomicInteger();
        AtomicInteger registryWaitingBodyBytes = new AtomicInteger();
        HttpServer registry = vertx.createHttpServer()
                .requestHandler(request -> {
                    if (request.path().endsWith("/manifests/first")) {
                        parkedRequest.set(request);
                        request.pause();
                        parkedArrived.countDown();
                        return;
                    }
                    if (!request.path().endsWith("/manifests/second")) {
                        request.response().setStatusCode(405).end();
                        return;
                    }
                    waitingArrivedAt.set(order.incrementAndGet());
                    request.bodyHandler(body -> {
                        registryWaitingBodyBytes.set(body.length());
                        request.response().setStatusCode(201).end();
                    });
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        HttpServer dataPlane = null;
        HttpClient client = null;
        try {
            dataPlane = dataPlane(registry, false);
            // HTTP/2 with prior knowledge: both pushes are streams on one connection, as
            // crane's are.
            client = vertx.createHttpClient(new HttpClientOptions()
                    .setProtocolVersion(HttpVersion.HTTP_2)
                    .setHttp2ClearTextUpgrade(false));
            SocketAddress server = SocketAddress.inetSocketAddress(dataPlane.actualPort(), "127.0.0.1");

            Future<HttpClientResponse> first = client.request(new RequestOptions()
                            .setServer(server)
                            .setHost("000000000000.dkr.ecr.us-east-1.localhost")
                            .setPort(4566)
                            .setMethod(HttpMethod.PUT)
                            .setURI("/v2/platform/api/manifests/first"))
                    .compose(request -> request.send(Buffer.buffer(new byte[parkedBodyBytes])));
            assertTrue(parkedArrived.await(5, TimeUnit.SECONDS),
                    "the push that holds the lock reached the registry");

            HttpClientRequest waiting = client.request(new RequestOptions()
                            .setServer(server)
                            .setHost("000000000000.dkr.ecr.us-east-1.localhost")
                            .setPort(4566)
                            .setMethod(HttpMethod.PUT)
                            .setURI("/v2/platform/api/manifests/second"))
                    .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            waiting.setChunked(true);
            Future<HttpClientResponse> second = waiting.response();
            List<Future<Void>> writes = new ArrayList<>();
            for (int written = 0; written < waitingBodyBytes; written += 64 * 1024) {
                writes.add(waiting.write(Buffer.buffer(new byte[64 * 1024])));
            }

            Future.join(writes).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(1, parkedAnswered.getCount(),
                    "the waiting push's whole body must reach the data plane while the push"
                            + " holding the lock is still parked");
            waiting.end();

            HttpServerRequest parked = parkedRequest.get();
            parked.bodyHandler(body -> {
                registryParkedBodyBytes.set(body.length());
                parkedAnsweredAt.set(order.incrementAndGet());
                parked.response().setStatusCode(201).end();
                parkedAnswered.countDown();
            });
            parked.resume();

            assertEquals(201, first.toCompletionStage().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).statusCode());
            assertEquals(201, second.toCompletionStage().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).statusCode());
            assertEquals(parkedBodyBytes, registryParkedBodyBytes.get());
            assertEquals(waitingBodyBytes, registryWaitingBodyBytes.get());
            assertTrue(waitingArrivedAt.get() > parkedAnsweredAt.get(),
                    "the waiting push reaches the registry only after the push holding the"
                            + " lock is answered");
            assertTrue(acquiredWithin(repositoryWriteLock, 5_000),
                    "both exchanges return the write lock with their answers");
        } finally {
            if (client != null) {
                client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
            if (dataPlane != null) {
                dataPlane.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
            registry.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    /**
     * An HTTP/2 client such as crane sends every upload of a push as a stream on one
     * connection, and the data plane needs a registry connection for each. An upload left
     * waiting for one stays paused, and its unread bytes hold the client connection's
     * flow-control window: the uploads that do have a registry connection stall and never
     * give it back.
     */
    @Test
    void concurrentHttp2UploadsOnOneConnectionAllReachTheRegistry() throws Exception {
        int uploads = 8;
        Buffer layer = Buffer.buffer(new byte[1024 * 1024]);
        AtomicInteger bytesReceived = new AtomicInteger();
        CountDownLatch registryConnectionsClosed = new CountDownLatch(uploads);
        HttpServer registry = vertx.createHttpServer()
                .connectionHandler(connection -> connection.closeHandler(closed -> registryConnectionsClosed.countDown()))
                .requestHandler(request -> request.body().onSuccess(body -> {
                    bytesReceived.addAndGet(body.length());
                    request.response().setStatusCode(202).end();
                }))
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        HttpServer dataPlane = null;
        HttpClient client = null;
        try {
            dataPlane = dataPlane(registry, false);

            // HTTP/2 with prior knowledge. The client pools a single HTTP/2 connection by
            // default, so every upload is a stream on the same connection, as crane's are.
            client = vertx.createHttpClient(new HttpClientOptions()
                    .setProtocolVersion(HttpVersion.HTTP_2)
                    .setHttp2ClearTextUpgrade(false));
            SocketAddress server = SocketAddress.inetSocketAddress(dataPlane.actualPort(), "127.0.0.1");
            List<Future<HttpClientResponse>> responses = new ArrayList<>();
            for (int i = 0; i < uploads; i++) {
                responses.add(client.request(new RequestOptions()
                                .setServer(server)
                                .setHost("000000000000.dkr.ecr.us-east-1.localhost")
                                .setPort(4566)
                                .setMethod(HttpMethod.PATCH)
                                .setURI("/v2/platform/api/blobs/uploads/upload-" + i))
                        .compose(request -> request.send(layer)));
            }

            Future.all(responses).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            for (Future<HttpClientResponse> response : responses) {
                assertEquals(HttpVersion.HTTP_2, response.result().version());
                assertEquals(202, response.result().statusCode());
            }
            assertEquals(uploads * layer.length(), bytesReceived.get());
            assertTrue(registryConnectionsClosed.await(5, TimeUnit.SECONDS),
                    "every exchange closes the registry connection it opened");
        } finally {
            if (client != null) {
                client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
            if (dataPlane != null) {
                dataPlane.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
            registry.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    /**
     * An HTTP/2 client that resets an upload's stream mid-body ends the exchange: the data plane
     * closes the registry connection it opened for it, which would otherwise wait for the rest of
     * the declared Content-Length.
     */
    @Test
    void anUploadResetMidBodyClosesItsRegistryConnection() throws Exception {
        CountDownLatch registryConnectionClosed = new CountDownLatch(1);
        CountDownLatch uploadStarted = new CountDownLatch(1);
        HttpServer registry = vertx.createHttpServer()
                .connectionHandler(connection -> connection.closeHandler(closed -> registryConnectionClosed.countDown()))
                .requestHandler(request -> request.handler(chunk -> uploadStarted.countDown()))
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        HttpServer dataPlane = null;
        HttpClient client = null;
        try {
            dataPlane = dataPlane(registry, false);
            client = vertx.createHttpClient(new HttpClientOptions()
                    .setProtocolVersion(HttpVersion.HTTP_2)
                    .setHttp2ClearTextUpgrade(false));
            HttpClientRequest upload = client.request(new RequestOptions()
                            .setServer(SocketAddress.inetSocketAddress(dataPlane.actualPort(), "127.0.0.1"))
                            .setHost("000000000000.dkr.ecr.us-east-1.localhost")
                            .setPort(4566)
                            .setMethod(HttpMethod.PATCH)
                            .setURI("/v2/platform/api/blobs/uploads/abandoned"))
                    .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(HttpVersion.HTTP_2, upload.version());
            upload.putHeader("Content-Length", String.valueOf(1024 * 1024));
            upload.write(Buffer.buffer(new byte[64 * 1024]));
            assertTrue(uploadStarted.await(5, TimeUnit.SECONDS), "the upload reached the registry");

            upload.reset();

            assertTrue(registryConnectionClosed.await(5, TimeUnit.SECONDS),
                    "the abandoned exchange closes its registry connection");
        } finally {
            if (client != null) {
                client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
            if (dataPlane != null) {
                dataPlane.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
            registry.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    /**
     * A client that resets its stream while the data plane is still checking an immutable tag ends
     * the exchange there: nothing of it reaches the registry, and the tag is free for the next
     * write.
     */
    @Test
    void aWriteResetDuringTheTagCheckNeverReachesTheRegistryAndFreesTheTag() throws Exception {
        AtomicReference<HttpServerRequest> heldCheck = new AtomicReference<>();
        CountDownLatch checkArrived = new CountDownLatch(1);
        CountDownLatch tagReleased = new CountDownLatch(1);
        AtomicInteger manifestWrites = new AtomicInteger();
        AtomicReference<String> manifestBody = new AtomicReference<>();
        HttpServer registry = vertx.createHttpServer()
                .requestHandler(request -> {
                    if (request.method() == HttpMethod.HEAD) {
                        // The first check waits until the test answers it; later ones find no tag.
                        if (heldCheck.compareAndSet(null, request)) {
                            checkArrived.countDown();
                        } else {
                            request.response().setStatusCode(404).end();
                        }
                    } else if (request.method() == HttpMethod.PUT) {
                        manifestWrites.incrementAndGet();
                        request.body().onSuccess(body -> {
                            manifestBody.set(body.toString());
                            request.response().setStatusCode(201).end();
                        });
                    } else {
                        request.response().setStatusCode(405).end();
                    }
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        HttpServer dataPlane = null;
        HttpClient client = null;
        try {
            dataPlane = dataPlane(registry, true, tagReleased);
            client = vertx.createHttpClient(new HttpClientOptions()
                    .setProtocolVersion(HttpVersion.HTTP_2)
                    .setHttp2ClearTextUpgrade(false));
            HttpClientRequest write = client.request(new RequestOptions()
                            .setServer(SocketAddress.inetSocketAddress(dataPlane.actualPort(), "127.0.0.1"))
                            .setHost("000000000000.dkr.ecr.us-east-1.localhost")
                            .setPort(4566)
                            .setMethod(HttpMethod.PUT)
                            .setURI("/v2/platform/api/manifests/v1"))
                    .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(HttpVersion.HTTP_2, write.version());
            write.putHeader("Content-Length", "64");
            write.write(Buffer.buffer("{\"abandoned\""));
            assertTrue(checkArrived.await(5, TimeUnit.SECONDS), "the data plane checks the tag first");

            write.reset();
            assertTrue(tagReleased.await(2, TimeUnit.SECONDS),
                    "the reset releases the tag while its registry check is still blocked");
            heldCheck.get().response().setStatusCode(404).end();

            assertEquals(201, putManifest(dataPlane.actualPort()).statusCode());
            assertEquals(1, manifestWrites.get());
            assertEquals("{}", manifestBody.get());
        } finally {
            if (client != null) {
                client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
            if (dataPlane != null) {
                dataPlane.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
            registry.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @CsvSource({"true,GET,/v2/", "false,GET,/v2/",
            "true,PUT,/v2/platform/api/manifests/latest", "false,PUT,/v2/platform/api/manifests/latest"})
    void unavailableRegistryExplainsTheNetworkRequirement(boolean startupFailure, String method, String path)
            throws Exception {
        EcrRegistryManager manager = Mockito.mock(EcrRegistryManager.class);
        HttpServer dataPlane = dataPlane(manager, false, null);
        when(manager.httpClient()).thenReturn(new RegistryHttpClient(
                "http://127.0.0.1:" + FreePorts.loopbackIpv4Port()));
        if (startupFailure) {
            Mockito.doThrow(new IllegalStateException("internal-startup-detail")).when(manager).ensureStarted();
        }
        RegistryResponse response = request(dataPlane.actualPort(), HttpMethod.valueOf(method), path);
        assertEquals(503, response.status());
        assertEquals("application/json", response.contentType());
        assertEquals("registry/2.0", response.apiVersion());
        assertFalse(response.body().isBlank());
        JsonArray errors = new JsonObject(response.body()).getJsonArray("errors");
        assertNotNull(errors);
        assertEquals(1, errors.size());
        JsonObject error = errors.getJsonObject(0);
        assertEquals("UNAVAILABLE", error.getString("code"));
        String message = error.getString("message");
        assertTrue(message.contains("registry"));
        assertTrue(message.contains("user-defined network"));
        assertTrue(message.contains("FLOCI_SERVICES_ECR_DOCKER_NETWORK"));
        assertFalse(response.body().contains("internal-startup-detail"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void headFailuresKeepRegistryHeadersWithoutSendingABody(boolean startupFailure) throws Exception {
        EcrRegistryManager manager = Mockito.mock(EcrRegistryManager.class);
        HttpServer dataPlane = dataPlane(manager, false, null);
        when(manager.httpClient()).thenReturn(new RegistryHttpClient(
                "http://127.0.0.1:" + FreePorts.loopbackIpv4Port()));
        if (startupFailure) {
            Mockito.doThrow(new IllegalStateException("internal-startup-detail")).when(manager).ensureStarted();
        }
        RegistryResponse response = request(dataPlane.actualPort(), HttpMethod.HEAD, "/v2/platform/api/manifests/latest");
        assertEquals(503, response.status());
        assertEquals("application/json", response.contentType());
        assertEquals("registry/2.0", response.apiVersion());
        assertTrue(response.body().isEmpty());
    }

    @Test
    void upstreamErrorResponsesPassThroughUnchanged() throws Exception {
        HttpServer registry = vertx.createHttpServer().requestHandler(request -> request.response()
                        .setStatusCode(503).putHeader("Content-Type", "text/plain").end("registry temporarily busy"))
                .listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        EcrRegistryManager manager = Mockito.mock(EcrRegistryManager.class);
        when(manager.httpClient()).thenReturn(new RegistryHttpClient("http://127.0.0.1:" + registry.actualPort()));
        HttpServer dataPlane = dataPlane(manager, false, null);
        RegistryResponse response = request(dataPlane.actualPort(), HttpMethod.GET, "/v2/");
        assertEquals(503, response.status());
        assertEquals("text/plain", response.contentType());
        assertEquals("registry temporarily busy", response.body());
        assertNull(response.apiVersion());
    }

    /**
     * A data plane in front of {@code registry}, on plain HTTP that a client can speak HTTP/1.1 or,
     * with prior knowledge, HTTP/2 to. Repository {@code platform/api} of account 000000000000 in
     * us-east-1 is stored as {@code legacy/platform-api}, with immutable tags when asked.
     */
    private HttpServer dataPlane(HttpServer registry, boolean immutableTags) throws Exception {
        return dataPlane(registry, immutableTags, null);
    }

    private HttpServer dataPlane(HttpServer registry, boolean immutableTags, CountDownLatch tagReleased) throws Exception {
        EcrRegistryManager registryManager = Mockito.mock(EcrRegistryManager.class);
        when(registryManager.httpClient())
                .thenReturn(new RegistryHttpClient("http://127.0.0.1:" + registry.actualPort()));
        return dataPlane(registryManager, immutableTags, tagReleased);
    }

    private HttpServer dataPlane(EcrRegistryManager registryManager, boolean immutableTags,
                                 CountDownLatch tagReleased) throws Exception {
        EcrService ecrService = Mockito.mock(EcrService.class);
        EmulatorConfig config = Mockito.mock(EmulatorConfig.class, Mockito.RETURNS_DEEP_STUBS);
        when(config.services().ecr().enabled()).thenReturn(true);
        when(config.services().ecr().uriStyle()).thenReturn("hostname");
        when(ecrService.isImageTagImmutable("platform/api", "000000000000", "us-east-1"))
                .thenReturn(immutableTags);
        // The tag lock lives in EcrService, so the mock holds a real one.
        Set<String> heldTags = ConcurrentHashMap.newKeySet();
        when(ecrService.claimImmutableTag(anyString())).thenAnswer(call -> heldTags.add(call.getArgument(0)));
        Mockito.doAnswer(call -> {
            boolean removed = heldTags.remove(call.getArgument(0));
            if (removed && tagReleased != null) {
                tagReleased.countDown();
            }
            return null;
        })
                .when(ecrService).releaseImmutableTag(anyString());
        // The repository write lock lives in EcrService too, so the mock holds a real one,
        // and counts a latch each time it refuses a writer.
        repositoryWriteLock = new Semaphore(1);
        repositoryWriteLockRefused = new CountDownLatch(1);
        when(ecrService.tryLockRepositoryWrites(anyString()))
                .thenAnswer(call -> {
                    if (repositoryWriteLock.tryAcquire()) {
                        return true;
                    }
                    repositoryWriteLockRefused.countDown();
                    return false;
                });
        Mockito.doAnswer(call -> {
            repositoryWriteLock.release();
            return null;
        }).when(ecrService).unlockRepositoryWrites(anyString());
        when(ecrService.registryRepositoryName("platform/api", "000000000000", "us-east-1"))
                .thenReturn("legacy/platform-api");

        Router router = Router.router(vertx);
        new EcrRegistryDataPlane(registryManager, ecrService, config, vertx).register(router);
        return vertx.createHttpServer().requestHandler(router)
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private record ManifestResponse(int statusCode, String body) {
    }

    /**
     * Takes the semaphore within {@code millis}, for assertions that follow an exchange whose
     * end, and with it its lock release, runs on the event loop after its answer is seen.
     */
    private static boolean acquiredWithin(Semaphore semaphore, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (!semaphore.tryAcquire()) {
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    private ManifestResponse putManifest(int port) throws Exception {
        return putManifest(port, "000000000000.dkr.ecr.us-east-1.localhost:4566");
    }

    private ManifestResponse putManifest(int port, String host) throws Exception {
        HttpClient client = vertx.createHttpClient();
        return client.request(new RequestOptions()
                        .setHost("127.0.0.1")
                        .setPort(port)
                        .setMethod(HttpMethod.PUT)
                        .setURI("/v2/platform/api/manifests/v1"))
                .compose(request -> {
                    request.putHeader("Host", host);
                    request.setChunked(true);
                    return request.send(Buffer.buffer("{}"));
                })
                // Read the body inside the response's own future chain. A body() registered from
                // the test thread after get() races the event loop, which may already have
                // delivered the whole response, and then yields an empty buffer.
                .compose(response -> response.body()
                        .map(body -> new ManifestResponse(response.statusCode(), body.toString())))
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private RegistryResponse request(int port, HttpMethod method, String path) throws Exception {
        return vertx.createHttpClient().request(new RequestOptions().setHost("127.0.0.1").setPort(port)
                        .setMethod(method).setURI(path))
                .compose(request -> request.putHeader("Host", "000000000000.dkr.ecr.us-east-1.localhost:4566").send())
                .compose(response -> response.body().map(body -> new RegistryResponse(response.statusCode(),
                        response.getHeader("Content-Type"), response.getHeader("Docker-Distribution-API-Version"),
                        body.toString())))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private record RegistryResponse(int status, String contentType, String apiVersion, String body) {}
}
