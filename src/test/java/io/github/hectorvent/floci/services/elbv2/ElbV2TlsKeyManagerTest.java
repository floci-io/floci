package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.core.common.tls.ReloadingKeyManager;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.JdkSSLEngineOptions;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.PemKeyCertOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated("Temporarily configures JSSE client session tickets")
class ElbV2TlsKeyManagerTest {

    private static final String HOST = "refresh.example.test";

    @Test
    void verifiedHandshakesContinueWhileReplacementKeysAreLoading() throws Exception {
        CertificateGenerator.GeneratedCertificate first = certificate(HOST);
        CertificateGenerator.GeneratedCertificate second = certificate(HOST);
        Vertx vertx = Vertx.vertx();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = null;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            ReloadingKeyManager manager = new ReloadingKeyManager(new ElbV2TlsKeyManager(vertx, pem(first)));
            server = server(vertx, manager);
            assertEquals(Pem.parseCertificate(first.certificatePem()), handshake(server.actualPort(), HOST, first, second));

            PemKeyCertOptions replacement = pem(second);
            KeyCertOptions paused = new KeyCertOptions() {
                @Override
                public KeyCertOptions copy() {
                    return this;
                }

                @Override
                public KeyManagerFactory getKeyManagerFactory(Vertx runtime) throws Exception {
                    entered.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS), "replacement key loading was not released");
                    return replacement.getKeyManagerFactory(runtime);
                }

