package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.services.rds.container.RdsBackendGate;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.testutil.FreePorts;
import io.github.hectorvent.floci.testutil.LogCapture;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RdsProxyManagerTest {

    @Test
    void interruptedRetirementRetainsOwnershipAndCanBeRetried() throws Exception {
        RdsProxyManager manager = manager();
        RdsAuthProxy proxy = authProxy("proxy");
        CountDownLatch release = new CountDownLatch(1);
        Thread accepting = blockedRetirement(release);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        ConcurrentHashMap<String, RdsAuthProxy> owned = registry(manager, "proxies");
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
        RdsProxyManager manager = manager();
        RdsAuthProxy proxy = authProxy("proxy");
        proxy.acceptRetirementTimeout = Duration.ofMillis(10);
        CountDownLatch release = new CountDownLatch(1);
        Thread accepting = blockedRetirement(release);
        ConcurrentHashMap<String, RdsAuthProxy> owned = registry(manager, "proxies");
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
        RdsProxyManager manager = manager();
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
        RdsProxyManager manager = manager();
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
        RdsProxyManager manager = manager();
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
        RdsProxyManager manager = manager();
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
        RdsProxyManager manager = manager();
        int proxyPort = FreePorts.anyInterfacePort();
        try {
            start(manager, "proxy", proxyPort);

            manager.updateMasterPassword("proxy", "rotated");

            assertEquals("rotated", masterPassword(registry(manager, "proxies").get("proxy")));
            assertPortUnavailable(proxyPort);
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void updateMasterPasswordForUnknownInstanceIsANoOp() {
        RdsProxyManager manager = manager();

        assertDoesNotThrow(() -> manager.updateMasterPassword("missing", "rotated"));
    }

    @Test
    void stopAllReleasesEveryListenerAndIsIdempotent() throws IOException {
        RdsProxyManager manager = manager();
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
        RdsProxyManager manager = manager();
        RdsAuthProxy proxy = authProxy("proxy");
        ServerSocket serverSocket = mock(ServerSocket.class);
        IOException closeFailure = new IOException("simulated close failure");
        doThrow(closeFailure).when(serverSocket).close();
        setServerSocket(proxy, serverSocket);
        ConcurrentHashMap<String, RdsAuthProxy> registry = registry(manager, "proxies");
        registry.put("proxy", proxy);

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                manager.stopProxy("proxy"));

        assertSame(closeFailure, thrown.getCause());
        assertSame(proxy, registry.get("proxy"));
        registry.clear();
    }

    @Test
    void failedPreviousStopRestoresMappingAndReleasesCandidate() throws Exception {
        RdsProxyManager manager = manager();
        RdsAuthProxy previous = mock(RdsAuthProxy.class);
        doThrow(new IllegalStateException("simulated previous stop failure"))
                .when(previous).stop();
        ConcurrentHashMap<String, RdsAuthProxy> registry = registry(manager, "proxies");
        registry.put("proxy", previous);
        int candidatePort = FreePorts.anyInterfacePort();

        assertThrows(RuntimeException.class, () -> start(manager, "proxy", candidatePort));

        assertSame(previous, registry.get("proxy"));
        assertPortAvailable(candidatePort);
        registry.clear();
    }

    @Test
    void failedStartCleanupDoesNotReplaceExistingListenerAndStartRetriesIt() throws Exception {
        RdsProxyManager manager = manager();
        int originalPort = FreePorts.anyInterfacePort();
        try {
            start(manager, "proxy", originalPort);
            RdsAuthProxy original = registry(manager, "proxies").get("proxy");
            RdsAuthProxy failedCandidate = mock(RdsAuthProxy.class);
            IllegalStateException stopFailure = new IllegalStateException("candidate stop failed");
            doThrow(stopFailure).doNothing().when(failedCandidate).stop();
            RuntimeException startupFailure = new RuntimeException("candidate start failed");
            recordFailedStart(manager, "proxy", failedCandidate, startupFailure);

            assertSame(stopFailure, startupFailure.getSuppressed()[0]);
            assertSame(original, registry(manager, "proxies").get("proxy"));
            int replacementPort = FreePorts.anyInterfacePort();
            start(manager, "proxy", replacementPort);
            verify(failedCandidate, times(2)).stop();
            assertFalse(registry(manager, "pendingFailedStarts").containsKey("proxy"));
            assertPortAvailable(originalPort);
            assertPortUnavailable(replacementPort);
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void stopProxyRetainsOnlyFailedPendingCandidateForRetry() throws Exception {
        RdsProxyManager manager = manager();
        int originalPort = FreePorts.anyInterfacePort();
        try {
            start(manager, "proxy", originalPort);
            RdsAuthProxy failedCandidate = mock(RdsAuthProxy.class);
            doThrow(new IllegalStateException("candidate stop failed"))
                    .doThrow(new IllegalStateException("candidate retry failed"))
                    .doNothing().when(failedCandidate).stop();
            recordFailedStart(manager, "proxy", failedCandidate, new RuntimeException("start failed"));

            assertThrows(IllegalStateException.class, () -> manager.stopProxy("proxy"));
            assertFalse(registry(manager, "proxies").containsKey("proxy"));
            assertSame(failedCandidate, registry(manager, "pendingFailedStarts").get("proxy"));
            assertPortAvailable(originalPort);
            manager.stopProxy("proxy");
            assertFalse(registry(manager, "pendingFailedStarts").containsKey("proxy"));
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void stopAllRetainsFailedStartCandidateUntilNextAttempt() throws Exception {
        RdsProxyManager manager = manager();
        int originalPort = FreePorts.anyInterfacePort();
        start(manager, "proxy", originalPort);
        RdsAuthProxy failedCandidate = mock(RdsAuthProxy.class);
        doThrow(new IllegalStateException("candidate stop failed"))
                .doThrow(new IllegalStateException("candidate shutdown failed"))
                .doNothing().when(failedCandidate).stop();
        recordFailedStart(manager, "proxy", failedCandidate, new RuntimeException("start failed"));

        manager.stopAll();
        assertFalse(registry(manager, "proxies").containsKey("proxy"));
        assertSame(failedCandidate, registry(manager, "pendingFailedStarts").get("proxy"));
        assertPortAvailable(originalPort);
        manager.stopAll();
        assertFalse(registry(manager, "pendingFailedStarts").containsKey("proxy"));
    }

    @Test
    void stopAllContinuesAndRetainsOnlyFailedListenerOwnership() throws Exception {
        RdsProxyManager manager = manager();
        int successfulPort = FreePorts.anyInterfacePort();
        start(manager, "successful", successfulPort);
        RdsAuthProxy failed = mock(RdsAuthProxy.class);
        doThrow(new IllegalStateException("simulated shutdown stop failure"))
                .when(failed).stop();
        ConcurrentHashMap<String, RdsAuthProxy> registry = registry(manager, "proxies");
        registry.put("failed", failed);

        assertDoesNotThrow(manager::stopAll);

        assertTrue(registry.containsKey("failed"));
        assertFalse(registry.containsKey("successful"));
        assertPortAvailable(successfulPort);
        registry.clear();
    }

    @Test
    void endpointResolutionWarningIsLoggedOnceUntilResolutionRecovers() throws Exception {
        RdsContainerManager containerManager = mock(RdsContainerManager.class);
        String runtimeId = "arn:aws:rds:us-east-1:123456789012:db:db-a";
        IllegalStateException resolutionFailure = new IllegalStateException("Docker unavailable");
        when(containerManager.resolveBackendEndpoint(runtimeId, DatabaseEngine.SQLSERVER))
                .thenThrow(resolutionFailure)
                .thenThrow(resolutionFailure)
                .thenReturn(new EndpointInfo("127.0.0.1", 1433))
                .thenThrow(resolutionFailure);
        RdsProxyManager manager = new RdsProxyManager(
                mock(RdsSigV4Validator.class), mock(RdsProxyTlsCertificates.class),
                testConfig(), RdsBackendGate.OPEN, containerManager);
        try {
            manager.startProxy("proxy", runtimeId, DatabaseEngine.SQLSERVER, false,
                    FreePorts.anyInterfacePort(), "stale-address", 1, "localhost",
                    "sa", "secret", null, (user, password) -> true, null);
            RdsAuthProxy.BackendEndpointResolver resolver = endpointResolver(
                    registry(manager, "proxies").get("proxy"));

            List<LogRecord> records = LogCapture.capture(RdsProxyManager.class, () -> {
                assertThrows(IOException.class, resolver::resolve);
                assertThrows(IOException.class, resolver::resolve);
                assertDoesNotThrow(resolver::resolve);
                assertThrows(IOException.class, resolver::resolve);
            });

            List<LogRecord> warnings = records.stream()
                    .filter(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
                    .toList();
            assertEquals(2, warnings.size());
            assertTrue(warnings.stream().allMatch(record ->
                    record.getMessage().contains("Could not resolve backend endpoint")));
            assertTrue(warnings.stream().allMatch(record ->
                    runtimeId.equals(record.getParameters()[0])));
            assertSame(resolutionFailure, warnings.getFirst().getThrown());
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void dynamicProxyResolvesTheBackendForEveryConnection() throws Exception {
        RdsContainerManager containerManager = mock(RdsContainerManager.class);
        RdsBackendGate backendGate = mock(RdsBackendGate.class);
        String runtimeId = "arn:aws:rds:us-east-1:123456789012:db:db-a";
        when(backendGate.enterRuntime(runtimeId)).thenReturn(RdsBackendGate.Lease.NONE);
        try (ServerSocket backend = reusableSocket(0)) {
            when(containerManager.resolveBackendEndpoint(runtimeId, DatabaseEngine.SQLSERVER))
                    .thenReturn(new EndpointInfo("127.0.0.1", backend.getLocalPort()));
            RdsProxyManager manager = new RdsProxyManager(
                    mock(RdsSigV4Validator.class), mock(RdsProxyTlsCertificates.class),
                    testConfig(), backendGate, containerManager);
            int proxyPort = FreePorts.anyInterfacePort();
            Thread serving = Thread.ofVirtual().start(() -> {
                try {
                    for (int connection = 0; connection < 2; connection++) {
                        try (Socket accepted = backend.accept()) {
                            accepted.getOutputStream().write('x');
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            try {
                manager.startProxy("proxy", runtimeId, DatabaseEngine.SQLSERVER, false,
                        proxyPort, "stale-address", 1, "localhost", "sa", "secret", null,
                        (user, password) -> true, null);

                for (int connection = 0; connection < 2; connection++) {
                    try (Socket client = new Socket("127.0.0.1", proxyPort)) {
                        client.setSoTimeout(5000);
                        assertEquals('x', client.getInputStream().read());
                    }
                }

                assertTrue(serving.join(Duration.ofSeconds(5)));
                verify(containerManager, times(2))
                        .resolveBackendEndpoint(runtimeId, DatabaseEngine.SQLSERVER);
                verify(backendGate, times(2)).enterRuntime(runtimeId);
            } finally {
                manager.stopAll();
            }
        }
    }

    private static RdsProxyManager manager() {
        RdsContainerManager containerManager = mock(RdsContainerManager.class);
        when(containerManager.resolveBackendEndpoint(
                anyString(), any(DatabaseEngine.class)))
                .thenReturn(new EndpointInfo("localhost", 1));
        return new RdsProxyManager(
                mock(RdsSigV4Validator.class), mock(RdsProxyTlsCertificates.class),
                testConfig(), RdsBackendGate.OPEN, containerManager);
    }

    private static void start(RdsProxyManager manager, String key, int proxyPort) {
        manager.startProxy(key, "runtime:" + key, DatabaseEngine.POSTGRES, false, proxyPort,
                "localhost", 1, "localhost", "admin", "secret", "app", (user, password) -> true,
                new RdsProxyBinding("localhost", proxyPort, "us-east-1", "123456789012", "db-ABCDEFGHIJKL01234", true));
    }

    private static RdsAuthProxy authProxy(String key) {
        return new RdsAuthProxy(key, "localhost", 1, "runtime:" + key,
                DatabaseEngine.POSTGRES, false,
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

    private static RdsAuthProxy.BackendEndpointResolver endpointResolver(RdsAuthProxy proxy)
            throws Exception {
        Field field = RdsAuthProxy.class.getDeclaredField("backendEndpointResolver");
        field.setAccessible(true);
        return (RdsAuthProxy.BackendEndpointResolver) field.get(proxy);
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
            RdsProxyManager manager, String name) throws Exception {
        Field field = RdsProxyManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return (ConcurrentHashMap<String, RdsAuthProxy>) field.get(manager);
    }

    private static void recordFailedStart(
            RdsProxyManager manager, String key, RdsAuthProxy candidate, Throwable failure) throws Exception {
        Method method = RdsProxyManager.class.getDeclaredMethod(
                "cleanupFailedStart", String.class, RdsAuthProxy.class, Throwable.class);
        method.setAccessible(true);
        method.invoke(manager, key, candidate, failure);
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
