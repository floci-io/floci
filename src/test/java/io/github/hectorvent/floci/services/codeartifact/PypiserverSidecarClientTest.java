package io.github.hectorvent.floci.services.codeartifact;

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
 * Wire-contract tests for {@link PypiserverSidecarClient} against a JDK {@code HttpServer} standing
 * in for a running pypiserver container. The container is registered in the manager's pool
 * directly, so readiness resolves to the fake server without any Docker daemon.
 */
class PypiserverSidecarClientTest {

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
    void fetchPackageVersionAssetGetsTheFlatPackagesPath() throws Exception {
        PypiserverSidecarManager manager = manager();
        seedPooledContainer(manager, "pypi-repo-1", "tracked-pypiserver-1", backendUrl);
        respondWithSimpleIndex("demo-pkg", "demo_pkg-1.0.0.tar.gz");
        backend.createContext("/packages/demo_pkg-1.0.0.tar.gz", exchange -> {
            byte[] body = "sdist bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("pypi-repo-1", "dom", "repo", null, "demo-pkg",
                "1.0.0", "demo_pkg-1.0.0.tar.gz");

        assertTrue(result.isPresent());
        assertEquals("sdist bytes", new String(result.get(), StandardCharsets.UTF_8));
    }

    /**
     * The fix for a real finding: the filename alone was trusted as proof of version, so a caller
     * could request a different (even nonexistent) version and still get back a real file that
     * actually belongs to a version it never named.
     */
    @Test
    void fetchPackageVersionAssetReturnsEmptyWhenTheRequestedVersionDoesNotMatchTheFilename() throws Exception {
        PypiserverSidecarManager manager = manager();
        seedPooledContainer(manager, "pypi-repo-1", "tracked-pypiserver-1", backendUrl);
        respondWithSimpleIndex("demo-pkg", "demo_pkg-1.0.0.tar.gz");
        backend.createContext("/packages/demo_pkg-1.0.0.tar.gz", exchange -> {
            byte[] body = "should never be served".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("pypi-repo-1", "dom", "repo", null, "demo-pkg",
                "9.9.9", "demo_pkg-1.0.0.tar.gz");

        assertTrue(result.isEmpty());
    }

    /**
     * The fix for a real finding: a plain substring check on {@code "-" + version} would wrongly
     * accept this, since {@code pkg-1.0.1-...} literally contains the text {@code -1.0.} even
     * though the file's real version is {@code 1.0.1}, not {@code 1.0}.
     */
    @Test
    void fetchPackageVersionAssetRejectsAVersionThatIsOnlyAPrefixOfTheFilenamesRealVersion() throws Exception {
        PypiserverSidecarManager manager = manager();
        seedPooledContainer(manager, "pypi-repo-1", "tracked-pypiserver-1", backendUrl);
        respondWithSimpleIndex("demo-pkg", "demo_pkg-1.0.1-py3-none-any.whl");
        backend.createContext("/packages/demo_pkg-1.0.1-py3-none-any.whl", exchange -> {
            byte[] body = "should never be served".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("pypi-repo-1", "dom", "repo", null, "demo-pkg",
                "1.0", "demo_pkg-1.0.1-py3-none-any.whl");

        assertTrue(result.isEmpty());
    }

    /**
     * The fix for a real finding on top of the previous one: a PEP 440 pre/post/dev-release
     * suffix also starts right after the matched version text with a letter, not a digit, so the
     * first boundary fix (reject only when followed by another digit) still wrongly accepted
     * {@code 1.0.post1}, {@code 1.0.dev1}, and {@code 1.0.rc1} as if they were plain {@code 1.0}.
     */
    @Test
    void fetchPackageVersionAssetRejectsAVersionThatIsOnlyAPrefixOfAPostReleaseSuffix() throws Exception {
        assertRequestedVersionRejectedAgainstRealVersion("1.0.post1");
    }

    @Test
    void fetchPackageVersionAssetRejectsAVersionThatIsOnlyAPrefixOfADevReleaseSuffix() throws Exception {
        assertRequestedVersionRejectedAgainstRealVersion("1.0.dev1");
    }

    @Test
    void fetchPackageVersionAssetRejectsAVersionThatIsOnlyAPrefixOfAReleaseCandidateSuffix() throws Exception {
        assertRequestedVersionRejectedAgainstRealVersion("1.0.rc1");
    }

    /**
     * The fix for a real finding: {@code +} introduces a PEP 440 local-version segment, so
     * whatever follows it is always part of a longer, different version. {@code 1.0+linux} is
     * not {@code 1.0}, but a {@code +} used to be treated the same as a wheel tag's {@code -} and
     * accepted as ending the version field right there.
     */
    @Test
    void fetchPackageVersionAssetRejectsAVersionThatIsOnlyAPrefixOfALocalVersionSegment() throws Exception {
        assertRequestedVersionRejectedAgainstRealVersion("1.0+linux");
    }

    /**
     * The fix for a real finding: PEP 440 allows a bare {@code -<digits>} right after a release
     * segment, with no keyword at all, as a legacy, implicit way of writing a post-release
     * ({@code 1.0-1} means {@code 1.0.post1}). The old check treated every {@code -} right after
     * a matched version as an unconditional boundary, so this legacy form wasn't recognized as a
     * continuation.
     */
    @Test
    void fetchPackageVersionAssetRejectsAVersionThatIsOnlyAPrefixOfAnImplicitPostRelease() throws Exception {
        assertRequestedVersionRejectedAgainstRealVersion("1.0-1");
    }

    /**
     * Same finding, the explicit-keyword form of a legacy post-release separator: PEP 440 also
     * allows {@code -} (not just {@code .}) directly before {@code post}/{@code dev}/{@code rc}.
     */
    @Test
    void fetchPackageVersionAssetRejectsAVersionThatIsOnlyAPrefixOfAHyphenSeparatedPostRelease() throws Exception {
        assertRequestedVersionRejectedAgainstRealVersion("1.0-post1");
    }

    /**
     * The fix for a real finding: {@code pre}, {@code preview}, and {@code c} are all PEP 440
     * aliases for {@code rc}, and weren't in the continuation list, so a filename using one of
     * them could sail through as if it were the plain requested version.
     */
    @Test
    void fetchPackageVersionAssetRejectsAVersionThatIsOnlyAPrefixOfAPreReleaseAlias() throws Exception {
        assertRequestedVersionRejectedAgainstRealVersion("1.0.pre1");
    }

    @Test
    void fetchPackageVersionAssetRejectsAVersionThatIsOnlyAPrefixOfAPreviewReleaseAlias() throws Exception {
        assertRequestedVersionRejectedAgainstRealVersion("1.0.preview1");
    }

    @Test
    void fetchPackageVersionAssetRejectsAVersionThatIsOnlyAPrefixOfAShorthandReleaseCandidateAlias() throws Exception {
        assertRequestedVersionRejectedAgainstRealVersion("1.0.c1");
    }

    /**
     * Same finding, the post-release side: {@code r} is a PEP 440 alias for {@code post}/{@code
     * rev}, wasn't recognized either, and sits one character shorter than {@code rev} so it needs
     * its own coverage.
     */
    @Test
    void fetchPackageVersionAssetRejectsAVersionThatIsOnlyAPrefixOfAShorthandPostReleaseAlias() throws Exception {
        assertRequestedVersionRejectedAgainstRealVersion("1.0-r1");
    }

    /**
     * The fix for a real finding on the opposite side of the same area: a PEP 427 wheel build tag
     * ({@code digit+ letter*}, placed between the version and the python/abi/platform tags) looks
     * exactly like PEP 440's implicit post-release numeral at the point this check inspects, but
     * actually ends the version right there rather than continuing it. {@code demo_pkg-1.0-1-py3-
     * none-any.whl} is version {@code 1.0} with build tag {@code 1}, not version {@code 1.0.post1}.
     */
    @Test
    void fetchPackageVersionAssetAcceptsAWheelBuildTagRatherThanTreatingItAsAPostRelease() throws Exception {
        PypiserverSidecarManager manager = manager();
        seedPooledContainer(manager, "pypi-repo-1", "tracked-pypiserver-1", backendUrl);
        respondWithSimpleIndex("demo-pkg", "demo_pkg-1.0-1-py3-none-any.whl");
        backend.createContext("/packages/demo_pkg-1.0-1-py3-none-any.whl", exchange -> {
            byte[] body = "wheel bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("pypi-repo-1", "dom", "repo", null, "demo-pkg",
                "1.0", "demo_pkg-1.0-1-py3-none-any.whl");

        assertTrue(result.isPresent());
        assertEquals("wheel bytes", new String(result.get(), StandardCharsets.UTF_8));
    }

    /**
     * The fix for a real finding: a package name can itself contain a run of digits that looks
     * like a version, and a legacy sdist filename (pre-dating the PEP 625 escaping modern
     * {@code demo_pkg-1.0.0.tar.gz}-style filenames in this test class use) keeps the project
     * name's own hyphens literal rather than underscoring them. A package literally named {@code
     * foo-1.0-bar} at version {@code 2.0} then produces the filename {@code
     * foo-1.0-bar-2.0.tar.gz}; the old check only looked at what followed a {@code -<version>}
     * match, so the {@code -1.0-} inside the name portion looked like a complete, valid version
     * {@code 1.0} on its own, serving the version-2.0 asset for a request asking for 1.0. Fixed
     * by also requiring that what precedes the match is exactly the requested package name,
     * normalized the same way the simple-index lookup normalizes it; this filenameEmbedsVersion
     * check now fails before any request is even sent.
     */
    @Test
    void fetchPackageVersionAssetRejectsAVersionThatOnlyMatchesInsideThePackageNamePortion() throws Exception {
        PypiserverSidecarManager manager = manager();
        seedPooledContainer(manager, "pypi-repo-1", "tracked-pypiserver-1", backendUrl);

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("pypi-repo-1", "dom", "repo", null, "foo-1.0-bar",
                "1.0", "foo-1.0-bar-2.0.tar.gz");

        assertTrue(result.isEmpty());
    }

    private void assertRequestedVersionRejectedAgainstRealVersion(String realVersion) throws Exception {
        PypiserverSidecarManager manager = manager();
        seedPooledContainer(manager, "pypi-repo-1", "tracked-pypiserver-1", backendUrl);
        String filename = "demo_pkg-" + realVersion + ".tar.gz";
        respondWithSimpleIndex("demo-pkg", filename);
        backend.createContext("/packages/" + filename, exchange -> {
            byte[] body = "should never be served".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("pypi-repo-1", "dom", "repo", null, "demo-pkg",
                "1.0", filename);

        assertTrue(result.isEmpty(), "version 1.0 must not match " + filename);
    }

    /**
     * The fix for a real finding: the package-index check used to be a plain substring search,
     * which a shorter real filename that happens to be a substring of a different, longer real
     * filename could pass incorrectly.
     */
    @Test
    void fetchPackageVersionAssetRequiresAnExactIndexEntryNotASubstringMatch() throws Exception {
        PypiserverSidecarManager manager = manager();
        seedPooledContainer(manager, "pypi-repo-1", "tracked-pypiserver-1", backendUrl);
        // The index genuinely lists a longer filename that contains the requested, shorter one as
        // a substring; the shorter one was never actually published on its own.
        respondWithSimpleIndex("demo-pkg", "demo_pkg-1.0.0.tar.gz.asc");
        backend.createContext("/packages/demo_pkg-1.0.0.tar.gz", exchange -> {
            byte[] body = "should never be served".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("pypi-repo-1", "dom", "repo", null, "demo-pkg",
                "1.0.0", "demo_pkg-1.0.0.tar.gz");

        assertTrue(result.isEmpty());
    }

    @Test
    void fetchPackageVersionAssetReturnsEmptyWhenThePackageWasNeverUploaded() throws Exception {
        PypiserverSidecarManager manager = manager();
        seedPooledContainer(manager, "pypi-repo-1", "tracked-pypiserver-1", backendUrl);
        backend.createContext("/simple/demo-pkg/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("pypi-repo-1", "dom", "repo", null, "demo-pkg",
                "1.0.0", "missing.whl");

        assertTrue(result.isEmpty());
    }

    /**
     * The fix for a real finding: pypiserver serves every file it holds flatly, regardless of
     * which package it belongs to, so without checking the named package's own simple index first,
     * a caller could name a real file that belongs to a different package in the same repository
     * and get it back under the wrong package's name.
     */
    @Test
    void fetchPackageVersionAssetReturnsEmptyWhenTheFileBelongsToADifferentPackage() throws Exception {
        PypiserverSidecarManager manager = manager();
        seedPooledContainer(manager, "pypi-repo-1", "tracked-pypiserver-1", backendUrl);
        respondWithSimpleIndex("demo-pkg", "demo_pkg-1.0.0.tar.gz");
        backend.createContext("/packages/other_pkg-1.0.0.tar.gz", exchange -> {
            byte[] body = "should never be served".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("pypi-repo-1", "dom", "repo", null, "demo-pkg",
                "1.0.0", "other_pkg-1.0.0.tar.gz");

        assertTrue(result.isEmpty());
    }

    @Test
    void fetchPackageVersionAssetThrowsRatherThanReportingNotFoundOnASidecarServerError() throws Exception {
        PypiserverSidecarManager manager = manager();
        seedPooledContainer(manager, "pypi-repo-1", "tracked-pypiserver-1", backendUrl);
        backend.createContext("/simple/demo-pkg/", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        assertThrows(IllegalStateException.class, () -> client(manager).fetchPackageVersionAsset("pypi-repo-1", "dom",
                "repo", null, "demo-pkg", "1.0.0", "demo_pkg-1.0.0.tar.gz"));
    }

    /**
     * The fix for a real finding: the normalized package name used to be spliced straight into
     * {@code /simple/<normalized>/}. A {@code /} inside {@code packageName} would then be
     * indistinguishable from the real separators around it and could splice in extra path
     * segments. Fixed by percent-encoding the normalized name via {@code encodeSegment} before
     * it's placed in the path, so a {@code /} inside it survives only as a literal {@code %2F}
     * within that one segment. {@code packageName} is {@code a/../b}, not a cleaner {@code ../..}:
     * the PEP 503 normalization this method already applies collapses runs of {@code -_.} to a
     * single {@code -} first, which would otherwise quietly absorb a pure dot-segment attempt
     * before it ever reached the encoding this test is checking.
     */
    @Test
    void fetchPackageVersionAssetTreatsASlashInThePackageNameAsLiteralNotAStructuralSeparator() throws Exception {
        PypiserverSidecarManager manager = manager();
        seedPooledContainer(manager, "pypi-repo-1", "tracked-pypiserver-1", backendUrl);
        AtomicReference<String> observedRawPath = new AtomicReference<>();
        backend.createContext("/", exchange -> {
            observedRawPath.set(exchange.getRequestURI().getRawPath());
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("pypi-repo-1", "dom", "repo", null, "a/../b",
                "1.0.0", "a/-/b-1.0.0.tar.gz");

        assertTrue(result.isEmpty());
        assertEquals("/simple/a%2F-%2Fb/", observedRawPath.get());
    }

    private void respondWithSimpleIndex(String packageName, String filename) {
        String html = "<a href=\"/packages/" + filename + "\">" + filename + "</a>";
        backend.createContext("/simple/" + packageName + "/", exchange -> {
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private PypiserverSidecarManager manager() {
        return new PypiserverSidecarManager(containerBuilder, lifecycleManager, config);
    }

    private PypiserverSidecarClient client(PypiserverSidecarManager manager) {
        return new PypiserverSidecarClient(manager, HttpClient.newHttpClient());
    }

    @SuppressWarnings("unchecked")
    private static void seedPooledContainer(PypiserverSidecarManager manager, String key, String containerId,
                                              String url) throws Exception {
        Field poolField = PypiserverSidecarManager.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        PerKeyContainerPool pool = (PerKeyContainerPool) poolField.get(manager);

        Field containersField = PerKeyContainerPool.class.getDeclaredField("containers");
        containersField.setAccessible(true);
        ConcurrentHashMap<String, PerKeyContainerPool.StartedContainer> containers =
                (ConcurrentHashMap<String, PerKeyContainerPool.StartedContainer>) containersField.get(pool);
        containers.put(key, new PerKeyContainerPool.StartedContainer(containerId, url));
    }
}
