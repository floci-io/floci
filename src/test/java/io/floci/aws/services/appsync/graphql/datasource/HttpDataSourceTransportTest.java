package io.floci.aws.services.appsync.graphql.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Pem;
import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import io.floci.aws.services.appsync.model.DataSource;
import io.floci.aws.testing.TlsTrust;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpDataSourceTransportTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private HttpDataSourceTransport transport;
    private final CountDownLatch slowResponseReleased = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            byte[] response = mapper.writeValueAsBytes(Map.of(
                    "method", exchange.getRequestMethod(), "uri", exchange.getRequestURI().toString(),
                    "body", new String(body, StandardCharsets.UTF_8),
                    "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                    "cookie", String.valueOf(exchange.getRequestHeaders().getFirst("Cookie"))));
            exchange.getResponseHeaders().set("X-Result", "present");
            exchange.getResponseHeaders().set("Set-Cookie", "session=must-not-persist");
            exchange.sendResponseHeaders(200, response.length);
            try (exchange) {
                exchange.getResponseBody().write(response);
            }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/error", exchange -> {
            byte[] body = "upstream failed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (exchange) {
                exchange.getResponseBody().write(body);
            }
        });
        server.createContext("/large", exchange -> {
            byte[] body = new byte[HttpDataSourceTransport.MAX_BYTES + 1];
            exchange.sendResponseHeaders(200, body.length);
            try (exchange) {
                exchange.getResponseBody().write(body);
            }
        });
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (exchange) {
                exchange.getResponseBody().write('a');
                exchange.getResponseBody().flush();
                try {
                    slowResponseReleased.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.start();
        // Only this transport fixture pins a synthetic public hostname to the test listener.
        DnsResolver resolver = publicResolver();
        transport = new HttpDataSourceTransport(HttpDataSourceTransport.client(resolver));
    }

    private static DnsResolver publicResolver() throws UnknownHostException {
        DnsResolver resolver = mock(DnsResolver.class, CALLS_REAL_METHODS);
        when(resolver.resolve("public.example")).thenReturn(new InetAddress[]{InetAddress.getLoopbackAddress()});
        when(resolver.resolveCanonicalHostname("public.example")).thenReturn("public.example");
        return resolver;
    }

    @AfterEach
    void stopServer() {
        slowResponseReleased.countDown();
        if (transport != null) {
            transport.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsResolverRequestAndReturnsHttpEnvelope() throws Exception {
        Map<String, Object> result = transport.send(request("/echo", "POST", Map.of(
                "headers", Map.of("Authorization", "Bearer external-only", "Content-Type", "text/plain"),
                "query", Map.of("q", "a+b &"), "body", "hello ü")));

        assertEquals(200, result.get("statusCode"));
        assertTrue(((Map<?, ?>) result.get("headers")).keySet().stream()
                .anyMatch(key -> key.toString().equalsIgnoreCase("X-Result")));
        JsonNode body = mapper.readTree((String) result.get("body"));
        assertEquals("POST", body.path("method").asText());
        assertEquals("/echo?q=a%2Bb%20%26", body.path("uri").asText());
        assertEquals("hello ü", body.path("body").asText());
        assertEquals("Bearer external-only", body.path("authorization").asText());
    }

    @Test
    void returnsRedirectWithoutFollowingIt() {
        assertEquals(302, transport.send(request("/redirect", "GET", Map.of())).get("statusCode"));
        assertEquals(0, calls.get());
    }

    @Test
    void returnsNonSuccessStatusToResolverWithoutRetry() {
        Map<String, Object> response = transport.send(request("/error", "GET", Map.of()));
        assertEquals(503, response.get("statusCode"));
        assertEquals("upstream failed", response.get("body"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "text/plain; charset=does-not-exist", "text/plain; charset=bad@@@charset", "not a content type"})
    void malformedResponseContentTypeDoesNotDiscardStatusOrBody(String contentType) {
        server.createContext("/charset", exchange -> {
            byte[] body = "response retained".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(503, body.length);
            try (exchange) {
                exchange.getResponseBody().write(body);
            }
        });
        Map<String, Object> response = transport.send(request("/charset", "GET", Map.of()));
        assertEquals(503, response.get("statusCode"));
        assertEquals("response retained", response.get("body"));
    }

    @Test
    void aSupportedResponseCharsetIsStillRespected() {
        server.createContext("/latin", exchange -> {
            byte[] body = "caf\u00e9".getBytes(StandardCharsets.ISO_8859_1);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=ISO-8859-1");
            exchange.sendResponseHeaders(200, body.length);
            try (exchange) {
                exchange.getResponseBody().write(body);
            }
        });
        assertEquals("caf\u00e9", transport.send(request("/latin", "GET", Map.of())).get("body"));
    }

    @Test
    void doesNotKeepCookiesAcrossResolverCalls() throws Exception {
        transport.send(request("/one", "GET", Map.of()));
        Map<String, Object> next = transport.send(request("/two", "GET", Map.of()));
        assertEquals("null", mapper.readTree((String) next.get("body")).path("cookie").asText());
    }

    @Test
    void refusesOversizedRequestBeforeSending() {
        HttpDataSourceRequest request = new HttpDataSourceRequest(
                URI.create(endpoint() + "/"), "POST", Map.of(), new byte[HttpDataSourceTransport.MAX_BYTES + 1]);
        assertThrows(AwsException.class, () -> transport.send(request));
        assertEquals(0, calls.get());
    }

    @Test
    void refusesOversizedResponse() {
        assertThrows(AwsException.class, () -> transport.send(request("/large", "GET", Map.of())));
    }

    @Test
    void totalDeadlineCancelsResponseBodyRead() throws Exception {
        DnsResolver resolver = publicResolver();
        try (HttpDataSourceTransport shortDeadline = new HttpDataSourceTransport(
                HttpDataSourceTransport.client(resolver), Duration.ofMillis(200))) {
            long started = System.nanoTime();
            assertThrows(AwsException.class, () -> shortDeadline.send(request("/slow", "GET", Map.of())));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
        }
    }

    @Test
    void totalDeadlineAlsoBoundsDnsLookup() throws Exception {
        CountDownLatch releaseDns = new CountDownLatch(1);
        DnsResolver resolver = mock(DnsResolver.class, CALLS_REAL_METHODS);
        when(resolver.resolve("public.example")).thenAnswer(call -> {
            releaseDns.await(5, TimeUnit.SECONDS);
            return new InetAddress[]{InetAddress.getLoopbackAddress()};
        });
        try (HttpDataSourceTransport shortDeadline = new HttpDataSourceTransport(
                HttpDataSourceTransport.client(resolver), Duration.ofMillis(200))) {
            long started = System.nanoTime();
            assertThrows(AwsException.class, () -> shortDeadline.send(request("/", "GET", Map.of())));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
        } finally {
            releaseDns.countDown();
        }
    }

    @Test
    void defaultTransportCanReachALocalMock() {
        try (HttpDataSourceTransport production = new HttpDataSourceTransport()) {
            HttpDataSourceRequest request = new HttpDataSourceRequest(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "GET", Map.of(), new byte[0]);
            assertEquals(200, production.send(request).get("statusCode"));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void dnsValidationAllowsPrivateContainerAddresses() throws Exception {
        DnsResolver delegate = mock(DnsResolver.class);
        InetAddress[] addresses = {InetAddress.getByName("10.0.0.25"), InetAddress.getByName("172.18.0.2"),
                InetAddress.getByName("192.168.1.2"), InetAddress.getByName("fd00::1"),
                InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1")};
        when(delegate.resolve("compose-service")).thenReturn(addresses);
        assertEquals(List.of(addresses), List.of(HttpDataSourceTransport.checkedResolver(delegate).resolve("compose-service")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"169.254.169.254", "169.254.0.1", "fe80::1", "fd00:ec2::254", "::ffff:169.254.169.254",
            "100.100.100.200", "::ffff:100.100.100.200"})
    void dnsValidationRejectsMixedLocalAndMetadataAnswers(String address) throws Exception {
        DnsResolver delegate = mock(DnsResolver.class);
        when(delegate.resolve("mixed.example")).thenReturn(new InetAddress[]{InetAddress.getLoopbackAddress(),
                InetAddress.getByName(address)});
        assertThrows(UnknownHostException.class,
                () -> HttpDataSourceTransport.checkedResolver(delegate).resolve("mixed.example"));
    }

    @Test
    void httpsPreservesCertificateAndHostnameVerification(@TempDir Path tlsDirectory) throws Exception {
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDirectory);
        CertificateGenerator.GeneratedCertificate leaf = ca.issueServerCertificate(
                "public.example", List.of("public.example"), KeyAlgorithm.RSA_2048, null);
        SSLContext serverContext = ca.serverSslContext(Pem.parseCertificate(leaf.certificatePem()),
                Pem.parsePrivateKey(leaf.privateKeyPem()));
        HttpsServer https = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        AtomicInteger accepted = new AtomicInteger();
        https.createContext("/", exchange -> {
            accepted.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        https.start();
        DnsResolver resolver = mock(DnsResolver.class, CALLS_REAL_METHODS);
        for (String host : List.of("public.example", "wrong.example")) {
            when(resolver.resolve(host)).thenReturn(new InetAddress[]{InetAddress.getLoopbackAddress()});
            when(resolver.resolveCanonicalHostname(host)).thenReturn(host);
        }
        URI target = URI.create("https://public.example:" + https.getAddress().getPort() + "/");
        try (HttpDataSourceTransport ordinary = new HttpDataSourceTransport(HttpDataSourceTransport.client(resolver));
             HttpDataSourceTransport trusted = new HttpDataSourceTransport(
                     HttpDataSourceTransport.client(resolver, TlsTrust.trusting(ca.certificate())))) {
            assertThrows(AwsException.class, () -> ordinary.send(
                    new HttpDataSourceRequest(target, "GET", Map.of(), new byte[0])));
            assertEquals(0, accepted.get());
            assertEquals(200, trusted.send(new HttpDataSourceRequest(target, "GET", Map.of(), new byte[0])).get("statusCode"));
            URI wrong = URI.create("https://wrong.example:" + https.getAddress().getPort() + "/");
            assertThrows(AwsException.class, () -> trusted.send(
                    new HttpDataSourceRequest(wrong, "GET", Map.of(), new byte[0])));
            assertEquals(1, accepted.get());
        } finally {
            https.stop(0);
        }
    }

    private HttpDataSourceRequest request(String path, String method, Map<String, Object> params) {
        DataSource source = new DataSource();
        source.setHttpConfig(Map.of("endpoint", endpoint()));
        return HttpDataSourceRequest.parse(source, Map.of("method", method, "resourcePath", path, "params", params), mapper);
    }

    private String endpoint() {
        return "http://public.example:" + server.getAddress().getPort();
    }
}
