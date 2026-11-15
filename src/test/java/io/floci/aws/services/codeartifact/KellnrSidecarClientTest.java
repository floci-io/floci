package io.floci.aws.services.codeartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.PerKeyContainerPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Wire-contract tests for {@link KellnrSidecarClient} against a JDK {@code HttpServer} standing
 * in for a running Kellnr container, shaped exactly like the real responses captured live against
 * a real Kellnr container earlier in this project. The container is registered in the manager's
 * pool directly, so readiness resolves to the fake server without any Docker daemon.
 */
class KellnrSidecarClientTest {

    private final ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
    private final ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class);

    private HttpServer backend;
    private String backendUrl;

    @BeforeEach
    void setUpBackend() throws Exception {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/api/v1/crates/config.json", exchange -> {
            byte[] body = "{\"dl\":\"/api/v1/crates/dl\",\"api\":\"\",\"auth-required\":false}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
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
    void packageVersionExistsTrueForAPublishedVersion() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", backendUrl);
        respondWithSparseIndex("democrate", "1.0.0");

        assertTrue(client(manager).packageVersionExists("cargo-repo-1", "dom", "repo", null, "democrate", "1.0.0"));
    }

    @Test
    void packageVersionExistsFalseForAVersionThatWasNeverPublished() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", backendUrl);
        respondWithSparseIndex("democrate", "1.0.0");

        assertFalse(client(manager).packageVersionExists("cargo-repo-1", "dom", "repo", null, "democrate", "9.9.9"));
    }

    @Test
    void packageExistsFalseWhenTheSparseIndexHasNeverBeenPublished() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", backendUrl);
        backend.createContext("/api/v1/crates/de/mo/democrate", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        assertFalse(client(manager).packageExists("cargo-repo-1", "dom", "repo", null, "democrate"));
    }

    @Test
    void listPackagesIsEmptyWhenTheRepositoryHasNoRunningContainerYet() {
        KellnrSidecarManager manager = manager();

        assertTrue(client(manager).listPackages("cargo-repo-1", "dom", "repo").isEmpty());
    }

    @Test
    void listPackagesNamesEveryCrateAcrossAPaginatedReadOfTheRealListingRoute() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", backendUrl);
        List<String> requestedQueries = new CopyOnWriteArrayList<>();
        backend.createContext("/api/v1/ui/crates", exchange -> {
            requestedQueries.add(exchange.getRequestURI().getQuery());
            byte[] body;
            if (exchange.getRequestURI().getQuery().contains("page=0")) {
                body = ("{\"crates\":[{\"name\":\"democrate\",\"version\":\"1.0.0\"},"
                        + "{\"name\":\"othercrate\",\"version\":\"2.0.0\"}],\"page_size\":100,\"page\":0}")
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                body = "{\"crates\":[],\"page_size\":100,\"page\":1}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        List<RepositorySidecarManager.PackageIdentifier> packages = client(manager).listPackages("cargo-repo-1",
                "dom", "repo");

        assertEquals(List.of(new RepositorySidecarManager.PackageIdentifier(null, "democrate"),
                new RepositorySidecarManager.PackageIdentifier(null, "othercrate")), packages);
        assertEquals(2, requestedQueries.size());
    }

    @Test
    void fetchPackageVersionAssetGetsTheRealDownloadPath() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", backendUrl);
        respondWithSparseIndex("democrate", "1.0.0");
        backend.createContext("/api/v1/crates/dl/democrate/1.0.0/download", exchange -> {
            byte[] body = "crate bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("cargo-repo-1", "dom", "repo", null,
                "democrate", "1.0.0", "democrate-1.0.0.crate");

        assertTrue(result.isPresent());
        assertEquals("crate bytes", new String(result.get(), StandardCharsets.UTF_8));
    }

    @Test
    void fetchPackageVersionAssetEmptyWhenTheAssetNameIsNotTheDeterministicForm() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", backendUrl);
        respondWithSparseIndex("democrate", "1.0.0");

        Optional<byte[]> result = client(manager).fetchPackageVersionAsset("cargo-repo-1", "dom", "repo", null,
                "democrate", "1.0.0", "wrong-name.crate");

        assertTrue(result.isEmpty());
    }

    /**
     * {@link #fetchPackageVersionAssetGetsTheRealDownloadPath}'s own streamed sibling: same wire
     * behavior, but the caller gets an open {@link InputStream} plus the real {@code
     * Content-Length} instead of an already-resident {@code byte[]}.
     */
    @Test
    void fetchPackageVersionAssetStreamedGetsTheRealDownloadPath() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", backendUrl);
        respondWithSparseIndex("democrate", "1.0.0");
        backend.createContext("/api/v1/crates/dl/democrate/1.0.0/download", exchange -> {
            byte[] body = "crate bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Optional<RepositorySidecarManager.StreamedAsset> result = client(manager).fetchPackageVersionAssetStreamed(
                "cargo-repo-1", "dom", "repo", null, "democrate", "1.0.0", "democrate-1.0.0.crate");

        assertTrue(result.isPresent());
        try (InputStream in = result.get().content()) {
            assertEquals("crate bytes", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(11, result.get().contentLength());
    }

    @Test
    void listPackageVersionAssetNamesNamesTheOneDeterministicAsset() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", backendUrl);
        respondWithSparseIndex("democrate", "1.0.0");

        List<String> result = client(manager).listPackageVersionAssetNames("cargo-repo-1", "dom", "repo", null,
                "democrate", "1.0.0");

        assertEquals(List.of("democrate-1.0.0.crate"), result);
    }

    @Test
    void listPackageVersionsReadsEachEntrysPubtime() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", backendUrl);
        respondWithSparseIndexLines("democrate",
                sparseIndexLine("democrate", "1.0.0", "2026-01-15T10:00:00Z"),
                sparseIndexLine("democrate", "2.0.0", "2026-02-20T10:00:00Z"));

        List<RepositorySidecarManager.PackageVersionTimestamp> result = client(manager)
                .listPackageVersions("cargo-repo-1", "dom", "repo", null, "democrate");

        assertEquals(2, result.size());
        assertEquals("1.0.0", result.get(0).version());
        assertEquals(Instant.parse("2026-01-15T10:00:00Z"), result.get(0).publishedTime());
        assertEquals("2.0.0", result.get(1).version());
        assertEquals(Instant.parse("2026-02-20T10:00:00Z"), result.get(1).publishedTime());
    }

    /**
     * The real crates.io sparse-index bucketing rule for a 4+ character name: first two
     * characters, then next two, then the name itself, confirmed live against a real Kellnr
     * container earlier in this project.
     */
    @Test
    void sparseIndexPathBucketsAFourCharacterOrLongerNameByItsFirstFourCharacters() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", backendUrl);
        backend.createContext("/api/v1/crates/se/rd/serde", exchange -> {
            byte[] body = sparseIndexLine("serde", "1.0.0", "2026-01-15T10:00:00Z").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        assertTrue(client(manager).packageVersionExists("cargo-repo-1", "dom", "repo", null, "serde", "1.0.0"));
    }

    @Test
    void storePackageVersionAssetPublishesTheRealBinaryWireBody() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", backendUrl);
        AtomicReference<String> receivedCookie = new AtomicReference<>();
        AtomicReference<byte[]> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedAuthorization = new AtomicReference<>();
        respondToLogin();
        respondToAddToken(cookie -> receivedCookie.set(cookie));
        backend.createContext("/api/v1/crates/new", exchange -> {
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            byte[] body = "{\"warnings\":null}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        client(manager).storePackageVersionAsset("cargo-repo-1", "dom", "repo", null, "democrate", "1.0.0",
                "democrate-1.0.0.crate", "crate bytes".getBytes(StandardCharsets.UTF_8));

        // The token-mint call used the admin session cookie, and the real publish call used the
        // minted token (no "Bearer" prefix, confirmed live against a real Kellnr container) rather
        // than the cookie itself.
        assertEquals("kellnr_session_id=test-session", receivedCookie.get());
        assertEquals("tok-1", receivedAuthorization.get());
        byte[] body = receivedBody.get();
        ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        int metadataLength = buffer.getInt();
        byte[] metadata = new byte[metadataLength];
        buffer.get(metadata);
        assertTrue(new String(metadata, StandardCharsets.UTF_8).contains("\"name\":\"democrate\""));
        assertTrue(new String(metadata, StandardCharsets.UTF_8).contains("\"vers\":\"1.0.0\""));
        int crateLength = buffer.getInt();
        byte[] crate = new byte[crateLength];
        buffer.get(crate);
        assertEquals("crate bytes", new String(crate, StandardCharsets.UTF_8));
    }

    /**
     * {@link KellnrSidecarClient#storePackageVersionAsset(String, String, String, String, String,
     * String, String, byte[])}'s own streamed sibling: the staged file's real content ends up in
     * the same binary wire body.
     */
    @Test
    void storePackageVersionAssetStreamedSendsTheStagedFilesRealContent() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", backendUrl);
        Path staged = Files.createTempFile("kellnr-client-test-", ".tmp");
        Files.writeString(staged, "staged crate bytes");
        respondToLogin();
        respondToAddToken(cookie -> { });
        AtomicReference<byte[]> receivedBody = new AtomicReference<>();
        backend.createContext("/api/v1/crates/new", exchange -> {
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            byte[] body = "{\"warnings\":null}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        client(manager).storePackageVersionAsset("cargo-repo-1", "dom", "repo", null, "democrate", "1.0.0",
                "democrate-1.0.0.crate", staged, Files.size(staged));

        ByteBuffer buffer = ByteBuffer.wrap(receivedBody.get()).order(ByteOrder.LITTLE_ENDIAN);
        int metadataLength = buffer.getInt();
        buffer.position(buffer.position() + metadataLength);
        int crateLength = buffer.getInt();
        byte[] crate = new byte[crateLength];
        buffer.get(crate);
        assertEquals("staged crate bytes", new String(crate, StandardCharsets.UTF_8));
    }

    @Test
    void storePackageVersionAssetMintsOnlyOneTokenAcrossSeveralPublishes() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", backendUrl);
        respondToLogin();
        List<String> mintedTokenNames = new CopyOnWriteArrayList<>();
        backend.createContext("/api/v1/user/add_token", exchange -> {
            mintedTokenNames.add("minted");
            byte[] body = "{\"name\":\"floci\",\"token\":\"tok-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        backend.createContext("/api/v1/crates/new", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"warnings\":null}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        KellnrSidecarClient client = client(manager);

        client.storePackageVersionAsset("cargo-repo-1", "dom", "repo", null, "democrate", "1.0.0",
                "democrate-1.0.0.crate", "a".getBytes(StandardCharsets.UTF_8));
        client.storePackageVersionAsset("cargo-repo-1", "dom", "repo", null, "democrate", "2.0.0",
                "democrate-2.0.0.crate", "b".getBytes(StandardCharsets.UTF_8));

        assertEquals(1, mintedTokenNames.size());
    }

    @Test
    void deletePackageDeletesViaTheAdminRouteWithTheSessionCookie() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", backendUrl);
        respondToLogin();
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        AtomicReference<String> receivedCookie = new AtomicReference<>();
        backend.createContext("/api/v1/ui/delete_crate", exchange -> {
            receivedQuery.set(exchange.getRequestURI().getQuery());
            receivedCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        client(manager).deletePackage("cargo-repo-1", "dom", "repo", null, "democrate");

        assertEquals("name=democrate", receivedQuery.get());
        assertEquals("kellnr_session_id=test-session", receivedCookie.get());
    }

    @Test
    void deletePackageVersionAssetDeletesViaTheAdminRouteWithBothNameAndVersion() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", backendUrl);
        respondToLogin();
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        backend.createContext("/api/v1/ui/delete_version", exchange -> {
            receivedQuery.set(exchange.getRequestURI().getQuery());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        client(manager).deletePackageVersionAsset("cargo-repo-1", "dom", "repo", null, "democrate", "1.0.0",
                "democrate-1.0.0.crate");

        assertEquals("name=democrate&version=1.0.0", receivedQuery.get());
    }

    @Test
    void deletePackageToleratesAnAlreadyGoneCoordinate() throws Exception {
        KellnrSidecarManager manager = manager();
        seedPooledContainer(manager, "cargo-repo-1", "tracked-kellnr-1", backendUrl);
        respondToLogin();
        backend.createContext("/api/v1/ui/delete_crate", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        assertFalse(catches(() -> client(manager).deletePackage("cargo-repo-1", "dom", "repo", null, "democrate")));
    }

    /**
     * A future {@code CodeArtifactCargoController}'s own push handler is expected to synchronize
     * on this exact object for a repository, the same one {@link KellnrSidecarClient
     * #storePackageVersionAsset} and {@link KellnrSidecarClient#deletePackage} themselves take, so
     * a push and a delete for the same repository can never interleave; a different repository
     * must still get its own lock rather than blocking behind an unrelated one.
     */
    @Test
    void lockForReturnsTheSameObjectForOneRepositoryAndDifferentObjectsForDifferentOnes() {
        KellnrSidecarClient client = client(manager());

        Object repo1Again = client.lockFor("cargo-repo-1");

        assertSame(client.lockFor("cargo-repo-1"), repo1Again);
        assertNotSame(client.lockFor("cargo-repo-1"), client.lockFor("cargo-repo-2"));
    }

    private static boolean catches(ThrowingRunnable runnable) {
        try {
            runnable.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    private void respondToLogin() {
        backend.createContext("/api/v1/user/login", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Set-Cookie", "kellnr_session_id=test-session; Path=/");
            byte[] body = "{\"user\":\"admin\",\"is_admin\":true,\"is_logged_in\":true}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    private void respondToAddToken(Consumer<String> receivedCookie) {
        backend.createContext("/api/v1/user/add_token", exchange -> {
            receivedCookie.accept(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"name\":\"floci\",\"token\":\"tok-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    private void respondWithSparseIndex(String packageName, String version) {
        respondWithSparseIndexLines(packageName, sparseIndexLine(packageName, version, "2026-01-15T10:00:00Z"));
    }

    private void respondWithSparseIndexLines(String packageName, String... lines) {
        String body = String.join("\n", lines);
        backend.createContext(sparseIndexContextPath(packageName), exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private static String sparseIndexContextPath(String packageName) {
        String first2 = packageName.substring(0, 2);
        String next2 = packageName.substring(2, 4);
        return "/api/v1/crates/" + first2 + "/" + next2 + "/" + packageName;
    }

    private static String sparseIndexLine(String packageName, String version, String pubtime) {
        return "{\"name\":\"" + packageName + "\",\"vers\":\"" + version + "\",\"deps\":[],\"cksum\":\"abc\","
                + "\"features\":{},\"yanked\":false,\"pubtime\":\"" + pubtime + "\",\"v\":1}";
    }

    private KellnrSidecarClient client(KellnrSidecarManager manager) {
        return new KellnrSidecarClient(manager, new ObjectMapper(), HttpClient.newHttpClient());
    }

    private KellnrSidecarManager manager() {
        return new KellnrSidecarManager(containerBuilder, lifecycleManager, config);
    }

    @SuppressWarnings("unchecked")
    private static void seedPooledContainer(KellnrSidecarManager manager, String key, String containerId,
                                              String url) throws Exception {
        Field poolField = KellnrSidecarManager.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        PerKeyContainerPool pool = (PerKeyContainerPool) poolField.get(manager);

        Field containersField = PerKeyContainerPool.class.getDeclaredField("containers");
        containersField.setAccessible(true);
        ConcurrentHashMap<String, PerKeyContainerPool.StartedContainer> containers =
                (ConcurrentHashMap<String, PerKeyContainerPool.StartedContainer>) containersField.get(pool);
        containers.put(key, new PerKeyContainerPool.StartedContainer(containerId, url));
        // The real startContainer() always mints and stores a password together with starting the
        // container; this fake seeding stands in for both at once via the manager's own
        // package-private test seam, not reflection, since that one is new to this class.
        manager.seedAdminPasswordForTest(key, "test-admin-password");
    }
}
