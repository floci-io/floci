package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.rds.proxy.RdsProxyTlsCertificates;
import io.github.hectorvent.floci.services.rds.proxy.RdsSigV4Validator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RedshiftProxyManagerTest {

    private RedshiftProxyManager newManager() {
        return new RedshiftProxyManager(
                mock(RdsSigV4Validator.class), mock(RdsProxyTlsCertificates.class));
    }

    private static void start(RedshiftProxyManager manager, String key, int proxyPort) {
        manager.startProxy(key, proxyPort, "localhost", 1, "localhost",
                "admin", "secret", "dev", (user, password) -> true);
    }

    @Test
    void startRegistersTheKeyAndBindsThePort() throws Exception {
        RedshiftProxyManager manager = newManager();
        int port = availablePort();
        try {
            start(manager, "111111111111:c1", port);
            assertTrue(registry(manager).containsKey("111111111111:c1"));
            assertPortUnavailable(port);
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void stopProxyRemovesTheKeyAndReleasesThePort() throws Exception {
        RedshiftProxyManager manager = newManager();
        int port = availablePort();
        start(manager, "k", port);

        manager.stopProxy("k");

        assertFalse(registry(manager).containsKey("k"));
        assertPortAvailable(port);
    }

    @Test
    void startingAnExistingKeyStopsTheOldProxyAndInstallsTheNewOne() throws IOException {
        RedshiftProxyManager manager = newManager();
        int firstPort = availablePort();
        try {
            start(manager, "k", firstPort);
            int replacementPort = availablePort();

            start(manager, "k", replacementPort);

            assertPortAvailable(firstPort);
            assertPortUnavailable(replacementPort);
        } finally {
            manager.stopAll();
        }
    }


    @Test
    void updateMasterPasswordForUnknownKeyIsANoOp() {
        RedshiftProxyManager manager = newManager();
        assertDoesNotThrow(() -> manager.updateMasterPassword("missing", "rotated"));
    }

    @Test
    void updateMasterPasswordSwapsTheRunningSnapshot() throws Exception {
        RedshiftProxyManager manager = newManager();
        int port = availablePort();
        try {
            start(manager, "k", port);
            manager.updateMasterPassword("k", "rotated");
            assertEquals("rotated", masterPassword(registry(manager).get("k")));
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void stopAllReleasesEveryListenerAndIsIdempotent() throws IOException {
        RedshiftProxyManager manager = newManager();
        int a = availablePort();
        start(manager, "a", a);
        int b = availablePort();
        start(manager, "b", b);

        manager.stopAll();

        assertPortAvailable(a);
        assertPortAvailable(b);
        assertDoesNotThrow(manager::stopAll);
    }

    @Test
    void failedStartOnABusyPortLeavesNoRegistryEntry() throws Exception {
        RedshiftProxyManager manager = newManager();
        try (ServerSocket occupied = new ServerSocket(0)) {
            assertThrows(RuntimeException.class,
                    () -> start(manager, "k", occupied.getLocalPort()));
            assertFalse(registry(manager).containsKey("k"));
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void stopProxyRetainsTheProxyWhenItsListenerCloseFails() throws Exception {
        RedshiftProxyManager manager = newManager();
        RedshiftAuthProxy badProxy = mock(RedshiftAuthProxy.class);
        doThrow(new RuntimeException("close failed")).when(badProxy).stop();
        registry(manager).put("k", badProxy);

        assertThrows(RuntimeException.class, () -> manager.stopProxy("k"));

        // Proxy stays registered so a later cleanup attempt can still reach it and retry.
        assertSame(badProxy, registry(manager).get("k"));
    }

    @Test
    void stopProxyRetryClosesTheSameProxyAndThenDeregistersIt() throws Exception {
        RedshiftProxyManager manager = newManager();
        RedshiftAuthProxy proxy = mock(RedshiftAuthProxy.class);
        doThrow(new RuntimeException("close failed")).doNothing().when(proxy).stop();
        registry(manager).put("k", proxy);

        assertThrows(RuntimeException.class, () -> manager.stopProxy("k"));
        assertDoesNotThrow(() -> manager.stopProxy("k"));

        assertFalse(registry(manager).containsKey("k"));
        verify(proxy, times(2)).stop();
    }

    @Test
    void stopProxyRefusesWhileAFailedStartupListenerStillCannotBeClosed() throws Exception {
        RedshiftProxyManager manager = newManager();
        RedshiftAuthProxy stuck = mock(RedshiftAuthProxy.class);
        doThrow(new RuntimeException("close failed")).when(stuck).stop();
        unclosable(manager).put("k", stuck);

        assertThrows(RuntimeException.class, () -> manager.stopProxy("k"));

        // Reference retained so the next attempt can retry the close.
        assertSame(stuck, unclosable(manager).get("k"));
    }

    @Test
    void stopProxyKeepsRetryingTheSameUnclosableListenerOnEveryCall() throws Exception {
        RedshiftProxyManager manager = newManager();
        RedshiftAuthProxy stuck = mock(RedshiftAuthProxy.class);
        doThrow(new RuntimeException("close failed")).when(stuck).stop();
        unclosable(manager).put("k", stuck);

        assertThrows(RuntimeException.class, () -> manager.stopProxy("k"));
        assertThrows(RuntimeException.class, () -> manager.stopProxy("k"));

        verify(stuck, times(2)).stop();
        assertTrue(unclosable(manager).containsKey("k"));
    }

    @Test
    void stopProxyRecoversTheUnclosableEntryOnceTheListenerFinallyCloses() throws Exception {
        RedshiftProxyManager manager = newManager();
        RedshiftAuthProxy recovering = mock(RedshiftAuthProxy.class);
        doThrow(new RuntimeException("close failed")).doNothing().when(recovering).stop();
        unclosable(manager).put("k", recovering);

        assertThrows(RuntimeException.class, () -> manager.stopProxy("k"));
        assertDoesNotThrow(() -> manager.stopProxy("k"));

        assertFalse(unclosable(manager).containsKey("k"));
    }

    @Test
    void startProxyLeavesAnUnclosableEntryInPlace() throws Exception {
        RedshiftProxyManager manager = newManager();
        RedshiftAuthProxy stuck = mock(RedshiftAuthProxy.class);
        doThrow(new RuntimeException("close failed")).when(stuck).stop();
        unclosable(manager).put("k", stuck);
        int port = availablePort();
        try {
            // A fresh start for the same key must not silently drop the leaked listener.
            start(manager, "k", port);
            assertSame(stuck, unclosable(manager).get("k"));
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void stopAllAlsoDrainsUnclosableProxies() throws Exception {
        RedshiftProxyManager manager = newManager();
        RedshiftAuthProxy recovered = mock(RedshiftAuthProxy.class);
        RedshiftAuthProxy stillStuck = mock(RedshiftAuthProxy.class);
        doThrow(new RuntimeException("close failed")).when(stillStuck).stop();
        unclosable(manager).put("a", recovered);
        unclosable(manager).put("b", stillStuck);

        manager.stopAll();

        assertFalse(unclosable(manager).containsKey("a"));
        assertTrue(unclosable(manager).containsKey("b"));
    }

    // --- reflection + port helpers copied from RdsProxyManagerTest ---

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, RedshiftAuthProxy> registry(RedshiftProxyManager manager)
            throws Exception {
        Field field = RedshiftProxyManager.class.getDeclaredField("proxies");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, RedshiftAuthProxy>) field.get(manager);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, RedshiftAuthProxy> unclosable(RedshiftProxyManager manager)
            throws Exception {
        Field field = RedshiftProxyManager.class.getDeclaredField("unclosableProxies");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, RedshiftAuthProxy>) field.get(manager);
    }

    private static String masterPassword(RedshiftAuthProxy proxy) throws Exception {
        Field field = RedshiftAuthProxy.class.getDeclaredField("masterPassword");
        field.setAccessible(true);
        return (String) field.get(proxy);
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void assertPortAvailable(int port) {
        IOException last = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try (ServerSocket ignored = reusableSocket(port)) {
                return;
            } catch (IOException e) {
                last = e;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted waiting for port " + port, e);
            }
        }
        fail("Proxy port " + port + " was not released", last);
    }

    private static void assertPortUnavailable(int port) {
        assertThrows(IOException.class, () -> {
            try (ServerSocket ignored = reusableSocket(port)) {
                // active proxy owns this listener
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
