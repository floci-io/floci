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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Wire-contract tests for {@link BagetterSidecarClient} against a JDK {@code HttpServer} standing in
 * for a running BaGetter container. The container is registered in the manager's pool directly, so
 * readiness resolves to the fake server without any Docker daemon.
 */
class BagetterSidecarClientTest {

    private final ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
    private final ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class);

    private HttpServer backend;
    private String backendUrl;

    @BeforeEach
    void setUpBackend() throws Exception {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/health", exchange -> {
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
    void fetchPackageVersionAssetGetsTheFlatContainerNupkgPath() throws Exception {
        BagetterSidecarManager manager = manager();
        seedPooledContainer(manager, "nuget-repo-1", "tracked-bagetter-1", backendUrl);
        respondWithVersions("demo-pkg", "1.0.0");
        backend.createContext("/v3/package/demo-pkg/1.0.0/demo-pkg.1.0.0.nupkg", exchange -> {
            byte[] body = "nupkg bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("nuget-repo-1", "dom", "repo", null,
                "demo-pkg", "1.0.0", "demo-pkg.1.0.0.nupkg");

        assertTrue(result.isPresent());
        assertEquals("nupkg bytes", new String(result.get(), StandardCharsets.UTF_8));
    }

    /**
     * AWS normalizes NuGet versions (leading zeroes removed): a caller asking for {@code 1.00.0}
     * must still find the version BaGetter itself lists as {@code 1.0.0}.
     */
    @Test
    void fetchPackageVersionAssetMatchesANonNormalizedRequestedVersion() throws Exception {
        BagetterSidecarManager manager = manager();
        seedPooledContainer(manager, "nuget-repo-1", "tracked-bagetter-1", backendUrl);
        respondWithVersions("demo-pkg", "1.0.0");
        backend.createContext("/v3/package/demo-pkg/1.0.0/demo-pkg.1.0.0.nupkg", exchange -> {
            byte[] body = "nupkg bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("nuget-repo-1", "dom", "repo", null,
                "demo-pkg", "1.00.0", "demo-pkg.1.0.0.nupkg");

        assertTrue(result.isPresent());
    }

    /**
     * The asset name must equal the one deterministic name AWS documents for a given package and
     * version; any other requested name (even a real file that happens to exist under a different
     * name) is empty.
     */
    @Test
    void fetchPackageVersionAssetReturnsEmptyWhenTheAssetNameDoesNotMatchTheNormalizedForm() throws Exception {
        BagetterSidecarManager manager = manager();
        seedPooledContainer(manager, "nuget-repo-1", "tracked-bagetter-1", backendUrl);
        respondWithVersions("demo-pkg", "1.0.0");

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("nuget-repo-1", "dom", "repo", null,
                "demo-pkg", "1.0.0", "demo-pkg.nupkg");

        assertTrue(result.isEmpty());
    }

    @Test
    void fetchPackageVersionAssetReturnsEmptyWhenTheVersionWasNeverPublished() throws Exception {
        BagetterSidecarManager manager = manager();
        seedPooledContainer(manager, "nuget-repo-1", "tracked-bagetter-1", backendUrl);
        respondWithVersions("demo-pkg", "1.0.0");

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("nuget-repo-1", "dom", "repo", null,
                "demo-pkg", "9.9.9", "demo-pkg.9.9.9.nupkg");

        assertTrue(result.isEmpty());
    }

    @Test
    void packageExistsTrueWhenTheFlatContainerListsAtLeastOneVersion() throws Exception {
        BagetterSidecarManager manager = manager();
        seedPooledContainer(manager, "nuget-repo-1", "tracked-bagetter-1", backendUrl);
        respondWithVersions("demo-pkg", "1.0.0");

        assertTrue(client(manager).packageExists("nuget-repo-1", "dom", "repo", null, "demo-pkg"));
    }

    @Test
    void packageExistsFalseWhenTheFlatContainerHasNeverHeardOfIt() throws Exception {
        BagetterSidecarManager manager = manager();
        seedPooledContainer(manager, "nuget-repo-1", "tracked-bagetter-1", backendUrl);
        backend.createContext("/v3/package/never-published/index.json", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        assertFalse(client(manager).packageExists("nuget-repo-1", "dom", "repo", null, "never-published"));
    }

    @Test
    void packageVersionExistsMatchesANonNormalizedRequestedVersion() throws Exception {
        BagetterSidecarManager manager = manager();
        seedPooledContainer(manager, "nuget-repo-1", "tracked-bagetter-1", backendUrl);
        respondWithVersions("demo-pkg", "1.0.0");

        assertTrue(client(manager).packageVersionExists("nuget-repo-1", "dom", "repo", null, "demo-pkg", "1.00.0"));
    }

    @Test
    void packageVersionExistsFalseForAVersionThatWasNeverPublished() throws Exception {
        BagetterSidecarManager manager = manager();
        seedPooledContainer(manager, "nuget-repo-1", "tracked-bagetter-1", backendUrl);
        respondWithVersions("demo-pkg", "1.0.0");

        assertFalse(client(manager).packageVersionExists("nuget-repo-1", "dom", "repo", null, "demo-pkg", "9.9.9"));
    }

    /**
     * {@code CodeArtifactNuGetController}'s push handler synchronizes on this exact object for a
     * repository, the same one {@link BagetterSidecarClient#deletePackage} itself takes, so a
     * push and a delete for the same repository can never interleave; a different repository
     * must still get its own lock rather than blocking behind an unrelated one.
     */
    @Test
    void lockForReturnsTheSameObjectForOneRepositoryAndDifferentObjectsForDifferentOnes() {
        BagetterSidecarClient client = client(manager());

        Object repo1Again = client.lockFor("nuget-repo-1");

        assertSame(client.lockFor("nuget-repo-1"), repo1Again);
        assertNotSame(client.lockFor("nuget-repo-1"), client.lockFor("nuget-repo-2"));
    }

    @Test
    void deletePackageDeletesEveryVersionTheFlatContainerLists() throws Exception {
        BagetterSidecarManager manager = manager();
        seedPooledContainer(manager, "nuget-repo-1", "tracked-bagetter-1", backendUrl);
        respondWithVersions("demo-pkg", "1.0.0", "2.0.0");
        List<String> deletedPaths = new CopyOnWriteArrayList<>();
        backend.createContext("/api/v2/package/demo-pkg", exchange -> {
            deletedPaths.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        client(manager).deletePackage("nuget-repo-1", "dom", "repo", null, "demo-pkg");

        assertEquals(List.of("/api/v2/package/demo-pkg/1.0.0", "/api/v2/package/demo-pkg/2.0.0"),
                deletedPaths.stream().sorted().toList());
    }

    @Test
    void fetchPackageVersionReadmeReturnsTheEmbeddedReadmeFileWhenOneExists() throws Exception {
        BagetterSidecarManager manager = manager();
        seedPooledContainer(manager, "nuget-repo-1", "tracked-bagetter-1", backendUrl);
        respondWithVersions("demo-pkg", "1.0.0");
        backend.createContext("/v3/package/demo-pkg/1.0.0/readme", exchange -> {
            byte[] body = "# Real readme".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Optional<String> result = client(manager).fetchPackageVersionReadme("nuget-repo-1", "dom", "repo", null,
                "demo-pkg", "1.0.0");

        assertEquals(Optional.of("# Real readme"), result);
    }

    @Test
    void fetchPackageVersionReadmeFallsBackToTheNuspecDescriptionWhenNoReadmeFileIsEmbedded() throws Exception {
        BagetterSidecarManager manager = manager();
        seedPooledContainer(manager, "nuget-repo-1", "tracked-bagetter-1", backendUrl);
        respondWithVersions("demo-pkg", "1.0.0");
        backend.createContext("/v3/package/demo-pkg/1.0.0/readme", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        String nuspec = "<?xml version=\"1.0\"?><package><metadata><id>demo-pkg</id><version>1.0.0</version>"
                + "<description>A long description.</description></metadata></package>";
        backend.createContext("/v3/package/demo-pkg/1.0.0/demo-pkg.nuspec", exchange -> {
            byte[] body = nuspec.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Optional<String> result = client(manager).fetchPackageVersionReadme("nuget-repo-1", "dom", "repo", null,
                "demo-pkg", "1.0.0");

        assertEquals(Optional.of("A long description."), result);
    }

    @Test
    void fetchPackageVersionReadmeReturnsEmptyStringWhenNeitherReadmeNorNuspecAreReachable() throws Exception {
        BagetterSidecarManager manager = manager();
        seedPooledContainer(manager, "nuget-repo-1", "tracked-bagetter-1", backendUrl);
        respondWithVersions("demo-pkg", "1.0.0");
        backend.createContext("/v3/package/demo-pkg/1.0.0/readme", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        backend.createContext("/v3/package/demo-pkg/1.0.0/demo-pkg.nuspec", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        Optional<String> result = client(manager).fetchPackageVersionReadme("nuget-repo-1", "dom", "repo", null,
                "demo-pkg", "1.0.0");

        assertEquals(Optional.of(""), result);
    }

    @Test
    void fetchPackageVersionReadmeReturnsEmptyWhenTheVersionWasNeverPublished() throws Exception {
        BagetterSidecarManager manager = manager();
        seedPooledContainer(manager, "nuget-repo-1", "tracked-bagetter-1", backendUrl);
        respondWithVersions("demo-pkg", "1.0.0");

        Optional<String> result = client(manager).fetchPackageVersionReadme("nuget-repo-1", "dom", "repo", null,
                "demo-pkg", "9.9.9");

        assertTrue(result.isEmpty());
    }

    private void respondWithVersions(String packageName, String... versions) {
        String json = "{\"versions\":[" + String.join(",",
                java.util.Arrays.stream(versions).map(v -> "\"" + v + "\"").toArray(String[]::new)) + "]}";
        backend.createContext("/v3/package/" + packageName + "/index.json", exchange -> {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private BagetterSidecarManager manager() {
        return new BagetterSidecarManager(containerBuilder, lifecycleManager, config);
    }

    private BagetterSidecarClient client(BagetterSidecarManager manager) {
        return new BagetterSidecarClient(manager, new ObjectMapper(), HttpClient.newHttpClient());
    }

    @SuppressWarnings("unchecked")
    private static void seedPooledContainer(BagetterSidecarManager manager, String key, String containerId,
                                              String url) throws Exception {
        Field poolField = BagetterSidecarManager.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        PerKeyContainerPool pool = (PerKeyContainerPool) poolField.get(manager);

        Field containersField = PerKeyContainerPool.class.getDeclaredField("containers");
        containersField.setAccessible(true);
        ConcurrentHashMap<String, PerKeyContainerPool.StartedContainer> containers =
                (ConcurrentHashMap<String, PerKeyContainerPool.StartedContainer>) containersField.get(pool);
        containers.put(key, new PerKeyContainerPool.StartedContainer(containerId, url));
    }
}
