package io.floci.aws.services.dsql.dataplane;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.services.dsql.model.DsqlCluster;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ResourceLock(ParallelLocks.PORTS)
class ExternalDsqlBackendTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void trustedDatabasesAreNotScramOnly() {
        assertFalse(BackendCredentials.TRUST.scramOnly());
    }

    @Test
    void externalModeNeverTouchesDocker() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        ContainerDetector detector = mock(ContainerDetector.class);
        EmulatorConfig config = unreachableExternalServer();
        DsqlDatabases databases = new DsqlDatabases(new DockerDsqlBackend(builder, lifecycle, detector, config),
                new ExternalDsqlBackend(config, Duration.ZERO), config);
        DsqlCluster cluster = cluster();

        assertThrows(SQLException.class, () -> databases.start(cluster));
        assertNotNull(cluster.getBackendSecret(), "the secret is set on the cluster for the caller to persist");
        assertThrows(SQLException.class, () -> databases.rolesToDrop(cluster));
        assertThrows(RuntimeException.class, () -> databases.stop(cluster, true));
        databases.stop(cluster, false);

        verifyNoInteractions(builder, lifecycle, detector);
    }

    @Test
    void derivedPasswordIsStablePerRole() {
        String admin = ExternalDsqlBackend.derive(SECRET, "dabc_admin");

        assertEquals(admin, ExternalDsqlBackend.derive(SECRET, "dabc_admin"));
        assertNotEquals(admin, ExternalDsqlBackend.derive(SECRET, "dabc_app"));
        assertNotEquals(admin, ExternalDsqlBackend.derive(
                Base64.getEncoder().encodeToString(new byte[] {1}), "dabc_admin"));
        assertTrue(admin.matches("[A-Za-z0-9_-]{43}"), admin);
        assertFalse(admin.contains("="));
    }

    @Test
    void verifierIsPostgresqlsScramFormatAndStablePerRole() throws Exception {
        String verifier = ExternalDsqlBackend.verifier(SECRET, "dabc_admin");

        assertEquals(verifier, ExternalDsqlBackend.verifier(SECRET, "dabc_admin"));
        assertNotEquals(verifier, ExternalDsqlBackend.verifier(SECRET, "dabc_app"));
        Matcher parts = Pattern.compile("SCRAM-SHA-256\\$4096:([^$]+)\\$([^:]+):(.+)").matcher(verifier);
        assertTrue(parts.matches(), verifier);
        byte[] salt = Base64.getDecoder().decode(parts.group(1));
        Mac saltMac = Mac.getInstance("HmacSHA256");
        saltMac.init(new SecretKeySpec(Base64.getDecoder().decode(SECRET), "HmacSHA256"));
        assertArrayEquals(Arrays.copyOf(saltMac.doFinal("scram-salt:dabc_admin".getBytes(StandardCharsets.UTF_8)), 16),
                salt);
        // StoredKey and ServerKey as RFC 5802 defines them, from the password a client logs in with.
        String password = ExternalDsqlBackend.derive(SECRET, "dabc_admin");
        byte[] salted = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(password.toCharArray(), salt, 4096, 256)).getEncoded();
        Mac keyMac = Mac.getInstance("HmacSHA256");
        keyMac.init(new SecretKeySpec(salted, "HmacSHA256"));
        byte[] clientKey = keyMac.doFinal("Client Key".getBytes(StandardCharsets.UTF_8));
        byte[] serverKey = keyMac.doFinal("Server Key".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(clientKey),
                Base64.getDecoder().decode(parts.group(2)));
        assertArrayEquals(serverKey, Base64.getDecoder().decode(parts.group(3)));
        assertFalse(verifier.contains(password));
    }

    @Test
    void theOwnershipMarkerNamesTheClustersArn() throws SQLException {
        assertEquals("floci:dsql:arn:aws:dsql:eu-west-1:111122223333:cluster/abcdefghijklmnopqrstuvwxyz",
                ExternalDsqlBackend.marker(cluster()));
        DsqlCluster withoutArn = new DsqlCluster();
        withoutArn.setIdentifier("abc");
        assertThrows(SQLException.class, () -> ExternalDsqlBackend.marker(withoutArn));
    }

    @Test
    void trustedDatabasesWantNeitherPasswordNorVerifier() {
        assertNull(BackendCredentials.TRUST.passwordFor("admin"));
        assertNull(BackendCredentials.TRUST.verifierFor("admin"));
    }

    @Test
    void aSecretIsGeneratedOnceAndKept() {
        DsqlCluster cluster = cluster();
        cluster.setBackendSecret(SECRET);

        assertThrows(SQLException.class, () -> new ExternalDsqlBackend(unreachableExternalServer(), Duration.ZERO).start(cluster));

        assertEquals(SECRET, cluster.getBackendSecret());
    }

    @Test
    void aServerOlderThanPostgresql16IsRefusedNamingItsVersion() throws SQLException {
        Connection server = serverOfVersion(15, "15.8");

        SQLException refused = assertThrows(SQLException.class,
                () -> ExternalDsqlBackend.requireSupportedVersion(server, "db.example:5433"));

        assertEquals("Floci's external DSQL backend needs PostgreSQL 16 or later; db.example:5433 runs 15.8",
                refused.getMessage());
        verify(server).getMetaData();
        verifyNoMoreInteractions(server);
    }

    @Test
    void postgresql16AndLaterAreAccepted() throws SQLException {
        ExternalDsqlBackend.requireSupportedVersion(serverOfVersion(16, "16.4"), "db.example:5432");
        ExternalDsqlBackend.requireSupportedVersion(serverOfVersion(17, "17.2"), "db.example:5432");
    }

    @Test
    void aServerThatAcceptsButNeverAnswersDoesNotHoldACallForever() throws Exception {
        // A listening socket that is never accepted from: TCP connects, and no reply ever comes, as from
        // a paused sidecar.
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            EmulatorConfig config = unreachableExternalServer();
            when(config.services().dsql().external().port()).thenReturn(silent.getLocalPort());
            ExternalDsqlBackend backend = new ExternalDsqlBackend(config, Duration.ofSeconds(1));
            DsqlCluster cluster = cluster();

            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                assertThrows(SQLException.class, () -> backend.start(cluster));
                assertThrows(SQLException.class, () -> backend.rolesToDrop(cluster));
                assertThrows(IllegalStateException.class, () -> backend.stop(cluster, true));
            }, "every call gives up on the server instead of waiting for it forever");
            assertEquals(0, backend.lockedClusters(), "and lets go of the cluster's lock");
        }
    }

    @Test
    void aSingleConnectionAttemptNeverOutlastsTheStartsWait() {
        assertEquals(1, ExternalDsqlBackend.loginTimeoutSeconds(Duration.ZERO), "zero would mean no limit");
        assertEquals(1, ExternalDsqlBackend.loginTimeoutSeconds(Duration.ofMillis(300)));
        assertEquals(5, ExternalDsqlBackend.loginTimeoutSeconds(Duration.ofSeconds(5)));
        assertEquals(10, ExternalDsqlBackend.loginTimeoutSeconds(ExternalDsqlBackend.READY_TIMEOUT));
    }

    @Test
    void theStartWaitsAsLongAsTheDockerBackendDoes() {
        assertEquals(Duration.ofSeconds(90), ExternalDsqlBackend.READY_TIMEOUT);
    }

    @Test
    void aClustersWorkWaitsForTheWorkAlreadyHoldingItsLock() throws Exception {
        ExternalDsqlBackend backend = new ExternalDsqlBackend(unreachableExternalServer(), Duration.ZERO);
        CountDownLatch startHolds = new CountDownLatch(1);
        CountDownLatch startMayFinish = new CountDownLatch(1);
        AtomicBoolean deletionRan = new AtomicBoolean();
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> start = threads.submit(() -> backend.underLock("c1", () -> {
                startHolds.countDown();
                await(startMayFinish);
                return null;
            }));
            await(startHolds);
            Future<Object> deletion = threads.submit(() -> backend.underLock("c1", () -> {
                deletionRan.set(true);
                return null;
            }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!backend.awaitedLock("c1") && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }

            assertTrue(backend.awaitedLock("c1"), "the deletion waits for the start's lock");
            assertFalse(deletionRan.get());
            assertEquals("other", backend.underLock("c2", () -> "other"), "another cluster is not held up");

            startMayFinish.countDown();
            start.get(10, TimeUnit.SECONDS);
            deletion.get(10, TimeUnit.SECONDS);
        }

        assertTrue(deletionRan.get());
        assertEquals(0, backend.lockedClusters(), "no lock is kept once nobody holds or awaits it");
    }

    @Test
    void aFailureReleasesTheClustersLock() {
        ExternalDsqlBackend backend = new ExternalDsqlBackend(unreachableExternalServer(), Duration.ZERO);

        assertThrows(SQLException.class, () -> backend.underLock("c1", () -> {
            throw new SQLException("refused");
        }));
        assertThrows(SQLException.class, () -> backend.start(cluster()));
        assertThrows(IllegalStateException.class, () -> backend.stop(cluster(), true));

        assertEquals(0, backend.lockedClusters());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Connection serverOfVersion(int major, String version) throws SQLException {
        Connection server = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(server.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseMajorVersion()).thenReturn(major);
        when(metaData.getDatabaseProductVersion()).thenReturn(version);
        return server;
    }

    private static EmulatorConfig unreachableExternalServer() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().dsql().backend()).thenReturn(EmulatorConfig.DsqlServiceConfig.Backend.EXTERNAL);
        when(config.services().dsql().external().host()).thenReturn(Optional.of("127.0.0.1"));
        // Nothing listens on port 1, so every connection is refused at once.
        when(config.services().dsql().external().port()).thenReturn(1);
        when(config.services().dsql().external().database()).thenReturn("postgres");
        when(config.services().dsql().external().username()).thenReturn("postgres");
        when(config.services().dsql().external().password()).thenReturn(Optional.empty());
        return config;
    }

    private static DsqlCluster cluster() {
        DsqlCluster cluster = new DsqlCluster();
        cluster.setIdentifier("abcdefghijklmnopqrstuvwxyz");
        cluster.setArn("arn:aws:dsql:eu-west-1:111122223333:cluster/abcdefghijklmnopqrstuvwxyz");
        return cluster;
    }
}
