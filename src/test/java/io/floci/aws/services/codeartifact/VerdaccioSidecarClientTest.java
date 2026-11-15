package io.floci.aws.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.PerKeyContainerPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
     * {@link #fetchPackageVersionAssetGetsTheUnscopedTarballPath}'s own streamed sibling: same
     * wire behavior, but the caller gets an open {@link InputStream} plus the real {@code
     * Content-Length} instead of an already-resident {@code byte[]}.
     */
    @Test
    void fetchPackageVersionAssetStreamedGetsTheUnscopedTarballPath() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", backendUrl);
        respondWithMetadata("/demo-pkg", "1.0.0", "/demo-pkg/-/demo-pkg-1.0.0.tgz");
        backend.createContext("/demo-pkg/-/demo-pkg-1.0.0.tgz", exchange -> {
            byte[] body = "tarball bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Optional<RepositorySidecarManager.StreamedAsset> result = client(manager).fetchPackageVersionAssetStreamed(
                "npm-repo-1", "dom", "repo", null, "demo-pkg", "1.0.0", "demo-pkg-1.0.0.tgz");

        assertTrue(result.isPresent());
        try (InputStream in = result.get().content()) {
            assertEquals("tarball bytes", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(13, result.get().contentLength());
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

    /**
     * Found by hectorvent's review of the npm external-connection work: an unconditional {@code
     * dist-tags.latest} in every write's own PUT body used to clobber a real local {@code latest}
     * the moment any other version was copied or retained (confirmed live against the pinned
     * image: Verdaccio preserves an existing package's own {@code dist-tags.latest} when a write
     * omits the field, see {@code VerdaccioSidecarClient#storeVersion}'s own javadoc). This reads
     * the exact body this client sends, not just the end-to-end result, so a future caller that
     * reintroduces a hardcoded {@code dist-tags.latest} fails here directly rather than only ever
     * showing up as a flaky-looking order-dependent symptom in a live test.
     */
    @Test
    void storePackageVersionAssetSendsNoDistTagsOfItsOwn() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", backendUrl);
        AtomicReference<String> observedBody = new AtomicReference<>();
        backend.createContext("/demo-pkg", exchange -> {
            observedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });

        client(manager).storePackageVersionAsset("npm-repo-1", "dom", "repo", null, "demo-pkg", "1.0.0",
                "demo-pkg-1.0.0.tgz", "tarball bytes".getBytes(StandardCharsets.UTF_8));

        JsonNode sentBody = new ObjectMapper().readTree(observedBody.get());
        assertTrue(sentBody.path("dist-tags").isMissingNode(), "must not send its own dist-tags: " + observedBody.get());
    }

    /**
     * {@code storePackageVersionAsset(..., Path, long)}'s own streamed body, built from explicit
     * JSON prefix/suffix fragments around a piped base64 encoder, not {@code mapper
     * .writeValueAsBytes} the way the {@code byte[]} overload's body still is, must still be
     * byte-for-byte the same request body a real Verdaccio instance would otherwise see from the
     * {@code byte[]} overload for the exact same content: this sends both for identical input and
     * compares the two captured request bodies directly, rather than only checking the streamed
     * one parses as valid JSON with the fields it expects.
     */
    @Test
    void storePackageVersionAssetFromAPathSendsTheExactSameBodyAsTheByteArrayOverload() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", backendUrl);
        byte[] content = "tarball bytes, twice over".getBytes(StandardCharsets.UTF_8);
        List<String> observedBodies = new CopyOnWriteArrayList<>();
        backend.createContext("/demo-pkg", exchange -> {
            observedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });

        client(manager).storePackageVersionAsset("npm-repo-1", "dom", "repo", null, "demo-pkg", "1.0.0",
                "demo-pkg-1.0.0.tgz", content);

        Path staged = Files.createTempFile("verdaccio-test-", ".bin");
        try {
            Files.write(staged, content);
            client(manager).storePackageVersionAsset("npm-repo-1", "dom", "repo", null, "demo-pkg", "1.0.0",
                    "demo-pkg-1.0.0.tgz", staged, content.length);
        } finally {
            Files.deleteIfExists(staged);
        }

        assertEquals(2, observedBodies.size());
        assertEquals(observedBodies.get(0), observedBodies.get(1));
        JsonNode sentBody = new ObjectMapper().readTree(observedBodies.get(1));
        assertEquals("application/octet-stream", sentBody.path("_attachments")
                .path("demo-pkg-1.0.0.tgz").path("content_type").asText());
        assertEquals(content.length, sentBody.path("_attachments").path("demo-pkg-1.0.0.tgz")
                .path("length").asLong());
        assertEquals(Base64.getEncoder().encodeToString(content), sentBody.path("_attachments")
                .path("demo-pkg-1.0.0.tgz").path("data").asText());
    }

    /**
     * Raised by Qodo's review of this PR: {@code base64EncodingStream}'s writer thread used to
     * simply close the pipe on its own read failure, which {@link PipedInputStream} cannot tell
     * apart from the writer finishing normally — a real mid-publish failure (the staged file
     * disappearing out from under it, say) would read as an ordinary clean EOF, silently sending
     * a shorter payload than the {@code shasum}/{@code integrity}/{@code length} fields (computed
     * from the file before this stream ever opened it) still claim. Targets {@code
     * base64EncodingStream} directly (package-private for exactly this): a nonexistent path makes
     * the writer's very first read fail, the simplest reproduction of "the writer failed before
     * producing everything it was supposed to," which is the one property this fix actually
     * changes, regardless of how far into the transfer the real failure happens to land.
     */
    @Test
    void base64EncodingStreamFailsRatherThanReadingAsACleanEofWhenTheFileIsGone() throws IOException {
        Path missing = Files.createTempFile("verdaccio-test-", ".bin");
        Files.delete(missing);

        InputStream encoding = VerdaccioSidecarClient.base64EncodingStream(missing);

        assertThrows(IOException.class, encoding::read);
    }

    /**
     * Mirrors the real npm unpublish mechanism, confirmed against a live Verdaccio container: GET
     * the packument for its {@code _rev}, then {@code DELETE} the package path with that revision
     * appended as {@code /-rev/<rev>}.
     */
    @Test
    void deletePackageFetchesTheRevisionThenDeletesWithIt() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", backendUrl);
        backend.createContext("/demo-pkg", exchange -> {
            byte[] bytes = "{\"_rev\":\"3-abc123\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        AtomicReference<String> deletePath = new AtomicReference<>();
        backend.createContext("/demo-pkg/-rev/3-abc123", exchange -> {
            deletePath.set(exchange.getRequestURI().toString());
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });

        client(manager).deletePackage("npm-repo-1", "dom", "repo", null, "demo-pkg");

        assertEquals("/demo-pkg/-rev/3-abc123", deletePath.get());
    }

    @Test
    void deletePackageThrowsWhenTheSidecarRejectsTheDelete() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", backendUrl);
        backend.createContext("/demo-pkg", exchange -> {
            byte[] bytes = "{\"_rev\":\"3-abc123\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        backend.createContext("/demo-pkg/-rev/3-abc123", exchange -> {
            exchange.sendResponseHeaders(409, -1);
            exchange.close();
        });

        assertThrows(IllegalStateException.class, () -> client(manager).deletePackage("npm-repo-1", "dom", "repo",
                null, "demo-pkg"));
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

    /**
     * Raised by hectorvent's review: every CodeArtifact repository gets an npm sidecar id at
     * creation regardless of whether it is ever actually used through npm, so {@code
     * ListPackages} with no format filter must not start this repository's own Verdaccio
     * container just to find out it was never used. No context is registered on {@code backend}
     * at all here: a real container start would have nothing to connect to and throw.
     */
    @Test
    void listPackagesReturnsEmptyWithoutStartingAContainerThatWasNeverUsed() {
        VerdaccioSidecarManager manager = manager();

        List<RepositorySidecarManager.PackageIdentifier> packages = client(manager).listPackages("npm-repo-1",
                "dom", "repo");

        assertTrue(packages.isEmpty());
    }

    /**
     * Raised by Greptile's review: a real npm {@code dist.tarball} filename is not required to
     * embed its version at all, so resolving a download's archived-status check by guessing a
     * {@code <packageName>-<version>.tgz} shape out of the filename's own text can both miss a
     * real tarball named something else entirely and misread an unrelated version out of one
     * that happens to coincidentally fit that shape. This resolves against the packument's own
     * {@code dist.tarball} instead, confirmed here with a tarball named nothing like its version.
     */
    @Test
    void versionForTarballFilenameResolvesAFilenameThatDoesNotEmbedItsVersion() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", backendUrl);
        respondWithMetadata("/demo-pkg", "1.0.0", "/demo-pkg/-/bundle.tgz");

        Optional<String> version = client(manager).versionForTarballFilename("npm-repo-1", "dom", "repo", null,
                "demo-pkg", "bundle.tgz");

        assertEquals(Optional.of("1.0.0"), version);
    }

    @Test
    void versionForTarballFilenameIsEmptyWhenNoVersionsTarballMatches() throws Exception {
        VerdaccioSidecarManager manager = manager();
        seedPooledContainer(manager, "npm-repo-1", "tracked-verdaccio-1", backendUrl);
        respondWithMetadata("/demo-pkg", "1.0.0", "/demo-pkg/-/demo-pkg-1.0.0.tgz");

        Optional<String> version = client(manager).versionForTarballFilename("npm-repo-1", "dom", "repo", null,
                "demo-pkg", "never-published.tgz");

        assertEquals(Optional.empty(), version);
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
