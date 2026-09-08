package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.tls.CertificateUpdatedEvent;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.quarkus.tls.runtime.config.TlsConfig;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.PemKeyCertOptions;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The MQTT over TLS listener against a real Vert.x and a mocked TLS registry: what it serves,
 * when it is not opened, how a bind failure is reported, and how a reloaded server certificate
 * reaches the next handshake without a restart.
 */
class IotMqttBrokerServiceTest {

    private static final CertificateGenerator GENERATOR = new CertificateGenerator();

    /**
     * AWS IoT's default security policy, IoTSecurityPolicy_TLS13_1_2_2022_10, in JSSE names: the
     * TLS 1.3 suites and the RSA-authenticated TLS 1.2 suites it lists (transport-security page).
     */
    private static final Set<String> AWS_TLS13_SUITES = Set.of(
            "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256");
    private static final Set<String> AWS_TLS12_RSA_SUITES = Set.of(
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_RSA_WITH_AES_128_GCM_SHA256", "TLS_RSA_WITH_AES_128_CBC_SHA256", "TLS_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_256_GCM_SHA384", "TLS_RSA_WITH_AES_256_CBC_SHA256", "TLS_RSA_WITH_AES_256_CBC_SHA");

    private static Vertx vertx;
    private static FlociCertificateAuthority ca;
    private static CertificateGenerator.GeneratedCertificate bootLeaf;
    private static CertificateGenerator.GeneratedCertificate reissuedLeaf;

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final TlsConfigurationRegistry registry = mock(TlsConfigurationRegistry.class);
    private final TlsConfiguration tls = mock(TlsConfiguration.class);
    @SuppressWarnings("unchecked")
    private final Instance<IotService> iotService = mock(Instance.class);
    private int plainPort;
    private int tlsPort;
    private IotMqttBrokerService broker;

    @BeforeAll
    static void keyMaterial(@TempDir Path dir) {
        vertx = Vertx.vertx();
        ca = FlociCertificateAuthority.loadOrCreate(dir);
        bootLeaf = ca.issueServerCertificate("localhost", List.of("localhost", "127.0.0.1"), KeyAlgorithm.RSA_2048, null);
        reissuedLeaf = ca.issueServerCertificate("localhost",
                List.of("localhost", "127.0.0.1", "iot.dev.localhost.floci.io"), KeyAlgorithm.RSA_2048, null);
    }

    @AfterAll
    static void closeVertx() {
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }

    @BeforeEach
    void brokerWithTlsOn() throws IOException {
        plainPort = freePort();
        do {
            tlsPort = freePort();
        } while (tlsPort == plainPort);
        when(config.services().iot().enabled()).thenReturn(true);
        when(config.services().iot().mqtt().enabled()).thenReturn(true);
        when(config.services().iot().mqtt().host()).thenReturn("127.0.0.1");
        when(config.services().iot().mqtt().port()).thenReturn(plainPort);
        when(config.services().iot().mqtt().tlsPort()).thenReturn(tlsPort);
        when(config.tls().enabled()).thenReturn(true);
        when(registry.getDefault()).thenReturn(Optional.of(tls));
        when(tls.getKeyStoreOptions()).thenReturn(pem(bootLeaf));
        broker = new IotMqttBrokerService(config, vertx, iotService, registry);
    }

    @AfterEach
    void stopBroker() {
        broker.stop();
    }

    @Test
    void tlsListenerServesTheRegistryLeafAndAsksForAClientCertificateWithoutRequiringOne() throws Exception {
        broker.startIfEnabled();

        RecordingKeyManager clientKeys = new RecordingKeyManager();
        X509Certificate served = handshake(new KeyManager[] {clientKeys});

        assertEquals(serial(bootLeaf), served.getSerialNumber(), "the leaf from the TLS registry's default configuration");
        assertTrue(clientKeys.asked.get(), "the server sent a CertificateRequest");
        assertTrue(accepts(plainPort), "the plaintext listener is up as well");
    }

    @Test
    void aDeviceOnAwsDefaultSecurityPolicyNegotiatesTls13() throws Exception {
        broker.startIfEnabled();

        SSLSession session = negotiate("TLSv1.3", AWS_TLS13_SUITES);

        assertEquals("TLSv1.3", session.getProtocol());
        assertTrue(AWS_TLS13_SUITES.contains(session.getCipherSuite()), session.getCipherSuite());
    }

    @Test
    void aDeviceOnAwsDefaultSecurityPolicyNegotiatesTls12() throws Exception {
        broker.startIfEnabled();

        SSLSession session = negotiate("TLSv1.2", AWS_TLS12_RSA_SUITES);

        assertEquals("TLSv1.2", session.getProtocol());
        assertTrue(AWS_TLS12_RSA_SUITES.contains(session.getCipherSuite()), session.getCipherSuite());
    }

    @Test
    void aClientCertificateFromAnyIssuerCompletesTheHandshake() throws Exception {
        broker.startIfEnabled();
        var device = GENERATOR.generateSelfSignedCertificate("device", List.of(), KeyAlgorithm.RSA_2048);

        X509Certificate served = handshake(keyManagers(device));

        assertEquals(serial(bootLeaf), served.getSerialNumber());
    }

    @Test
    void tlsPortZeroOpensOnlyThePlaintextListener() {
        when(config.services().iot().mqtt().tlsPort()).thenReturn(0);

        broker.startIfEnabled();

        assertTrue(accepts(plainPort));
        verifyNoInteractions(registry);
    }

    @Test
    void tlsDisabledOpensOnlyThePlaintextListener() {
        when(config.tls().enabled()).thenReturn(false);

        broker.startIfEnabled();

        assertTrue(accepts(plainPort));
        assertFalse(accepts(tlsPort), "no TLS listener while floci.tls.enabled is false");
        verifyNoInteractions(registry);
    }

    @Test
    void noDefaultTlsConfigurationLeavesTheTlsPortClosed() {
        when(registry.getDefault()).thenReturn(Optional.empty());

        broker.startIfEnabled();

        assertTrue(broker.isRunning());
        assertTrue(accepts(plainPort));
        assertFalse(accepts(tlsPort));
    }

    @Test
    void aTlsBindFailureLeavesNoListenerBehindAndTheNextStartSucceeds() throws Exception {
        try (ServerSocket blocker = new ServerSocket()) {
            blocker.bind(new InetSocketAddress("127.0.0.1", tlsPort));

            IllegalStateException failure = assertThrows(IllegalStateException.class, broker::startIfEnabled);

            assertTrue(failure.getMessage().contains(Integer.toString(tlsPort)), failure.getMessage());
            assertFalse(broker.isRunning());
            awaitClosed(plainPort);
        }

        broker.startIfEnabled();

        assertTrue(broker.isRunning());
        assertTrue(accepts(plainPort));
        assertEquals(serial(bootLeaf), handshake(null).getSerialNumber());
    }

    @Test
    void certificateUpdatedEventSwapsTheLeafForTheNextHandshake() throws Exception {
        broker.startIfEnabled();
        assertEquals(serial(bootLeaf), handshake(null).getSerialNumber());
        when(tls.getKeyStoreOptions()).thenReturn(pem(reissuedLeaf));

        broker.onCertificateUpdated(new CertificateUpdatedEvent(TlsConfig.DEFAULT_NAME, tls));

        assertEquals(serial(reissuedLeaf), handshake(null).getSerialNumber());
    }

    @Test
    void anEventForAnotherTlsConfigurationIsIgnored() throws Exception {
        broker.startIfEnabled();
        when(tls.getKeyStoreOptions()).thenReturn(pem(reissuedLeaf));

        broker.onCertificateUpdated(new CertificateUpdatedEvent("rds-proxy", tls));

        assertEquals(serial(bootLeaf), handshake(null).getSerialNumber());
    }

    @Test
    void aFailedSwapKeepsThePreviousLeaf() throws Exception {
        broker.startIfEnabled();
        when(tls.getKeyStoreOptions()).thenReturn(new PemKeyCertOptions()
                .addCertValue(Buffer.buffer("not a certificate"))
                .addKeyValue(Buffer.buffer("not a key")));

        broker.onCertificateUpdated(new CertificateUpdatedEvent(TlsConfig.DEFAULT_NAME, tls));

        assertEquals(serial(bootLeaf), handshake(null).getSerialNumber());
    }

    @Test
    void eventsBeforeStartAndAfterStopAreIgnored() {
        broker.onCertificateUpdated(new CertificateUpdatedEvent(TlsConfig.DEFAULT_NAME, tls));

        broker.startIfEnabled();
        broker.stop();
        broker.onCertificateUpdated(new CertificateUpdatedEvent(TlsConfig.DEFAULT_NAME, tls));

        assertFalse(broker.isRunning());
        awaitClosed(tlsPort);
        awaitClosed(plainPort);
    }

    @Test
    void stopClosesBothListenersAndStartReopensThem() throws Exception {
        broker.startIfEnabled();
        broker.stop();
        awaitClosed(tlsPort);
        awaitClosed(plainPort);
        when(tls.getKeyStoreOptions()).thenReturn(pem(reissuedLeaf));

        broker.startIfEnabled();

        assertTrue(accepts(plainPort));
        assertEquals(serial(reissuedLeaf), handshake(null).getSerialNumber(), "a restart reads the registry again");
    }

    /**
     * Reloads while connections arrive: every handshake completes with one of the two leaves.
     * Rebuilding the listener's SSL context instead would drop the connections accepted while
     * the new context is being built (Vert.x serves a pending update to them as null).
     */
    @Test
    void concurrentCertificateEventsAndHandshakesAllSucceed() throws Exception {
        broker.startIfEnabled();
        when(tls.getKeyStoreOptions()).thenAnswer(ignored -> pem(reissuedLeaf));
        int threads = 8;
        int rounds = 10;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads * 2);
        List<Future<?>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                outcomes.add(pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < rounds; round++) {
                        broker.onCertificateUpdated(new CertificateUpdatedEvent(TlsConfig.DEFAULT_NAME, tls));
                    }
                    return null;
                }));
                outcomes.add(pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < rounds; round++) {
                        X509Certificate served = handshake(null);
                        assertTrue(served.getSerialNumber().equals(serial(bootLeaf))
                                || served.getSerialNumber().equals(serial(reissuedLeaf)), "one of the two leaves");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> outcome : outcomes) {
                outcome.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(serial(reissuedLeaf), handshake(null).getSerialNumber());
    }

    private X509Certificate handshake(KeyManager[] clientKeys) throws Exception {
        try (SSLSocket socket = connectTls(clientKeys)) {
            socket.startHandshake();
            return (X509Certificate) socket.getSession().getPeerCertificates()[0];
        }
    }

    /** A handshake as a device pinned to one protocol version and AWS's suites for it would do. */
    private SSLSession negotiate(String protocol, Set<String> suites) throws Exception {
        try (SSLSocket socket = connectTls(null)) {
            socket.setEnabledProtocols(new String[] {protocol});
            socket.setEnabledCipherSuites(List.of(socket.getSupportedCipherSuites()).stream()
                    .filter(suites::contains).toArray(String[]::new));
            socket.startHandshake();
            return socket.getSession();
        }
    }

    private SSLSocket connectTls(KeyManager[] clientKeys) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("floci", ca.certificate());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(clientKeys, tmf.getTrustManagers(), null);
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
        socket.connect(new InetSocketAddress("127.0.0.1", tlsPort), 5_000);
        return socket;
    }

    private static KeyManager[] keyManagers(CertificateGenerator.GeneratedCertificate leaf) throws Exception {
        KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType());
        keys.load(null, null);
        PrivateKey key = GENERATOR.parsePrivateKey(leaf.privateKeyPem());
        keys.setKeyEntry("device", key, new char[0], new Certificate[] {GENERATOR.parseCertificate(leaf.certificatePem())});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, new char[0]);
        return kmf.getKeyManagers();
    }

    private static KeyCertOptions pem(CertificateGenerator.GeneratedCertificate leaf) {
        return new PemKeyCertOptions()
                .addCertValue(Buffer.buffer(leaf.certificatePem()))
                .addKeyValue(Buffer.buffer(leaf.privateKeyPem()));
    }

    private static java.math.BigInteger serial(CertificateGenerator.GeneratedCertificate leaf) {
        return GENERATOR.parseCertificate(leaf.certificatePem()).getSerialNumber();
    }

    private static boolean accepts(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    /** Vert.x resolves close() a moment before the OS releases the port; poll like the lazy-start test. */
    private static void awaitClosed(int port) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (accepts(port)) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("port " + port + " still accepts connections");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for port " + port + " to close", e);
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** A client key manager with no certificate that records whether the server asked for one. */
    private static final class RecordingKeyManager extends X509ExtendedKeyManager {
        final AtomicBoolean asked = new AtomicBoolean();

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            asked.set(true);
            return null;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            asked.set(true);
            return null;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[0];
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return new String[0];
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return null;
        }
    }
}
