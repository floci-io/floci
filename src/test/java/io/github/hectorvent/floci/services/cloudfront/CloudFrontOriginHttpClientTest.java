package io.github.hectorvent.floci.services.cloudfront;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.apache.hc.client5.http.DnsResolver;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudFrontOriginHttpClientTest {

    @Test
    void rejectsPrivateAddressBeforeConnecting() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = server(hits);
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of())) {
            HttpRequest request = request("http://blocked.invalid:" + server.getAddress().getPort() + "/");

            assertThrows(UnknownHostException.class,
                    () -> client.send(request, HttpResponse.BodyHandlers.ofByteArray()));
            assertEquals(0, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsEveryMixedAnswerContainingABlockedAddress() throws Exception {
        DnsResolver resolver = resolver(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("127.0.0.1"));
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(resolver, List.of())) {
            assertThrows(UnknownHostException.class, () -> client.send(
                    request("http://mixed.invalid:8080/"),
                    HttpResponse.BodyHandlers.ofByteArray()));
        }
    }

    @Test
    void connectsWithTheSingleValidatedResolutionAndPreservesHostHeader() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> hostHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            hostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            byte[] body = "origin-ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        AtomicInteger resolutions = new AtomicInteger();
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                if (resolutions.incrementAndGet() > 1) {
                    return new InetAddress[] { InetAddress.getByName("203.0.113.10") };
                }
                return new InetAddress[] { InetAddress.getByName("127.0.0.1") };
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };

        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver, List.of("rebind.invalid"))) {
            HttpResponse<byte[]> response = client.send(
                    request("http://rebind.invalid:" + server.getAddress().getPort() + "/"),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, response.statusCode());
            assertArrayEquals("origin-ok".getBytes(StandardCharsets.UTF_8), response.body());
            assertEquals(1, resolutions.get());
            assertEquals(1, hits.get());
            assertEquals("rebind.invalid:" + server.getAddress().getPort(), hostHeader.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotFollowOriginRedirects() throws Exception {
        AtomicInteger redirectedHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            redirectedHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of("redirect.invalid"))) {
            HttpResponse<byte[]> response = client.send(
                    request("http://redirect.invalid:" + server.getAddress().getPort() + "/start"),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(302, response.statusCode());
            assertEquals(0, redirectedHits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpsPinningPreservesSniAndHostnameVerification() throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        CertificateGenerator generator = new CertificateGenerator();
        CertificateGenerator.GeneratedCertificate generated = generator.generateSelfSignedCertificate(
                "origin.invalid", List.of("origin.invalid"), KeyAlgorithm.RSA_2048);
        X509Certificate certificate = generator.parseCertificate(generated.certificatePem());
        SSLContext serverContext = serverSslContext(
                certificate, generator.parsePrivateKey(generated.privateKeyPem()));
        SSLContext clientContext = clientSslContext(certificate);

        AtomicReference<String> hostHeader = new AtomicReference<>();
        AtomicReference<String> requestedSni = new AtomicReference<>();
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/", exchange -> {
            hostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            if (exchange instanceof HttpsExchange httpsExchange
                    && httpsExchange.getSSLSession() instanceof ExtendedSSLSession sslSession) {
                for (SNIServerName name : sslSession.getRequestedServerNames()) {
                    if (name instanceof SNIHostName sniHostName) {
                        requestedSni.set(sniHostName.getAsciiName());
                    }
                }
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        DnsResolver resolver = resolver(InetAddress.getByName("127.0.0.1"));
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver, List.of("origin.invalid", "wrong.invalid"), clientContext)) {
            int port = server.getAddress().getPort();
            HttpResponse<byte[]> response = client.send(
                    request("https://origin.invalid:" + port + "/"),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, response.statusCode());
            assertEquals("origin.invalid:" + port, hostHeader.get());
            assertEquals("origin.invalid", requestedSni.get());
            assertThrows(IOException.class, () -> client.send(
                    request("https://wrong.invalid:" + port + "/"),
                    HttpResponse.BodyHandlers.ofByteArray()));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server(AtomicInteger hits) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static HttpRequest request(String uri) {
        return HttpRequest.newBuilder(java.net.URI.create(uri))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
    }

    private static DnsResolver resolver(InetAddress... addresses) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) {
                return addresses.clone();
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    private static SSLContext serverSslContext(
            X509Certificate certificate, java.security.PrivateKey privateKey) throws Exception {
        char[] password = "changeit".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setKeyEntry("origin", privateKey, password,
                new java.security.cert.Certificate[] { certificate });
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, password);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    private static SSLContext clientSslContext(X509Certificate certificate) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("origin", certificate);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return context;
    }
}
