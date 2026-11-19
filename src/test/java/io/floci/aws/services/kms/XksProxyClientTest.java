package io.floci.aws.services.kms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsServer;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.testing.LocalHttpsServer;
import io.floci.aws.testing.TlsTrust;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Path;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XksProxyClientTest {
    private static final String ACCESS_KEY = "ABCDEFGHIJKLMNOPQRST";
    private static final String SECRET_KEY = "A".repeat(43);
    private static final String HEALTHY = """
            {"xksProxyFleetSize":1,"xksProxyVendor":"test-vendor","xksProxyModel":"test-proxy",
             "ekmVendor":"test-ekm","ekmFleetDetails":[{"id":"ekm-1","model":"test-model","healthStatus":"ACTIVE"}]}
            """;

    private static DnsResolver loopbackResolver() {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    @Test
    void allowsPrivateAddressesOnlyForTheExplicitlyConfiguredProxyHost() throws Exception {
        ConcurrentHashMap<String, AtomicInteger> allowedHosts = new ConcurrentHashMap<>();
        allowedHosts.put("proxy.internal", new AtomicInteger(1));
        XksProxyClient.PublicOnlyDnsResolver resolver =
                new XksProxyClient.PublicOnlyDnsResolver(loopbackResolver(), allowedHosts);

        assertEquals(1, resolver.resolve("proxy.internal").length);
        assertThrows(UnknownHostException.class, () -> resolver.resolve("other.internal"));
    }

    @Test
    void rejectsNonPublicEndpointShapesBeforeMakingARequest() {
        assertThrows(XksProxyClient.XksProxyException.class, () -> XksProxyClient.healthUri(
                URI.create("http://proxy.example.com"), "/kms/xks/v1"));
        assertThrows(XksProxyClient.XksProxyException.class, () -> XksProxyClient.healthUri(
                URI.create("https://proxy.example.com:8443"), "/kms/xks/v1"));
        assertThrows(XksProxyClient.XksProxyException.class, () -> XksProxyClient.healthUri(
                URI.create("https://proxy.example.com"), "/prefix/../kms/xks/v1"));
    }

    @Test
    void callsTheStandardHealthEndpointWithSigV4(@TempDir Path tlsDirectory) throws Exception {
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDirectory);
        HttpsServer server = LocalHttpsServer.issuedBy(ca);
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/kms/xks/v1/health", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = HEALTHY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (XksProxyClient client = new XksProxyClient(new ObjectMapper(), loopbackResolver(),
                TlsTrust.trusting(ca.certificate()), true)) {
            client.requireHealthy(URI.create("https://localhost:" + server.getAddress().getPort()),
                    "/kms/xks/v1", ACCESS_KEY, SECRET_KEY, "us-east-1", "ConnectCustomKeyStore");
        } finally {
            server.stop(0);
        }
        assertNotNull(requestBody.get());
        assertTrue(requestBody.get().contains("\"kmsOperation\":\"ConnectCustomKeyStore\""));
        assertNotNull(authorization.get());
        assertTrue(authorization.get().startsWith("AWS4-HMAC-SHA256 Credential=" + ACCESS_KEY + "/"));
        assertTrue(authorization.get().contains("SignedHeaders=host;x-amz-date"));
    }


    @Test
    void boundsSlowDnsResolutionByTheTotalHealthCheckDeadline() {
        DnsResolver slowResolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new UnknownHostException("DNS lookup interrupted");
                }
                return new InetAddress[]{InetAddress.getLoopbackAddress()};
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        long started = System.nanoTime();
        try (XksProxyClient client = new XksProxyClient(new ObjectMapper(), slowResolver, null, true)) {
            AwsException failure = assertThrows(AwsException.class, () -> client.requireHealthy(
                    URI.create("https://slow.example.com:443"), "/kms/xks/v1",
                    ACCESS_KEY, SECRET_KEY, "us-east-1", "ConnectCustomKeyStore"));
            assertEquals("XksProxyUriUnreachableException", failure.getErrorCode());
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1_000);
    }

    @Test
    void boundsAHealthResponseThatTricklesBytes(@TempDir Path tlsDirectory) throws Exception {
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDirectory);
        HttpsServer server = LocalHttpsServer.issuedBy(ca);
        server.createContext("/kms/xks/v1/health", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                Thread.sleep(1_000);
                exchange.getResponseBody().write('}');
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The client may cancel the request when its total deadline expires.
            } finally {
                exchange.close();
            }
        });
        server.start();
        long started = System.nanoTime();
        try (XksProxyClient client = new XksProxyClient(new ObjectMapper(), loopbackResolver(),
                TlsTrust.trusting(ca.certificate()), true)) {
            assertThrows(AwsException.class, () -> client.requireHealthy(
                    URI.create("https://localhost:" + server.getAddress().getPort()),
                    "/kms/xks/v1", ACCESS_KEY, SECRET_KEY, "us-east-1", "ConnectCustomKeyStore"));
        } finally {
            server.stop(0);
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1_000);
    }

    @Test
    void rejectsMalformedHealthResponse(@TempDir Path tlsDirectory) throws Exception {
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDirectory);
        HttpsServer server = LocalHttpsServer.issuedBy(ca);
        server.createContext("/kms/xks/v1/health", exchange -> {
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (XksProxyClient client = new XksProxyClient(new ObjectMapper(), loopbackResolver(),
                TlsTrust.trusting(ca.certificate()), true)) {
            AwsException failure = assertThrows(AwsException.class, () -> client.requireHealthy(
                    URI.create("https://localhost:" + server.getAddress().getPort()),
                    "/kms/xks/v1", ACCESS_KEY, SECRET_KEY, "us-east-1", "ConnectCustomKeyStore"));
            assertEquals("XksProxyInvalidResponseException", failure.getErrorCode());
        } finally {
            server.stop(0);
        }
    }
}
