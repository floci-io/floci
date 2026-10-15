package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.testutil.FreePorts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RdsProxyManagerTest {

    @Test
    void interruptedRetirementRetainsOwnershipAndCanBeRetried() throws Exception {
        RdsProxyManager manager = new RdsProxyManager(
                mock(RdsSigV4Validator.class), mock(RdsProxyTlsCertificates.class), testConfig());
        RdsAuthProxy proxy = authProxy("proxy");
        CountDownLatch release = new CountDownLatch(1);
        Thread accepting = blockedRetirement(release);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        ConcurrentHashMap<String, RdsAuthProxy> owned = registry(manager);
        try (ServerSocket listener = reusableSocket(0)) {
            int port = listener.getLocalPort();
            setServerSocket(proxy, listener);
            setAcceptThread(proxy, accepting);
            owned.put("proxy", proxy);
            Thread stopping = Thread.ofVirtual().start(() -> {
                Thread.currentThread().interrupt();
                try {
                    manager.stopProxy("proxy");
                } catch (RuntimeException e) {
                    failure.set(e);
                } finally {
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            assertTrue(stopping.join(Duration.ofSeconds(2)));
            assertInstanceOf(InterruptedException.class, failure.get().getCause());
            assertTrue(interrupted.get());
            assertSame(proxy, owned.get("proxy"));
            assertPortAvailable(port);
            release.countDown();
            assertTrue(accepting.join(Duration.ofSeconds(2)));
            manager.stopProxy("proxy");
            assertFalse(owned.containsKey("proxy"));
            assertPortAvailable(port);
        } finally {
            release.countDown();
            assertTrue(accepting.join(Duration.ofSeconds(2)));
            manager.stopAll();
        }
    }

    @Test
    void retirementTimeoutRetainsOwnershipAndCanBeRetried() throws Exception {
        RdsProxyManager manager = new RdsProxyManager(
                mock(RdsSigV4Validator.class), mock(RdsProxyTlsCertificates.class), testConfig());
        RdsAuthProxy proxy = authProxy("proxy");
        proxy.acceptRetirementTimeout = Duration.ofMillis(10);
        CountDownLatch release = new CountDownLatch(1);
        Thread accepting = blockedRetirement(release);
        ConcurrentHashMap<String, RdsAuthProxy> owned = registry(manager);
        try (ServerSocket listener = reusableSocket(0)) {
            int port = listener.getLocalPort();
            setServerSocket(proxy, listener);
            setAcceptThread(proxy, accepting);
            owned.put("proxy", proxy);
            assertThrows(IllegalStateException.class, () -> manager.stopProxy("proxy"));
            assertSame(proxy, owned.get("proxy"));
            assertPortAvailable(port);
            release.countDown();
            assertTrue(accepting.join(Duration.ofSeconds(2)));
            manager.stopProxy("proxy");
            assertFalse(owned.containsKey("proxy"));
            assertPortAvailable(port);
        } finally {
            release.countDown();
            assertTrue(accepting.join(Duration.ofSeconds(2)));
            manager.stopAll();
        }
    }

    @Test
    void samePortRebootDoesNotWaitForASeparateReleasePoll() throws IOException {
        RdsProxyManager manager = new RdsProxyManager(
                mock(RdsSigV4Validator.class), mock(RdsProxyTlsCertificates.class), testConfig());
        int port = FreePorts.anyInterfacePort();
        try {
            for (int attempt = 0; attempt < 100; attempt++) {
                start(manager, "reboot", port);
                try (Socket admitted = new Socket("127.0.0.1", port)) {
                    admitted.setSoTimeout(1000);
                    ByteArrayOutputStream startup = new ByteArrayOutputStream();
                    DataOutputStream fields = new DataOutputStream(startup);
                    fields.writeInt(196608);
                    fields.writeBytes("user");
                    fields.writeByte(0);
                    fields.writeBytes("admin");
                    fields.writeByte(0);
                    fields.writeBytes("database");
                    fields.writeByte(0);
                    fields.writeBytes("app");
                    fields.writeByte(0);
                    fields.writeByte(0);
                    DataOutputStream request = new DataOutputStream(admitted.getOutputStream());
                    request.writeInt(startup.size() + 4);
                    startup.writeTo(request);
                    request.flush();
                    DataInputStream response = new DataInputStream(admitted.getInputStream());
                    assertEquals('R', response.readUnsignedByte());
                    assertEquals(8, response.readInt());
                    assertEquals(3, response.readInt());
                    manager.stopProxy("reboot");
                    start(manager, "reboot", port);
                    assertPortUnavailable(port);
                    manager.stopProxy("reboot");
                }
            }
        } finally {
            manager.stopAll();
        }
        assertPortAvailable(port);
    }

    @Test
    void replacingProxyStopsPreviousListenerAndKeepsReplacementOwned() throws IOException {
        RdsProxyManager manager = new RdsProxyManager(
                mock(RdsSigV4Validator.class), mock(RdsProxyTlsCertificates.class), testConfig());
        int firstPort = FreePorts.anyInterfacePort();
        try {
            start(manager, "proxy", firstPort);
            int replacementPort = FreePorts.anyInterfacePort();

            start(manager, "proxy", replacementPort);

            assertPortAvailable(firstPort);
            assertPortUnavailable(replacementPort);
            manager.stopProxy("proxy");
            assertPortAvailable(replacementPort);
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void registrationFailureAfterBindReleasesCandidateListener() throws IOException {
        RdsProxyManager manager = new RdsProxyManager(
                mock(RdsSigV4Validator.class), mock(RdsProxyTlsCertificates.class), testConfig());
        int proxyPort = FreePorts.anyInterfacePort();
        try {
            assertThrows(RuntimeException.class, () -> start(manager, null, proxyPort));

            assertPortAvailable(proxyPort);
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void failedReplacementPreservesOriginalRegistryEntry() throws IOException {
        RdsProxyManager manager = new RdsProxyManager(
                mock(RdsSigV4Validator.class), mock(RdsProxyTlsCertificates.class), testConfig());
        int originalPort = FreePorts.anyInterfacePort();
        try {
            start(manager, "proxy", originalPort);
            try (ServerSocket occupied = new ServerSocket(0)) {
                assertThrows(RuntimeException.class, () ->
                        start(manager, "proxy", occupied.getLocalPort()));
                assertPortUnavailable(originalPort);
            }

            manager.stopProxy("proxy");
            assertPortAvailable(originalPort);
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void updateMasterPasswordSwapsTheRunningProxySnapshotWithoutARestart() throws Exception {
        RdsProxyManager manager = new RdsProxyManager(
                mock(RdsSigV4Validator.class), mock(RdsProxyTlsCertificates.class), testConfig());
        int proxyPort = FreePorts.anyInterfacePort();
        try {
            start(manager, "proxy", proxyPort);

            manager.updateMasterPassword("proxy", "rotated");

            assertEquals("rotated", masterPassword(registry(manager).get("proxy")));
            assertPortUnavailable(proxyPort);
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void updateMasterPasswordForUnknownInstanceIsANoOp() {
        RdsProxyManager manager = new RdsProxyManager(
                mock(RdsSigV4Validator.class), mock(RdsProxyTlsCertificates.class), testConfig());

        assertDoesNotThrow(() -> manager.updateMasterPassword("missing", "rotated"));
    }

    @Test
    void stopAllReleasesEveryListenerAndIsIdempotent() throws IOException {
        RdsProxyManager manager = new RdsProxyManager(
                mock(RdsSigV4Validator.class), mock(RdsProxyTlsCertificates.class), testConfig());
        int firstPort = FreePorts.anyInterfacePort();
        start(manager, "first", firstPort);
        int secondPort = FreePorts.anyInterfacePort();
        start(manager, "second", secondPort);

        manager.stopAll();

        assertPortAvailable(firstPort);
        assertPortAvailable(secondPort);
        assertDoesNotThrow(manager::stopAll);
    }

    @Test
    void closeFailurePropagatesAndManagerRetainsOwnership() throws Exception {
        RdsProxyManager manager = new RdsProxyManager(
                mock(RdsSigV4Validator.class), mock(RdsProxyTlsCertificates.class), testConfig());
        RdsAuthProxy proxy = authProxy("proxy");
        ServerSocket serverSocket = mock(ServerSocket.class);
        IOException closeFailure = new IOException("simulated close failure");
        doThrow(closeFailure).when(serverSocket).close();
        setServerSocket(proxy, serverSocket);
        ConcurrentHashMap<String, RdsAuthProxy> registry = registry(manager);
        registry.put("proxy", proxy);

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                manager.stopProxy("proxy"));

        assertSame(closeFailure, thrown.getCause());
        assertSame(proxy, registry.get("proxy"));
        registry.clear();
    }

    @Test
    void failedPreviousStopRestoresMappingAndReleasesCandidate() throws Exception {
        RdsProxyManager manager = new RdsProxyManager(
                mock(RdsSigV4Validator.class), mock(RdsProxyTlsCertificates.class), testConfig());
        RdsAuthProxy previous = mock(RdsAuthProxy.class);
        doThrow(new IllegalStateException("simulated previous stop failure"))
                .when(previous).stop();
        ConcurrentHashMap<String, RdsAuthProxy> registry = registry(manager);
        registry.put("proxy", previous);
        int candidatePort = FreePorts.anyInterfacePort();

        assertThrows(RuntimeException.class, () -> start(manager, "proxy", candidatePort));

        assertSame(previous, registry.get("proxy"));
        assertPortAvailable(candidatePort);
        registry.clear();
    }

    @Test
    void stopAllContinuesAndRetainsOnlyFailedListenerOwnership() throws Exception {
        RdsProxyManager manager = new RdsProxyManager(
                mock(RdsSigV4Validator.class), mock(RdsProxyTlsCertificates.class), testConfig());
        int successfulPort = FreePorts.anyInterfacePort();
        start(manager, "successful", successfulPort);
        RdsAuthProxy failed = mock(RdsAuthProxy.class);
        doThrow(new IllegalStateException("simulated shutdown stop failure"))
                .when(failed).stop();
        ConcurrentHashMap<String, RdsAuthProxy> registry = registry(manager);
        registry.put("failed", failed);

        assertDoesNotThrow(manager::stopAll);

        assertTrue(registry.containsKey("failed"));
        assertFalse(registry.containsKey("successful"));
        assertPortAvailable(successfulPort);
        registry.clear();
    }

    private static void start(RdsProxyManager manager, String key, int proxyPort) {
        manager.startProxy(key, DatabaseEngine.POSTGRES, false, proxyPort,
                "localhost", 1, "localhost", "admin", "secret", "app", (user, password) -> true,
                new RdsProxyBinding("localhost", proxyPort, "us-east-1", "123456789012", "db-ABCDEFGHIJKL01234", true));
    }

    private static RdsAuthProxy authProxy(String key) {
        return new RdsAuthProxy(key, "localhost", 1, DatabaseEngine.POSTGRES, false,
                "admin", "secret", "app", mock(RdsSigV4Validator.class),
                mock(RdsProxyTlsCertificates.class), (user, password) -> true,
                5000, 5000, 100);
    }

    private static EmulatorConfig testConfig() {
        EmulatorConfig.RdsServiceConfig rdsConfig = mock(EmulatorConfig.RdsServiceConfig.class);
        when(rdsConfig.proxyHandshakeTimeoutMillis()).thenReturn(5000);
        when(rdsConfig.proxyBackendConnectTimeoutMillis()).thenReturn(5000);
        when(rdsConfig.proxyMaxConnections()).thenReturn(100);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        when(servicesConfig.rds()).thenReturn(rdsConfig);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        return config;
    }

    private static String masterPassword(RdsAuthProxy proxy) throws Exception {
        Field field = RdsAuthProxy.class.getDeclaredField("masterPassword");
        field.setAccessible(true);
        return (String) field.get(proxy);
    }

    /** Holds retirement after socket close without depending on native scheduler timing. */
    private static Thread blockedRetirement(CountDownLatch release) {
        return Thread.ofVirtual().start(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static void setAcceptThread(RdsAuthProxy proxy, Thread thread) throws Exception {
        Field field = RdsAuthProxy.class.getDeclaredField("acceptThread");
        field.setAccessible(true);
        field.set(proxy, thread);
    }

    private static void setServerSocket(RdsAuthProxy proxy, ServerSocket socket) throws Exception {
        Field field = RdsAuthProxy.class.getDeclaredField("serverSocket");
        field.setAccessible(true);
        field.set(proxy, socket);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, RdsAuthProxy> registry(
            RdsProxyManager manager) throws Exception {
        Field field = RdsProxyManager.class.getDeclaredField("proxies");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, RdsAuthProxy>) field.get(manager);
    }

    private static void assertPortAvailable(int port) {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try (ServerSocket ignored = reusableSocket(port)) {
                return;
            } catch (IOException e) {
                lastFailure = e;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for proxy port " + port + " to be released", e);
            }
        }
        fail("Proxy port " + port + " was not released", lastFailure);
    }

    private static void assertPortUnavailable(int port) {
        assertThrows(IOException.class, () -> {
            try (ServerSocket ignored = reusableSocket(port)) {
                // The active proxy owns this listener.
            }
        });
    }

    private static ServerSocket reusableSocket(int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));
        return socket;
    }
}
