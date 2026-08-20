package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class RdsProxyManagerTest {

    @Test
    void replacingProxyStopsPreviousListenerAndKeepsReplacementOwned() throws IOException {
        RdsProxyManager manager = new RdsProxyManager(mock(RdsSigV4Validator.class));
        int firstPort = availablePort();
        try {
            start(manager, "proxy", firstPort);
            int replacementPort = availablePort();

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
        RdsProxyManager manager = new RdsProxyManager(mock(RdsSigV4Validator.class));
        int proxyPort = availablePort();
        try {
            assertThrows(RuntimeException.class, () -> start(manager, null, proxyPort));

            assertPortAvailable(proxyPort);
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void failedReplacementPreservesOriginalRegistryEntry() throws IOException {
        RdsProxyManager manager = new RdsProxyManager(mock(RdsSigV4Validator.class));
        int originalPort = availablePort();
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
    void stopAllReleasesEveryListenerAndIsIdempotent() throws IOException {
        RdsProxyManager manager = new RdsProxyManager(mock(RdsSigV4Validator.class));
        int firstPort = availablePort();
        start(manager, "first", firstPort);
        int secondPort = availablePort();
        start(manager, "second", secondPort);

        manager.stopAll();

        assertPortAvailable(firstPort);
        assertPortAvailable(secondPort);
        assertDoesNotThrow(manager::stopAll);
    }

    @Test
    void closeFailurePropagatesAndManagerRetainsOwnership() throws Exception {
        RdsProxyManager manager = new RdsProxyManager(mock(RdsSigV4Validator.class));
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
        RdsProxyManager manager = new RdsProxyManager(mock(RdsSigV4Validator.class));
        RdsAuthProxy previous = mock(RdsAuthProxy.class);
        doThrow(new IllegalStateException("simulated previous stop failure"))
                .when(previous).stop();
        ConcurrentHashMap<String, RdsAuthProxy> registry = registry(manager);
        registry.put("proxy", previous);
        int candidatePort = availablePort();

        assertThrows(RuntimeException.class, () -> start(manager, "proxy", candidatePort));

        assertSame(previous, registry.get("proxy"));
        assertPortAvailable(candidatePort);
        registry.clear();
    }

    @Test
    void stopAllContinuesAndRetainsOnlyFailedListenerOwnership() throws Exception {
        RdsProxyManager manager = new RdsProxyManager(mock(RdsSigV4Validator.class));
        int successfulPort = availablePort();
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
                "localhost", 1, "admin", "secret", "app", (user, password) -> true);
    }

    private static RdsAuthProxy authProxy(String key) {
        return new RdsAuthProxy(key, "localhost", 1, DatabaseEngine.POSTGRES, false,
                "admin", "secret", "app", mock(RdsSigV4Validator.class),
                (user, password) -> true);
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

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
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
