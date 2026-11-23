package io.floci.aws.config;

import io.floci.aws.testutil.FreePorts;
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
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;
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
        when(config.baseUrl()).thenReturn("http://base.example.test:4566");
        when(config.hostname()).thenReturn(Optional.of("api.example.test"));
        when(config.dns()).thenReturn(mock(EmulatorConfig.DnsConfig.class));
        when(config.services()).thenReturn(mock(EmulatorConfig.ServicesConfig.class));
        when(config.services().lambda()).thenReturn(mock(EmulatorConfig.LambdaServiceConfig.class));
        when(config.services().iot()).thenReturn(mock(EmulatorConfig.IotServiceConfig.class));
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
        int publicPort = FreePorts.anyInterfacePort();

        proxy = new TlsProxyServer(vertx, configWith(true, publicPort, 0), "127.0.0.1", 4510, 4511);

        awaitListening(publicPort);
        assertFalse(accepts(external, publicPort),
                "a proxy configured for 127.0.0.1 must not be reachable on " + external.getHostAddress());
    }

    @Test
    @Timeout(20)
    void nonLoopbackHostWithoutConsent_refusesToListen() throws Exception {
        int publicPort = FreePorts.anyInterfacePort();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TlsProxyServer(vertx, configWith(true, publicPort, 0), "0.0.0.0", 4510, 4511));

        assertTrue(e.getMessage().contains("FLOCI_SECURITY_ALLOW_UNSAFE_NETWORK_EXPOSURE"), e.getMessage());
        assertFalse(portAccepts(publicPort), "a refused proxy must not listen");
    }

    @Test
    @Timeout(20)
    void nonLoopbackHostWithConsent_listens() throws Exception {
        int publicPort = FreePorts.anyInterfacePort();
        EmulatorConfig config = configWith(true, publicPort, 0);
        when(config.security().allowUnsafeNetworkExposure()).thenReturn(true);

        proxy = new TlsProxyServer(vertx, config, "0.0.0.0", 4510, 4511);

        awaitListening(publicPort);
    }

    @Test
    @Timeout(20)
    void bindsBothPorts_andRoutesByFirstByte() throws Exception {
        httpBackend = startMarkerBackend("PLAIN");
        httpsBackend = startMarkerBackend("TLS");
        int[] ports = FreePorts.anyInterfacePorts(2);
        int publicPort = ports[0];
        int awsHttpsPort = ports[1];

        proxy = new TlsProxyServer(vertx, configWith(true, publicPort, awsHttpsPort), "127.0.0.1",
                httpBackend.actualPort(), httpsBackend.actualPort());

        // Public port: TLS ClientHello → HTTPS backend; anything else → HTTP backend.
        assertEquals("TLS", roundTrip(publicPort, TLS_HANDSHAKE));
        assertEquals("PLAIN", roundTrip(publicPort, (byte) 'G'));
        // AWS-HTTPS port: same protocol detection as the public port.
        assertEquals("TLS", roundTrip(awsHttpsPort, TLS_HANDSHAKE));
        assertEquals("PLAIN", roundTrip(awsHttpsPort, (byte) 'G'));
    }

    @Test
    @Timeout(20)
    void awsHttpsPort_routesAKnownServerNameAndLeavesTheRestToFloci() throws Exception {
        httpBackend = startMarkerBackend("PLAIN");
        httpsBackend = startMarkerBackend("TLS");
        NetServer albBackend = startMarkerBackend("ALB");
        try {
            int[] ports = FreePorts.anyInterfacePorts(2);
            int publicPort = ports[0];
            int awsHttpsPort = ports[1];
            EmulatorConfig config = configWith(true, publicPort, awsHttpsPort);
            when(config.services().iot().endpointAddress()).thenReturn(Optional.of("  MQTT.example.test:8443  "));
            proxy = new TlsProxyServer(vertx, config, "127.0.0.1",
                    httpBackend.actualPort(), httpsBackend.actualPort());
            proxy.routeServerNames((port, name, allowAliases) -> port == awsHttpsPort && allowAliases && !name.equals("other.example.test")
                    ? albBackend.actualPort() : -1);

            assertEquals("ALB", roundTrip(awsHttpsPort, TlsClientHelloTest.clientHello("ALB.example.test").getBytes()));
            assertEquals("TLS", roundTrip(awsHttpsPort, TlsClientHelloTest.clientHello("other.example.test").getBytes()));
            assertEquals("TLS", roundTrip(awsHttpsPort, TlsClientHelloTest.clientHello(null).getBytes()));
            for (String endpoint : Set.of("localhost.floci.io", "api.example.test", "base.example.test",
                    "host.docker.internal", "sqs.us-east-1.localhost.floci.io", "mqtt.example.test")) {
                assertEquals("TLS", roundTrip(awsHttpsPort, TlsClientHelloTest.clientHello(endpoint).getBytes()),
                        endpoint);
            }
            assertEquals("PLAIN", roundTrip(awsHttpsPort, new byte[]{(byte) 'G'}));
            assertEquals("TLS", roundTrip(publicPort, TlsClientHelloTest.clientHello("alb.example.test").getBytes()),
                    "the public port never routes by server name");
        } finally {
            closeBackend(albBackend);
        }
    }

    @Test
    @Timeout(20)
    void awsHttpsPort_waitsForAClientHelloSplitAcrossWrites() throws Exception {
        httpsBackend = startMarkerBackend("TLS");
        NetServer albBackend = startMarkerBackend("ALB");
        try {
            int[] ports = FreePorts.anyInterfacePorts(2);
            int awsHttpsPort = ports[1];
            proxy = new TlsProxyServer(vertx, configWith(true, ports[0], awsHttpsPort), "127.0.0.1",
                    4510, httpsBackend.actualPort());
            proxy.routeServerNames((port, name, allowAliases) -> "alb.example.test".equals(name) ? albBackend.actualPort() : -1);
            byte[] hello = TlsClientHelloTest.clientHello("alb.example.test").getBytes();

            awaitListening(awsHttpsPort);
            try (Socket sock = new Socket("127.0.0.1", awsHttpsPort)) {
                sock.setTcpNoDelay(true);
                sock.getOutputStream().write(hello, 0, 3);
                sock.getOutputStream().flush();
                Thread.sleep(100);
                sock.getOutputStream().write(hello, 3, hello.length - 3);
                sock.getOutputStream().flush();
                byte[] buf = new byte[64];
                int n = sock.getInputStream().read(buf);
                assertEquals("ALB", new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } finally {
            closeBackend(albBackend);
        }
    }

    @Test
    @Timeout(20)
    void awsHttpsPort_aRouteThatThrowsLeavesTheConnectionWithFloci() throws Exception {
        httpsBackend = startMarkerBackend("TLS");
        int[] ports = FreePorts.anyInterfacePorts(2);
        proxy = new TlsProxyServer(vertx, configWith(true, ports[0], ports[1]), "127.0.0.1",
                4510, httpsBackend.actualPort());
        proxy.routeServerNames((port, name, allowAliases) -> {
            throw new IllegalStateException("route failed");
        });

        assertEquals("TLS", roundTrip(ports[1], TlsClientHelloTest.clientHello("alb.example.test").getBytes()));
    }

    @Test
    @Timeout(20)
    void awsHttpsPort_aStalledPartialClientHelloGoesToFloci() throws Exception {
        httpsBackend = startMarkerBackend("TLS");
        int[] ports = FreePorts.anyInterfacePorts(2);
        proxy = new TlsProxyServer(vertx, configWith(true, ports[0], ports[1]), "127.0.0.1",
                4510, httpsBackend.actualPort());
        proxy.clientHelloTimeoutMillis = 200;
        proxy.routeServerNames((port, name, allowAliases) -> fail("a partial ClientHello has no server name to route"));
        byte[] hello = TlsClientHelloTest.clientHello("alb.example.test").getBytes();

        awaitListening(ports[1]);
        try (Socket sock = new Socket("127.0.0.1", ports[1])) {
            sock.getOutputStream().write(hello, 0, 3);
            sock.getOutputStream().flush();
            byte[] buf = new byte[64];
            int n = sock.getInputStream().read(buf);
            assertEquals("TLS", new String(buf, 0, n, StandardCharsets.UTF_8));
        }
    }

    @Test
    @Timeout(20)
    void awsHttpsPort_aClientHelloTrickledInGoesToFlociAtAFixedDeadline() throws Exception {
        httpsBackend = startMarkerBackend("TLS");
        int[] ports = FreePorts.anyInterfacePorts(2);
        proxy = new TlsProxyServer(vertx, configWith(true, ports[0], ports[1]), "127.0.0.1",
                4510, httpsBackend.actualPort());
        proxy.clientHelloTimeoutMillis = 500;
        proxy.routeServerNames((port, name, allowAliases) -> fail("the deadline passed before the ClientHello was complete"));
        byte[] hello = TlsClientHelloTest.clientHello("alb.example.test").getBytes();

        awaitListening(ports[1]);
        try (Socket sock = new Socket("127.0.0.1", ports[1])) {
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(100);
            byte[] buf = new byte[64];
            // One byte every 100ms keeps the buffer growing for far longer than the deadline.
            for (int i = 0; i < hello.length - 1; i++) {
                sock.getOutputStream().write(hello[i]);
                sock.getOutputStream().flush();
                try {
                    int n = sock.getInputStream().read(buf);
                    assertEquals("TLS", new String(buf, 0, n, StandardCharsets.UTF_8));
                    return;
                } catch (SocketTimeoutException stillWaiting) {
                    // No backend yet; the next byte follows.
                }
            }
            fail("a trickling client held the connection past its deadline");
        }
    }

    @Test
    @Timeout(20)
    void tlsDisabled_bindsNothing() throws Exception {
        int publicPort = FreePorts.anyInterfacePort();
        proxy = new TlsProxyServer(vertx, configWith(false, publicPort, 443), "127.0.0.1", 4510, 4511);
        assertFalse(portAccepts(publicPort), "no proxy should listen when TLS is disabled");
    }

    // ---- helpers ----

    private NetServer startMarkerBackend(String marker) throws Exception {
        NetServer server = vertx.createNetServer();
        server.connectHandler(sock -> sock.write(marker));
        CompletableFuture<Void> started = new CompletableFuture<>();
        server.listen(0, "127.0.0.1").onComplete(ar -> {
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
        return roundTrip(proxyPort, new byte[]{firstByte});
    }

    private String roundTrip(int proxyPort, byte[] firstBytes) throws Exception {
        awaitListening(proxyPort);
        try (Socket sock = new Socket("127.0.0.1", proxyPort)) {
            sock.getOutputStream().write(firstBytes);
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
}