                @Override
                public Function<String, KeyManagerFactory> keyManagerFactoryMapper(Vertx runtime) throws Exception {
                    return replacement.keyManagerFactoryMapper(runtime);
                }
            };
            Future<?> update = executor.submit(() -> manager.reload(new ElbV2TlsKeyManager(vertx, paused)));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS), "replacement key loading did not start");
                assertFalse(update.isDone());
                for (int i = 0; i < 3; i++) {
                    assertEquals(Pem.parseCertificate(first.certificatePem()),
                            handshake(server.actualPort(), HOST, first, second));
                }
            } finally {
                release.countDown();
            }
            update.get(10, TimeUnit.SECONDS);
            assertEquals(Pem.parseCertificate(second.certificatePem()), handshake(server.actualPort(), HOST, first, second));
        } finally {
            release.countDown();
            close(server, vertx);
        }
    }

    @Test
    void failedReplacementLeavesVerifiedHandshakesOnThePreviousCertificate() throws Exception {
        CertificateGenerator.GeneratedCertificate first = certificate(HOST);
        Vertx vertx = Vertx.vertx();
        HttpServer server = null;
        try {
            ReloadingKeyManager manager = new ReloadingKeyManager(new ElbV2TlsKeyManager(vertx, pem(first)));
            server = server(vertx, manager);
            PemKeyCertOptions invalid = new PemKeyCertOptions().addCertValue(Buffer.buffer("invalid"))
                    .addKeyValue(Buffer.buffer("invalid"));

            assertThrows(IllegalStateException.class,
                    () -> manager.reload(new ElbV2TlsKeyManager(vertx, invalid)));

            assertEquals(Pem.parseCertificate(first.certificatePem()), handshake(server.actualPort(), HOST, first));
        } finally {
            close(server, vertx);
        }
    }

    @Test
    void sniSelectsAdditionalCertificateAndReloadRemovesIt() throws Exception {
        CertificateGenerator.GeneratedCertificate first = certificate(HOST);
        CertificateGenerator.GeneratedCertificate additional = certificate("additional.example.test");
        Vertx vertx = Vertx.vertx();
        HttpServer server = null;
        try {
            ReloadingKeyManager manager = new ReloadingKeyManager(new ElbV2TlsKeyManager(vertx, pem(first)));
            server = server(vertx, manager);
            manager.reload(new ElbV2TlsKeyManager(vertx, pem(first, additional)));
            assertEquals(Pem.parseCertificate(additional.certificatePem()),
                    handshake(server.actualPort(), "additional.example.test", first, additional));
            assertEquals(Pem.parseCertificate(first.certificatePem()), handshake(server.actualPort(), HOST, first, additional));

            manager.reload(new ElbV2TlsKeyManager(vertx, pem(first)));
            int port = server.actualPort();
            assertThrows(SSLHandshakeException.class,
                    () -> handshake(port, "additional.example.test", first, additional));
            assertEquals(Pem.parseCertificate(first.certificatePem()), handshake(port, HOST, first));
        } finally {
            close(server, vertx);
        }
    }

    @Test
    void wildcardAndMissingSniPreserveCertificateSelection() throws Exception {
        CertificateGenerator.GeneratedCertificate defaultCertificate = certificate("default.other.test");
        CertificateGenerator.GeneratedCertificate wildcard = certificate("*.example.test");
        Vertx vertx = Vertx.vertx();
        HttpServer server = null;
        try {
            ReloadingKeyManager manager = new ReloadingKeyManager(new ElbV2TlsKeyManager(vertx, pem(defaultCertificate, wildcard)));
            server = server(vertx, manager);
            assertEquals(Pem.parseCertificate(wildcard.certificatePem()),
                    handshake(server.actualPort(), HOST, true, defaultCertificate, wildcard));
            assertEquals(Pem.parseCertificate(defaultCertificate.certificatePem()),
                    handshake(server.actualPort(), "default.other.test", false, defaultCertificate, wildcard));
        } finally {
            close(server, vertx);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reusedClientContextTracksCertificateReplacementAndRemoval(boolean sessionTickets) throws Exception {
        CertificateGenerator.GeneratedCertificate defaultCertificate = certificate("default.other.test");
        CertificateGenerator.GeneratedCertificate first = certificate(HOST);
        CertificateGenerator.GeneratedCertificate replacement = certificate(HOST);
        Vertx vertx = Vertx.vertx();
        HttpServer server = null;
        try {
            ReloadingKeyManager manager = new ReloadingKeyManager(
                    new ElbV2TlsKeyManager(vertx, pem(first)));
            server = server(vertx, manager);
            int port = server.actualPort();
            SSLContext client = clientContextWithSessionTickets(sessionTickets, defaultCertificate, first, replacement);
            SSLSession initial = handshake(port, HOST, true, client);
            assertEquals("TLSv1.2", initial.getProtocol());
            assertTrue(initial.getId().length > 0);
            assertEquals(Pem.parseCertificate(first.certificatePem()), initial.getPeerCertificates()[0]);

            SSLSession beforeReplacement = handshake(port, HOST, true, client);
            assertArrayEquals(initial.getId(), beforeReplacement.getId());

            manager.reload(new ElbV2TlsKeyManager(vertx, pem(replacement)));
            SSLSession afterReplacement = handshake(port, HOST, true, client);
            assertEquals(!sessionTickets, Arrays.equals(initial.getId(), afterReplacement.getId()));
            CertificateGenerator.GeneratedCertificate expected = sessionTickets ? replacement : first;
            assertEquals(Pem.parseCertificate(expected.certificatePem()), afterReplacement.getPeerCertificates()[0]);
            SSLSession resumedReplacement = handshake(port, HOST, true, client);
            assertArrayEquals(afterReplacement.getId(), resumedReplacement.getId());
            assertEquals(Pem.parseCertificate(replacement.certificatePem()),
                    handshake(port, HOST, defaultCertificate, first, replacement));

            manager.reload(new ElbV2TlsKeyManager(vertx, pem(defaultCertificate)));
            if (!sessionTickets) {
                SSLSession afterRemoval = handshake(port, HOST, true, client);
                assertArrayEquals(initial.getId(), afterRemoval.getId());
                assertEquals(Pem.parseCertificate(first.certificatePem()), afterRemoval.getPeerCertificates()[0]);
                afterRemoval.invalidate();
            }
            assertThrows(SSLHandshakeException.class, () -> handshake(port, HOST, true, client));
            assertEquals(Pem.parseCertificate(defaultCertificate.certificatePem()),
                    handshake(port, "default.other.test", defaultCertificate));
        } finally {
            close(server, vertx);
        }
    }

    private static CertificateGenerator.GeneratedCertificate certificate(String host) {
        return new CertificateGenerator().generateSelfSignedCertificate(host, List.of(), KeyAlgorithm.RSA_2048);
    }

    private static PemKeyCertOptions pem(CertificateGenerator.GeneratedCertificate... certificates) {
        PemKeyCertOptions options = new PemKeyCertOptions();
        for (CertificateGenerator.GeneratedCertificate certificate : certificates) {
            options.addKeyValue(Buffer.buffer(certificate.privateKeyPem()));
            options.addCertValue(Buffer.buffer(certificate.certificatePem()));
        }
        return options;
    }

    private static HttpServer server(Vertx vertx, ReloadingKeyManager manager) throws Exception {
        return vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0)
                        .setSsl(true).setSslEngineOptions(new JdkSSLEngineOptions())
                        .setKeyCertOptions(KeyCertOptions.wrap(manager)))
                .requestHandler(request -> request.response().end("ok"))
                .listen().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static X509Certificate handshake(int port, String host,
                                             CertificateGenerator.GeneratedCertificate... trusted) throws Exception {
        return handshake(port, host, true, trusted);
    }

    private static X509Certificate handshake(int port, String host, boolean sni,
                                             CertificateGenerator.GeneratedCertificate... trusted) throws Exception {
        SSLSession session = handshake(port, host, sni, clientContext("TLS", trusted));
        return (X509Certificate) session.getPeerCertificates()[0];
    }

    private static SSLContext clientContextWithSessionTickets(boolean enabled,
            CertificateGenerator.GeneratedCertificate... trusted) throws Exception {
        String property = "jdk.tls.client.enableSessionTicketExtension";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, Boolean.toString(enabled));
            return clientContext("TLSv1.2", trusted);
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    private static SSLContext clientContext(String protocol,
                                           CertificateGenerator.GeneratedCertificate... trusted) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        for (int i = 0; i < trusted.length; i++) {
            store.setCertificateEntry(Integer.toString(i), Pem.parseCertificate(trusted[i].certificatePem()));
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        SSLContext context = SSLContext.getInstance(protocol);
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    private static SSLSession handshake(int port, String host, boolean sni, SSLContext context) throws Exception {
        try (Socket transport = new Socket()) {
            transport.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(transport, host, port, true)) {
                socket.setSoTimeout(2000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                if (!sni) {
                    parameters.setServerNames(List.of());
                }
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + host
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertTrue(response.startsWith("HTTP/1.1 200"));
                assertTrue(response.endsWith("ok"));
                return socket.getSession();
            }
        }
    }

    private static void close(HttpServer server, Vertx vertx) throws Exception {
        try {
            if (server != null) {
                server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}
