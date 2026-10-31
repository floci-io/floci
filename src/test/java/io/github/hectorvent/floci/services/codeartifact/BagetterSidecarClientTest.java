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
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /**
     * BaGetter's flat container (what {@link #respondWithVersions} fakes) always returns
     * normalized, lowercased version strings, but its registration resource's {@code
     * catalogEntry.version} keeps the original publish casing. A mixed-case prerelease like
     * {@code 1.0.0-Beta} must still have its published time found when looked up by the
     * flat-container's normalized {@code 1.0.0-beta}.
     */
    @Test
    void listPackageVersionsFindsAPublishedTimeForAMixedCasePrereleaseVersion() throws Exception {
        BagetterSidecarManager manager = manager();
        seedPooledContainer(manager, "nuget-repo-1", "tracked-bagetter-1", backendUrl);
        respondWithVersions("demo-pkg", "1.0.0-beta");
        respondWithRegistration("demo-pkg", "1.0.0-Beta", "2026-01-15T10:00:00.0000000+00:00");

        List<RepositorySidecarManager.PackageVersionTimestamp> result = client(manager)
                .listPackageVersions("nuget-repo-1", "dom", "repo", null, "demo-pkg");

        assertEquals(1, result.size());
        assertEquals("1.0.0-beta", result.get(0).version());
        assertEquals(Instant.parse("2026-01-15T10:00:00Z"), result.get(0).publishedTime());
    }

    /**
     * {@code getJsonAbsolute}'s 404-only "nothing there" handling must not also swallow a real
     * upstream failure: an auth or server error on the registration resource must surface as an
     * error, the same way a non-404 failure on the flat container already does elsewhere in this
     * client, rather than silently reporting every version as having no publish time.
     */
    @Test
    void listPackageVersionsThrowsWhenTheRegistrationResourceFailsWithARealError() throws Exception {
        BagetterSidecarManager manager = manager();
        seedPooledContainer(manager, "nuget-repo-1", "tracked-bagetter-1", backendUrl);
        respondWithVersions("demo-pkg", "1.0.0");
        backend.createContext("/v3/registration/demo-pkg/index.json", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        assertThrows(IllegalStateException.class, () -> client(manager)
                .listPackageVersions("nuget-repo-1", "dom", "repo", null, "demo-pkg"));
    }

    private void respondWithRegistration(String packageName, String version, String published) {
        String json = "{\"items\":[{\"items\":[{\"catalogEntry\":{\"version\":\"" + version + "\",\"published\":\""
                + published + "\"}}]}]}";
        backend.createContext("/v3/registration/" + packageName + "/index.json", exchange -> {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private void respondWithVersions(String packageName, String... versions) {
        String json = "{\"versions\":[" + String.join(",",
                Arrays.stream(versions).map(v -> "\"" + v + "\"").toArray(String[]::new)) + "]}";
        backend.createContext("/v3/package/" + packageName + "/index.json", exchange -> {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    /**
     * Raised by Greptile's review: the real NuGet V3 Search resource excludes prerelease versions
     * whenever {@code prerelease} is omitted, so a package whose only published version is a
     * prerelease would never come back from {@link BagetterSidecarClient#listPackages} without it.
     */
    @Test
    void listPackagesIncludesAPackageWhoseOnlyVersionIsAPrerelease() throws Exception {
        BagetterSidecarManager manager = manager();
        seedPooledContainer(manager, "nuget-repo-1", "tracked-bagetter-1", backendUrl);
        AtomicReference<String> requestedQuery = new AtomicReference<>();
        backend.createContext("/v3/search", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            requestedQuery.set(query);
            boolean firstPage = query.contains("skip=0");
            byte[] body = (firstPage ? "{\"data\":[{\"id\":\"demo-pkg\"}]}" : "{\"data\":[]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        List<RepositorySidecarManager.PackageIdentifier> packages = client(manager).listPackages("nuget-repo-1",
                "dom", "repo");

        assertEquals(1, packages.size());
        assertEquals("demo-pkg", packages.get(0).packageName());
        assertTrue(requestedQuery.get().contains("prerelease=true"));
    }

    /**
     * Raised by hectorvent's review: every CodeArtifact repository gets a nuget sidecar id at
     * creation regardless of whether it is ever actually used through nuget, so {@code
     * ListPackages} with no format filter must not start this repository's own BaGetter
     * container just to find out it was never used. No context is registered on {@code backend}
     * at all here: a real container start would have nothing to connect to and throw.
     */
    @Test
    void listPackagesReturnsEmptyWithoutStartingAContainerThatWasNeverUsed() {
        BagetterSidecarManager manager = manager();

        List<RepositorySidecarManager.PackageIdentifier> packages = client(manager).listPackages("nuget-repo-1",
                "dom", "repo");

        assertTrue(packages.isEmpty());
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
