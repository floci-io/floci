package io.github.hectorvent.floci.services.cloudfront;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.util.TimeValue;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    @Test
    void retriesAnIdempotentRequestUntilTheAttemptsAreExhausted() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = holdingServer(hits, release);
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of("origin.invalid"))) {
            CloudFrontOriginHttpClient.OriginTimeouts timeouts =
                    new CloudFrontOriginHttpClient.OriginTimeouts(
                            Duration.ofSeconds(1), Duration.ofMillis(200), 3,
                            CloudFrontOriginTimeouts.DEFAULT_RESPONSE_COMPLETION_TIMEOUT,
                            CloudFrontOriginTimeouts.DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT);
            HttpRequest request = request("http://origin.invalid:" + server.getAddress().getPort() + "/");

            long started = System.nanoTime();
            assertThrows(CloudFrontOriginTimeoutException.class, () -> get(client, request, timeouts));
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertEquals(3, hits.get());
            assertTrue(elapsedMillis < 5000,
                    "expected the 200 ms read timeout to end every attempt on an origin that never "
                            + "answers, took " + elapsedMillis + " ms");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void doesNotRetryAnIdempotentRequestWhenOnlyOneAttemptIsConfigured() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = holdingServer(hits, release);
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of("origin.invalid"))) {
            CloudFrontOriginHttpClient.OriginTimeouts timeouts =
                    new CloudFrontOriginHttpClient.OriginTimeouts(
                            Duration.ofSeconds(1), Duration.ofMillis(200), 1,
                            CloudFrontOriginTimeouts.DEFAULT_RESPONSE_COMPLETION_TIMEOUT,
                            CloudFrontOriginTimeouts.DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT);
            HttpRequest request = request("http://origin.invalid:" + server.getAddress().getPort() + "/");

            assertThrows(CloudFrontOriginTimeoutException.class, () -> get(client, request, timeouts));

            assertEquals(1, hits.get());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void doesNotRetryAResponseTimeoutForANonIdempotentRequest() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = holdingServer(hits, release);
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of("origin.invalid"))) {
            CloudFrontOriginHttpClient.OriginTimeouts timeouts =
                    new CloudFrontOriginHttpClient.OriginTimeouts(
                            Duration.ofSeconds(1), Duration.ofMillis(200), 3,
                            CloudFrontOriginTimeouts.DEFAULT_RESPONSE_COMPLETION_TIMEOUT,
                            CloudFrontOriginTimeouts.DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT);
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://origin.invalid:" + server.getAddress().getPort() + "/"))
                    .method("POST", HttpRequest.BodyPublishers.noBody())
                    .build();

            assertThrows(CloudFrontOriginTimeoutException.class, () -> get(client, request, timeouts));

            assertEquals(1, hits.get(), "a POST that hit a response timeout must not be retried");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void retriesAConnectTimeoutForANonIdempotentRequest() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        DnsResolver resolver = countingResolver(resolutions, InetAddress.getByName("192.0.2.1"));
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver, List.of("blackhole.invalid"))) {
            CloudFrontOriginHttpClient.OriginTimeouts timeouts =
                    new CloudFrontOriginHttpClient.OriginTimeouts(
                            Duration.ofMillis(100), Duration.ofSeconds(5), 3,
                            CloudFrontOriginTimeouts.DEFAULT_RESPONSE_COMPLETION_TIMEOUT,
                            CloudFrontOriginTimeouts.DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT);
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://blackhole.invalid:81/"))
                    .method("POST", HttpRequest.BodyPublishers.noBody())
                    .build();

            assertThrows(CloudFrontOriginTimeoutException.class, () -> get(client, request, timeouts));

            assertEquals(3, resolutions.get(),
                    "a POST whose connect times out counts as a connection attempt and is retried");
        }
    }

    @Test
    void retriesARefusedConnectionForANonIdempotentRequest() throws Exception {
        int refusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            refusedPort = socket.getLocalPort();
        }
        AtomicInteger resolutions = new AtomicInteger();
        DnsResolver resolver = countingResolver(resolutions, InetAddress.getByName("127.0.0.1"));
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver, List.of("refused.invalid"))) {
            CloudFrontOriginHttpClient.OriginTimeouts timeouts =
                    new CloudFrontOriginHttpClient.OriginTimeouts(
                            Duration.ofSeconds(1), Duration.ofSeconds(5), 3,
                            CloudFrontOriginTimeouts.DEFAULT_RESPONSE_COMPLETION_TIMEOUT,
                            CloudFrontOriginTimeouts.DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT);
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://refused.invalid:" + refusedPort + "/"))
                    .method("POST", HttpRequest.BodyPublishers.noBody())
                    .build();

            assertThrows(ConnectException.class, () -> get(client, request, timeouts));

            assertEquals(3, resolutions.get(),
                    "a POST whose connection is refused counts as a connection attempt and is retried");
        }
    }

    @Test
    void honorsTheConfiguredReadTimeout() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = holdingServer(hits, release);
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of("origin.invalid"))) {
            CloudFrontOriginHttpClient.OriginTimeouts timeouts =
                    new CloudFrontOriginHttpClient.OriginTimeouts(
                            Duration.ofSeconds(1), Duration.ofMillis(150), 1,
                            CloudFrontOriginTimeouts.DEFAULT_RESPONSE_COMPLETION_TIMEOUT,
                            CloudFrontOriginTimeouts.DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT);
            HttpRequest request = request("http://origin.invalid:" + server.getAddress().getPort() + "/");

            long started = System.nanoTime();
            assertThrows(CloudFrontOriginTimeoutException.class, () -> get(client, request, timeouts));
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertTrue(elapsedMillis < 5000,
                    "expected the configured 150 ms read timeout to end the request, not the 30 s "
                            + "default, took " + elapsedMillis + " ms");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void theCompletionTimeoutBoundsTheWaitForTheResponseHeaders() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = holdingServer(hits, release);
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of("origin.invalid"))) {
            // The read timeout is far longer than the completion timeout, so the completion timeout
            // is the one that has to end the request.
            CloudFrontOriginHttpClient.OriginTimeouts timeouts =
                    new CloudFrontOriginHttpClient.OriginTimeouts(
                            Duration.ofSeconds(1), Duration.ofSeconds(30), 1,
                            Duration.ofMillis(500),
                            CloudFrontOriginTimeouts.DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT);
            HttpRequest request = request("http://origin.invalid:" + server.getAddress().getPort() + "/");

            long started = System.nanoTime();
            assertThrows(CloudFrontOriginTimeoutException.class, () -> get(client, request, timeouts));
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertTrue(elapsedMillis < 5000,
                    "expected the 500 ms completion timeout to end the request, not the 30 s read "
                            + "timeout, took " + elapsedMillis + " ms");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void theCompletionTimeoutBoundsTheRetriesToo() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = holdingServer(hits, release);
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of("origin.invalid"))) {
            CloudFrontOriginHttpClient.OriginTimeouts timeouts =
                    new CloudFrontOriginHttpClient.OriginTimeouts(
                            Duration.ofSeconds(1), Duration.ofSeconds(30), 3,
                            Duration.ofMillis(600),
                            CloudFrontOriginTimeouts.DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT);
            HttpRequest request = request("http://origin.invalid:" + server.getAddress().getPort() + "/");

            long started = System.nanoTime();
            assertThrows(CloudFrontOriginTimeoutException.class, () -> get(client, request, timeouts));
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertTrue(elapsedMillis < 3000,
                    "expected the completion timeout to bound the retries as well, took "
                            + elapsedMillis + " ms");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void theCompletionTimeoutEndsAResponseThatNeverCompletes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                // A body that never ends: it keeps sending, one byte at a time, past the deadline.
                for (int sent = 0; sent < 200; sent++) {
                    out.write('x');
                    out.flush();
                    Thread.sleep(100);
                }
            } catch (IOException | InterruptedException expected) {
                // The client ended the connection, which is what the completion timeout does.
            } finally {
                exchange.close();
            }
        });
        server.start();

        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of("origin.invalid"))) {
            CloudFrontOriginHttpClient.OriginTimeouts timeouts =
                    new CloudFrontOriginHttpClient.OriginTimeouts(
                            Duration.ofSeconds(1), Duration.ofSeconds(30), 1,
                            Duration.ofMillis(700),
                            CloudFrontOriginTimeouts.DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT);
            CloudFrontOriginHttpClient.OpenResponse response =
                    get(client, request("http://origin.invalid:" + server.getAddress().getPort() + "/"),
                            timeouts);

            long started = System.nanoTime();
            assertThrows(CloudFrontOriginTimeoutException.class, () -> readAll(response));
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertTrue(elapsedMillis < 5000,
                    "expected the 700 ms completion timeout to end the body, took " + elapsedMillis
                            + " ms");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsTheOriginConnectionForTheConfiguredKeepAlive() throws Exception {
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
            CloudFrontOriginHttpClient.OriginTimeouts timeouts =
                    new CloudFrontOriginHttpClient.OriginTimeouts(
                            Duration.ofSeconds(1), Duration.ofSeconds(5), 1,
                            CloudFrontOriginTimeouts.DEFAULT_RESPONSE_COMPLETION_TIMEOUT,
                            Duration.ofSeconds(300));
            readAll(get(client, request(uri), timeouts));
            readAll(get(client, request(uri), timeouts));

            assertEquals(2, peers.size());
            assertEquals(peers.get(0), peers.get(1),
                    "the connection was kept for the configured keep-alive and reused");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theKeepAliveStrategyReturnsTheOriginsKeepAlive() {
        CloudFrontOriginHttpClient.KeepAliveStrategy strategy =
                new CloudFrontOriginHttpClient.KeepAliveStrategy();
        // The context the client sends, so the test fails if it stops carrying the keep-alive.
        CloudFrontOriginHttpClient.OriginTimeouts timeouts =
                new CloudFrontOriginHttpClient.OriginTimeouts(
                        Duration.ofSeconds(1), Duration.ofSeconds(30), 1,
                        CloudFrontOriginTimeouts.DEFAULT_RESPONSE_COMPLETION_TIMEOUT,
                        Duration.ofSeconds(2));
        HttpClientContext context = CloudFrontOriginHttpClient.context(timeouts);

        assertEquals(TimeValue.ofSeconds(2),
                strategy.getKeepAliveDuration(new BasicHttpResponse(200), context));

        BasicHttpResponse originAsksForLess = new BasicHttpResponse(200);
        originAsksForLess.addHeader("Keep-Alive", "timeout=1");
        assertEquals(TimeValue.ofSeconds(1),
                strategy.getKeepAliveDuration(originAsksForLess, context),
                "the origin's own Keep-Alive header shortens the setting");

        BasicHttpResponse originAsksForMore = new BasicHttpResponse(200);
        originAsksForMore.addHeader("Keep-Alive", "timeout=30");
        assertEquals(TimeValue.ofSeconds(2),
                strategy.getKeepAliveDuration(originAsksForMore, context),
                "the setting caps what the origin asks for");

        assertEquals(
                TimeValue.ofSeconds(CloudFrontOriginTimeouts.DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT_SECONDS),
                strategy.getKeepAliveDuration(new BasicHttpResponse(200), HttpClientContext.create()),
                "a request with no keep-alive setting uses the default");
    }

    @Test
    void theCompletionTimeoutEndsAStalledBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 1024);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write('x');
                out.flush();
                // The body then stalls: the headers arrived, the rest of the body never does.
                Thread.sleep(30_000);
            } catch (IOException | InterruptedException expected) {
                // The client ended the connection, which is what the completion timeout does.
            } finally {
                exchange.close();
            }
        });
        server.start();

        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of("origin.invalid"))) {
            // The read timeout is far longer than the completion timeout, so the completion timeout
            // is the one that has to end the stalled body.
            CloudFrontOriginHttpClient.OriginTimeouts timeouts =
                    new CloudFrontOriginHttpClient.OriginTimeouts(
                            Duration.ofSeconds(1), Duration.ofSeconds(30), 1,
                            Duration.ofMillis(700),
                            CloudFrontOriginTimeouts.DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT);
            CloudFrontOriginHttpClient.OpenResponse response = get(client,
                    request("http://origin.invalid:" + server.getAddress().getPort() + "/"), timeouts);

            long started = System.nanoTime();
            CloudFrontOriginTimeoutException failure = assertThrows(CloudFrontOriginTimeoutException.class,
                    () -> readAll(response));
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertEquals(CloudFrontServingController.ORIGIN_TIMEOUT_MESSAGE, failure.getMessage());
            assertTrue(elapsedMillis < 5000,
                    "expected the 700 ms completion timeout to end the stalled body, took "
                            + elapsedMillis + " ms");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aSlowViewerDoesNotExpireAResponseTheOriginFinishedInTime() throws Exception {
        byte[] body = "a body the origin answered in full".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        AtomicLong clock = new AtomicLong();
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(InetAddress.getByName("127.0.0.1")), List.of("origin.invalid"), clock::get)) {
            CloudFrontOriginHttpClient.OriginTimeouts timeouts =
                    new CloudFrontOriginHttpClient.OriginTimeouts(
                            Duration.ofSeconds(1), Duration.ofSeconds(30), 1,
                            Duration.ofMillis(500),
                            CloudFrontOriginTimeouts.DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT);
            CloudFrontOriginHttpClient.OpenResponse response = get(client,
                    request("http://origin.invalid:" + server.getAddress().getPort() + "/"), timeouts);

            // The viewer reads the body far slower than the completion timeout allows: a second
            // passes between reads. The origin answered in time, so the response is not expired.
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            try (InputStream origin = response.body()) {
                byte[] chunk = new byte[4];
                int read;
                while ((read = origin.read(chunk)) != -1) {
                    received.write(chunk, 0, read);
                    clock.addAndGet(Duration.ofSeconds(1).toNanos());
                }
            }
            assertArrayEquals(body, received.toByteArray());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theCompletionTimeoutBoundsTheConnectionToTheOrigin() throws Exception {
        // An address that blackholes the connection: the attempt is never answered.
        InetAddress blackhole = InetAddress.getByName("10.255.255.1");
        assumeTrue(connectionHangs(blackhole),
                "the environment answers the connection attempt instead of dropping it");
        try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                resolver(blackhole), List.of("origin.invalid"))) {
            // The connect timeout is far longer than the completion timeout, so the completion
            // timeout is the one that has to end the connection attempt.
            CloudFrontOriginHttpClient.OriginTimeouts timeouts =
                    new CloudFrontOriginHttpClient.OriginTimeouts(
                            Duration.ofSeconds(10), Duration.ofSeconds(30), 1,
                            Duration.ofMillis(500),
                            CloudFrontOriginTimeouts.DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT);
            HttpRequest request = request("http://origin.invalid:80/");

            long started = System.nanoTime();
            assertThrows(CloudFrontOriginTimeoutException.class, () -> get(client, request, timeouts));
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertTrue(elapsedMillis < 5000,
                    "expected the 500 ms completion timeout to end the connection attempt, not the "
                            + "10 s connect timeout, took " + elapsedMillis + " ms");
        }
    }

    @Test
    void theCompletionTimeoutBoundsTheTlsHandshake() throws Exception {
        // An origin that accepts the connection and never answers the handshake.
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (ServerSocket socket = new ServerSocket(0, 1, loopback)) {
            Thread acceptor = new Thread(() -> {
                try (Socket accepted = socket.accept()) {
                    Thread.sleep(30_000);
                } catch (IOException | InterruptedException expected) {
                    // The client gave up on the handshake.
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            try (CloudFrontOriginHttpClient client = new CloudFrontOriginHttpClient(
                    resolver(loopback), List.of("origin.invalid"))) {
                CloudFrontOriginHttpClient.OriginTimeouts timeouts =
                        new CloudFrontOriginHttpClient.OriginTimeouts(
                                Duration.ofSeconds(10), Duration.ofSeconds(30), 1,
                                Duration.ofMillis(500),
                                CloudFrontOriginTimeouts.DEFAULT_ORIGIN_KEEPALIVE_TIMEOUT);
                HttpRequest request = request("https://origin.invalid:" + socket.getLocalPort() + "/");

                long started = System.nanoTime();
                assertThrows(CloudFrontOriginTimeoutException.class, () -> get(client, request, timeouts));
                long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

                assertTrue(elapsedMillis < 5000,
                        "expected the 500 ms completion timeout to end the handshake, not the 10 s "
                                + "connect timeout, took " + elapsedMillis + " ms");
            }
        }
    }

    /**
     * Whether a connection to {@code address} hangs, as a blackholed address does: the attempt has
     * to be dropped rather than answered for the origin connect timeout to be the one under test.
     */
    private static boolean connectionHangs(InetAddress address) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, 80), 500);
            return false;
        } catch (SocketTimeoutException expected) {
            return true;
        } catch (IOException expected) {
            return false;
        }
    }

    private static CloudFrontOriginHttpClient.OpenResponse get(
            CloudFrontOriginHttpClient client, HttpRequest request,
            CloudFrontOriginHttpClient.OriginTimeouts timeouts) throws Exception {
        return client.open(request, List.of(), Map.of(), null, -1, timeouts);
    }

    /**
     * An origin that counts its hits and holds every request open until {@code release} opens, so a
     * client that answers nothing never races a fixed origin delay: only the client's own timeout
     * can end an attempt.
     */
    private static HttpServer holdingServer(AtomicInteger hits, CountDownLatch release) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            try {
                exchange.getRequestBody().readAllBytes();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException expected) {
                // The client gave up on the attempt, which is what the read timeout under test causes.
            }
            try {
                byte[] body = "origin-ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException expected) {
                // The client is gone, so there is nobody left to answer.
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
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

    /** A resolver that answers every lookup with {@code address}, counting the lookups. */
    private static DnsResolver countingResolver(AtomicInteger resolutions, InetAddress address) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) {
                resolutions.incrementAndGet();
                return new InetAddress[] { address };
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
