package io.github.hectorvent.floci.services.cloudfront;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import io.github.hectorvent.floci.core.common.Pem;
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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudFrontOriginHttpClientTest {

    @Test
    void rejectsPrivateAddressBeforeConnecting() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = server(hits);
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of())) {
            HttpRequest request = request("http://blocked.invalid:" + server.getAddress().getPort() + "/");

            assertThrows(UnknownHostException.class, () -> get(client, request));
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
            assertThrows(UnknownHostException.class, () -> get(client, request("http://mixed.invalid:8080/")));
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
            CloudFrontOriginHttpClient.OpenResponse response = get(client,
                    request("http://rebind.invalid:" + server.getAddress().getPort() + "/"));

            assertEquals(200, response.statusCode());
            assertArrayEquals("origin-ok".getBytes(StandardCharsets.UTF_8), readAll(response));
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
            CloudFrontOriginHttpClient.OpenResponse response = get(client,
                    request("http://redirect.invalid:" + server.getAddress().getPort() + "/start"));
            readAll(response);

            assertEquals(302, response.statusCode());
            assertEquals(0, redirectedHits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void originHeadersReplaceSameNamedRequestHeaders() throws Exception {
        AtomicReference<List<String>> receivedValues = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedValues.set(exchange.getRequestHeaders().get("X-Origin-Verify"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of("origin.invalid"))) {
            HttpRequest request = HttpRequest.newBuilder(java.net.URI.create(
                            "http://origin.invalid:" + server.getAddress().getPort() + "/"))
                    .timeout(Duration.ofSeconds(5))
                    .header("X-Origin-Verify", "viewer-value")
                    .GET()
                    .build();

            readAll(client.open(request, List.of(), Map.of("X-Origin-Verify", "configured-value"), null, -1));

            assertEquals(List.of("configured-value"), receivedValues.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsTheBodyWithItsExactLengthAndTheCallerContentType() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> contentLength = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            body.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        byte[] payload = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of("origin.invalid"))) {
            HttpRequest request = HttpRequest.newBuilder(java.net.URI.create(
                            "http://origin.invalid:" + server.getAddress().getPort() + "/items"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .method("PUT", HttpRequest.BodyPublishers.noBody())
                    .build();

            CloudFrontOriginHttpClient.OpenResponse response = client.open(
                    request, List.of(), Map.of(), new ByteArrayInputStream(payload), payload.length);
            readAll(response);

            assertEquals(204, response.statusCode());
            assertEquals("PUT", method.get());
            assertEquals("application/json", contentType.get());
            assertEquals(Integer.toString(payload.length), contentLength.get());
            assertArrayEquals(payload, body.get());
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
        X509Certificate certificate = Pem.parseCertificate(generated.certificatePem());
        SSLContext serverContext = serverSslContext(
                certificate, Pem.parsePrivateKey(generated.privateKeyPem()));
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
            CloudFrontOriginHttpClient.OpenResponse response = get(client,
                    request("https://origin.invalid:" + port + "/"));
            readAll(response);

            assertEquals(200, response.statusCode());
            assertEquals("origin.invalid:" + port, hostHeader.get());
            assertEquals("origin.invalid", requestedSni.get());
            assertThrows(IOException.class, () -> get(client, request("https://wrong.invalid:" + port + "/")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamsARequestBodyChunkedWhenItsLengthIsUnknown() throws Exception {
        AtomicReference<String> transferEncoding = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            transferEncoding.set(exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
            body.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        byte[] payload = new byte[100_000];
        new Random(1).nextBytes(payload);
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of("origin.invalid"))) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://origin.invalid:" + server.getAddress().getPort() + "/items"))
                    .timeout(Duration.ofSeconds(5))
                    .method("POST", HttpRequest.BodyPublishers.noBody())
                    .build();

            readAll(client.open(request, List.of(), Map.of(), new ByteArrayInputStream(payload), -1));

            assertEquals("chunked", transferEncoding.get());
            assertArrayEquals(payload, body.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void closingAResponseBeforeItsEndDoesNotReadTheRestOfIt() throws Exception {
        long size = 512L * 1024 * 1024;
        AtomicLong sent = new AtomicLong();
        CountDownLatch done = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] chunk = new byte[64 * 1024];
            exchange.sendResponseHeaders(200, size);
            try (OutputStream out = exchange.getResponseBody()) {
                for (long written = 0; written < size; written += chunk.length) {
                    out.write(chunk);
                    sent.addAndGet(chunk.length);
                }
            } catch (IOException expected) {
                // The client went away mid-body, which is what the test does.
            } finally {
                done.countDown();
            }
        });
        server.start();

        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of("origin.invalid"))) {
            CloudFrontOriginHttpClient.OpenResponse response = get(client,
                    request("http://origin.invalid:" + server.getAddress().getPort() + "/big"));
            assertEquals(size, response.contentLength());
            response.body().readNBytes(1024);
            response.body().close();

            assertTrue(done.await(30, TimeUnit.SECONDS), "the origin is still sending");
            assertTrue(sent.get() < size / 2, "closing read " + (sent.get() >> 20) + " MiB of " + (size >> 20));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aResponseReadToItsEndLeavesItsConnectionForTheNextRequest() throws Exception {
        List<InetSocketAddress> peers = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            peers.add(exchange.getRemoteAddress());
            byte[] body = "origin-ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of("origin.invalid"))) {
            String uri = "http://origin.invalid:" + server.getAddress().getPort() + "/";
            readAll(get(client, request(uri)));
            readAll(get(client, request(uri)));

            assertEquals(2, peers.size());
            assertEquals(peers.get(0), peers.get(1), "the second request opened a new connection");
        } finally {
            server.stop(0);
        }
    }

    private static CloudFrontOriginHttpClient.OpenResponse get(CloudFrontOriginHttpClient client,
                                                               HttpRequest request) throws Exception {
        return client.open(request, List.of(), Map.of(), null, -1);
    }

    private static byte[] readAll(CloudFrontOriginHttpClient.OpenResponse response) throws IOException {
        try (InputStream body = response.body()) {
            return body.readAllBytes();
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
