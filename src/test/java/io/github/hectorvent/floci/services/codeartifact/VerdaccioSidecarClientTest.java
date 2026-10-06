package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.PerKeyContainerPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Wire-contract tests for {@link VerdaccioSidecarClient} against a JDK {@code HttpServer} standing in
 * for a running Verdaccio container. The container is registered in the manager's pool directly, so
 * readiness resolves to the fake server without any Docker daemon.
 */
class VerdaccioSidecarClientTest {

    private final ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
    private final ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class);

    private HttpServer backend;
    private String backendUrl;

    @BeforeEach
    void setUpBackend() throws Exception {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/-/ping", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        backend.start();
        backendUrl = "http://127.0.0.1:" + backend.getAddress().getPort();
    }

    @AfterEach
    void tearDownBackend() {
        backend.stop(0);
    }

    @Test
    void fetchPackageVersionAssetGetsTheUnscopedTarballPath() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", backendUrl);
        respondWithMetadata("/demo-pkg", "1.0.0", "/demo-pkg/-/demo-pkg-1.0.0.tgz");
        backend.createContext("/demo-pkg/-/demo-pkg-1.0.0.tgz", exchange -> {
            byte[] body = "tarball bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("npm-repo-1", "dom", "repo", null, "demo-pkg",
                "1.0.0", "demo-pkg-1.0.0.tgz");

        assertTrue(result.isPresent());
        assertEquals("tarball bytes", new String(result.get(), StandardCharsets.UTF_8));
    }

    /**
     * Confirmed against a live Verdaccio container: a scoped package's tarball path keeps the
     * {@code @scope/} segment in the URL even though the filename itself never includes it
     * (unlike the local tarball the npm CLI builds when packing, which does).
     */
    @Test
    void fetchPackageVersionAssetGetsTheScopedTarballPathWithTheFilenameUnprefixed() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", backendUrl);
        respondWithMetadata("/@myscope/demo-pkg", "2.0.0", "/@myscope/demo-pkg/-/demo-pkg-2.0.0.tgz");
        backend.createContext("/@myscope/demo-pkg/-/demo-pkg-2.0.0.tgz", exchange -> {
            byte[] body = "scoped tarball bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("npm-repo-1", "dom", "repo", "myscope",
                "demo-pkg", "2.0.0", "demo-pkg-2.0.0.tgz");

        assertTrue(result.isPresent());
        assertEquals("scoped tarball bytes", new String(result.get(), StandardCharsets.UTF_8));
    }

    @Test
    void fetchPackageVersionAssetReturnsEmptyWhenThePackageWasNeverPublished() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", backendUrl);
        backend.createContext("/demo-pkg", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("npm-repo-1", "dom", "repo", null, "demo-pkg",
                "1.0.0", "missing.tgz");

        assertTrue(result.isEmpty());
    }

    /**
     * The fix for a real finding: without checking the package's own metadata first, a caller
     * naming a real tarball filename under a version that was never actually published to it
     * would get that file's bytes back anyway, since the tarball route is keyed by filename alone.
     */
    @Test
    void fetchPackageVersionAssetReturnsEmptyWhenTheVersionWasNeverPublishedEvenIfTheFilenameExists() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", backendUrl);
        respondWithMetadata("/demo-pkg", "1.0.0", "/demo-pkg/-/demo-pkg-1.0.0.tgz");
        backend.createContext("/demo-pkg/-/demo-pkg-9.9.9.tgz", exchange -> {
            byte[] body = "should never be served".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("npm-repo-1", "dom", "repo", null, "demo-pkg",
                "9.9.9", "demo-pkg-9.9.9.tgz");

        assertTrue(result.isEmpty());
    }

    @Test
    void fetchPackageVersionAssetThrowsRatherThanReportingNotFoundOnASidecarServerError() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", backendUrl);
        backend.createContext("/demo-pkg", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        assertThrows(IllegalStateException.class, () -> client(manager).fetchPackageVersionAsset("npm-repo-1", "dom", "repo",
                null, "demo-pkg", "1.0.0", "demo-pkg-1.0.0.tgz"));
    }

    /**
     * The fix for a real finding: {@code packagePath} used to splice the npm scope straight into
     * the URL. A {@code /} inside {@code namespace} (the scope) would then be indistinguishable
     * from one of the real separators between the scope, the package name, and {@code -}, letting
     * it splice in extra path segments. Fixed by percent-encoding each coordinate on its own
     * before joining them, so a {@code /} inside one of them survives only as a literal
     * {@code %2F} within one segment.
     */
    @Test
    void fetchPackageVersionAssetTreatsASlashInTheNamespaceAsLiteralNotAStructuralSeparator() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", backendUrl);
        AtomicReference<String> observedRawPath = new AtomicReference<>();
        backend.createContext("/", exchange -> {
            observedRawPath.set(exchange.getRequestURI().getRawPath());
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("npm-repo-1", "dom", "repo", "../../escape",
                "demo-pkg", "1.0.0", "demo-pkg-1.0.0.tgz");

        assertTrue(result.isEmpty());
        assertEquals("/@..%2F..%2Fescape/demo-pkg", observedRawPath.get());
    }

    /**
     * Same finding, the other half of it: a coordinate that is exactly {@code ..} (no slash at
     * all, so the fix above wouldn't catch it on its own) would sit as a real dot-segment between
     * two of this method's own {@code /} separators. Fixed by also escaping the dots themselves
     * whenever a coordinate's entire value is {@code .} or {@code ..}.
     */
    @Test
    void fetchPackageVersionAssetTreatsADotDotPackageNameAsLiteralNotADotSegment() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", backendUrl);
        AtomicReference<String> observedRawPath = new AtomicReference<>();
        backend.createContext("/", exchange -> {
            observedRawPath.set(exchange.getRequestURI().getRawPath());
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("npm-repo-1", "dom", "repo", null, "..",
                "1.0.0", "demo-pkg-1.0.0.tgz");

        assertTrue(result.isEmpty());
        assertEquals("/%2E%2E", observedRawPath.get());
    }

    private void respondWithMetadata(String packagePath, String version, String tarballPath) {
        String body = "{\"versions\":{\"" + version + "\":{\"dist\":{\"tarball\":\"http://ignored" + tarballPath
                + "\"}}}}";
        backend.createContext(packagePath, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private VerdaccioSidecarManager manager() {
        return new VerdaccioSidecarManager(containerBuilder, lifecycleManager, config);
    }

    private VerdaccioSidecarClient client(VerdaccioSidecarManager manager) {
        return new VerdaccioSidecarClient(manager, config, new ObjectMapper(), HttpClient.newHttpClient());
    }

    @SuppressWarnings("unchecked")
    private static void seedPooledContainer(VerdaccioSidecarManager manager, String key, String containerId,
                                              String url) throws Exception {
        Field poolField = VerdaccioSidecarManager.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        PerKeyContainerPool pool = (PerKeyContainerPool) poolField.get(manager);

        Field containersField = PerKeyContainerPool.class.getDeclaredField("containers");
        containersField.setAccessible(true);
        ConcurrentHashMap<String, PerKeyContainerPool.StartedContainer> containers =
                (ConcurrentHashMap<String, PerKeyContainerPool.StartedContainer>) containersField.get(pool);
        containers.put(key, new PerKeyContainerPool.StartedContainer(containerId, url));
    }
}
