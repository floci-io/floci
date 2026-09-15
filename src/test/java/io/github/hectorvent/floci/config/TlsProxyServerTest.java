package io.github.hectorvent.floci.config;

import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TlsProxyServer}'s multi-port binding and first-byte protocol detection.
 *
 * <p>Uses ephemeral ports for the public port, the AWS-HTTPS port, and the stub HTTP/HTTPS
 * backends, so nothing privileged (443) is bound and the test never collides with a running
 * Floci. The stub backends simply emit a marker on connect; the proxy pipes it back, letting
 * the test assert which backend a given first byte was routed to.
 */
class TlsProxyServerTest {

    /** TLS ClientHello content type — the first byte the proxy keys protocol detection on. */
    private static final byte TLS_HANDSHAKE = 0x16;

    private Vertx vertx;
    private TlsProxyServer proxy;
    private NetServer httpBackend;
    private NetServer httpsBackend;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        if (proxy != null) {
            proxy.stop();
        }
        closeBackend(httpBackend);
        closeBackend(httpsBackend);
        vertx.close();
    }

    private static EmulatorConfig configWith(boolean tlsEnabled, int publicPort, int awsHttpsPort) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
        when(config.port()).thenReturn(publicPort);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(tlsEnabled);
        when(tls.awsHttpsPort()).thenReturn(awsHttpsPort);
        when(config.security()).thenReturn(mock(EmulatorConfig.SecurityConfig.class));
        return config;
    }

    @Test
    void listenPorts_includesPublicAndAwsHttpsPort() {
        proxy = new TlsProxyServer(vertx, configWith(false, 4566, 443), "127.0.0.1", 4510, 4511);
        assertEquals(Set.of(4566, 443), proxy.listenPorts());
    }

    @Test
    void listenPorts_dropsAwsHttpsPortWhenZero() {
        proxy = new TlsProxyServer(vertx, configWith(false, 4566, 0), "127.0.0.1", 4510, 4511);
        assertEquals(Set.of(4566), proxy.listenPorts());
    }

    @Test
    void listenPorts_dedupesWhenAwsHttpsPortEqualsPublic() {
        proxy = new TlsProxyServer(vertx, configWith(false, 4566, 4566), "127.0.0.1", 4510, 4511);
        assertEquals(Set.of(4566), proxy.listenPorts());
    }

    @Test
    @Timeout(20)
    void bindsTheConfiguredHost_notEveryInterface() throws Exception {
        InetAddress external = nonLoopbackIpv4();
        assumeTrue(external != null, "needs a non-loopback IPv4 interface");
        int publicPort = freePort();

        proxy = new TlsProxyServer(vertx, configWith(true, publicPort, 0), "127.0.0.1", 4510, 4511);

        awaitListening(publicPort);
        assertFalse(accepts(external, publicPort),
                "a proxy configured for 127.0.0.1 must not be reachable on " + external.getHostAddress());
    }

    @Test
    @Timeout(20)
    void nonLoopbackHostWithoutConsent_refusesToListen() throws Exception {
        int publicPort = freePort();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TlsProxyServer(vertx, configWith(true, publicPort, 0), "0.0.0.0", 4510, 4511));

        assertTrue(e.getMessage().contains("FLOCI_SECURITY_ALLOW_UNSAFE_NETWORK_EXPOSURE"), e.getMessage());
        assertFalse(portAccepts(publicPort), "a refused proxy must not listen");
    }

    @Test
    @Timeout(20)
    void nonLoopbackHostWithConsent_listens() throws Exception {
        int publicPort = freePort();
        EmulatorConfig config = configWith(true, publicPort, 0);
        when(config.security().allowUnsafeNetworkExposure()).thenReturn(true);

        proxy = new TlsProxyServer(vertx, config, "0.0.0.0", 4510, 4511);

        awaitListening(publicPort);
    }

    @Test
    @Timeout(20)
    void bindsBothPorts_andRoutesByFirstByte() throws Exception {
        int httpBe = freePort();
        int httpsBe = freePort();
        int publicPort = freePort();
        int awsHttpsPort = freePort();
        httpBackend = startMarkerBackend(httpBe, "PLAIN");
        httpsBackend = startMarkerBackend(httpsBe, "TLS");

        proxy = new TlsProxyServer(vertx, configWith(true, publicPort, awsHttpsPort), "127.0.0.1", httpBe, httpsBe);

        // Public port: TLS ClientHello → HTTPS backend; anything else → HTTP backend.
        assertEquals("TLS", roundTrip(publicPort, TLS_HANDSHAKE));
        assertEquals("PLAIN", roundTrip(publicPort, (byte) 'G'));
        // AWS-HTTPS port: same protocol detection as the public port.
        assertEquals("TLS", roundTrip(awsHttpsPort, TLS_HANDSHAKE));
        assertEquals("PLAIN", roundTrip(awsHttpsPort, (byte) 'G'));
    }

    @Test
    @Timeout(20)
    void tlsDisabled_bindsNothing() throws Exception {
        int publicPort = freePort();
        proxy = new TlsProxyServer(vertx, configWith(false, publicPort, 443), "127.0.0.1", 4510, 4511);
        assertFalse(portAccepts(publicPort), "no proxy should listen when TLS is disabled");
    }

    // ---- helpers ----

    private NetServer startMarkerBackend(int port, String marker) throws Exception {
        NetServer server = vertx.createNetServer();
        server.connectHandler(sock -> sock.write(marker));
        CompletableFuture<Void> started = new CompletableFuture<>();
        server.listen(port, "127.0.0.1").onComplete(ar -> {
            if (ar.succeeded()) {
                started.complete(null);
            } else {
                started.completeExceptionally(ar.cause());
            }
        });
        started.get(5, TimeUnit.SECONDS);
        return server;
    }

    private String roundTrip(int proxyPort, byte firstByte) throws Exception {
        awaitListening(proxyPort);
        try (Socket sock = new Socket("127.0.0.1", proxyPort)) {
            sock.getOutputStream().write(new byte[]{firstByte});
            sock.getOutputStream().flush();
            byte[] buf = new byte[64];
            int n = sock.getInputStream().read(buf);
            assertTrue(n > 0, "expected a marker response from the backend");
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        }
    }

    private void awaitListening(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                return;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }
        fail("port " + port + " not listening within timeout");
    }

    private boolean portAccepts(int port) throws Exception {
        // Give any (unexpected) async bind a brief chance to come up, then probe once.
        Thread.sleep(500);
        try (Socket s = new Socket("127.0.0.1", port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean accepts(InetAddress address, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(address, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static InetAddress nonLoopbackIpv4() throws SocketException {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address) {
                    return address;
                }
            }
        }
        return null;
    }

    private static void closeBackend(NetServer server) {
        if (server != null) {
            server.close();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
