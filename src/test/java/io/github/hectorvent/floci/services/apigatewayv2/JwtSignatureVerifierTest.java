package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.hectorvent.floci.config.ContainerCaBundle;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.config.TlsConfigSource;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link JwtSignatureVerifier} against a real local OIDC discovery + JWKS server
 * (following the {@code HttpProxyInvokerTest} pattern of a real {@code com.sun.net.httpserver}
 * backend rather than a mock), and a real RS256-signed token - the same shape a real IdP's would
 * take, since this class exists specifically to check tokens against a live issuer's real keys.
 */
class JwtSignatureVerifierTest {

    private HttpServer server;
    private String issuer;
    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private JwtSignatureVerifier verifier;
    private JwtSignatureVerifier strictVerifier;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        publicKey = (RSAPublicKey) pair.getPublic();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/.well-known/openid-configuration", discovery(issuer));
        server.createContext("/jwks", this::serveJwks);
        server.start();

        verifier = new JwtSignatureVerifier(objectMapper, SystemDefaultDnsResolver.INSTANCE, true);
        strictVerifier = new JwtSignatureVerifier(objectMapper, SystemDefaultDnsResolver.INSTANCE, false);
    }

    @BeforeEach
    @AfterEach
    void forgetBootstrapTlsDir() {
        // TlsConfigSource.resolvedTlsDir is static: a TLS-on bootstrap run by another test class
        // in this JVM would otherwise steer the bundle lookup at that test's directory.
        System.setProperty("floci.tls.enabled", "false");
        new TlsConfigSource();
        System.clearProperty("floci.tls.enabled");
    }

    @AfterEach
    void tearDown() {
        verifier.close();
        strictVerifier.close();
        server.stop(0);
    }

    @Test
    void verifiesATokenSignedWithTheMatchingKey() throws Exception {
        String token = signToken("test-key-1", privateKey);
        verifier.verify(token, issuer); // does not throw
    }

    @Test
    void rejectsATokenSignedWithADifferentKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        RSAPrivateKey forgedKey = (RSAPrivateKey) gen.generateKeyPair().getPrivate();

        // kid still names the real key, but the signature was produced by an unrelated keypair -
        // this is exactly what a forged token looks like on the wire.
        String token = signToken("test-key-1", forgedKey);

        assertThrows(JwtSignatureVerifier.JwtVerificationException.class,
                () -> verifier.verify(token, issuer));
    }

    @Test
    void rejectsATokenWithNoMatchingKid() throws Exception {
        String token = signToken("unknown-kid", privateKey);

        assertThrows(JwtSignatureVerifier.JwtVerificationException.class,
                () -> verifier.verify(token, issuer));
    }

    @Test
    void rejectsAnUnsignedAlgNoneToken() {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"user1\"}");
        String token = header + "." + payload + ".";

        JwtSignatureVerifier.JwtVerificationException ex = assertThrows(
                JwtSignatureVerifier.JwtVerificationException.class,
                () -> verifier.verify(token, issuer));
        assertTrue(ex.getMessage().toLowerCase().contains("alg") || ex.getMessage().toLowerCase().contains("none"));
    }

    @Test
    void rejectsWhenIssuerHasNoDiscoveryDocument() throws Exception {
        String token = signToken("test-key-1", privateKey);

        assertThrows(JwtSignatureVerifier.JwtVerificationException.class,
                () -> verifier.verify(token, "http://127.0.0.1:1")); // nothing listening
    }

    @Test
    void rejectsWhenIssuerIsMissing() throws Exception {
        String token = signToken("test-key-1", privateKey);

        assertThrows(JwtSignatureVerifier.JwtVerificationException.class,
                () -> verifier.verify(token, null));
    }

    @Test
    void rejectsNonPublicIssuerAddressesWithoutExplicitOptIn() throws Exception {
        String token = signToken("test-key-1", privateKey);

        assertThrows(JwtSignatureVerifier.JwtVerificationException.class,
                () -> strictVerifier.verify(token, issuer));
        assertThrows(JwtSignatureVerifier.JwtVerificationException.class,
                () -> strictVerifier.verify(token, "https://127.0.0.1:443"));
        assertThrows(JwtSignatureVerifier.JwtVerificationException.class,
                () -> strictVerifier.verify(token, "https://10.0.0.1"));
        assertThrows(JwtSignatureVerifier.JwtVerificationException.class,
                () -> strictVerifier.verify(token, "https://169.254.169.254"));
    }

    @Test
    void privateNetworkOptInDoesNotAllowPublicPlaintextIssuer() throws Exception {
        String token = signToken("test-key-1", privateKey);

        JwtSignatureVerifier.JwtVerificationException exception = assertThrows(
                JwtSignatureVerifier.JwtVerificationException.class,
                () -> verifier.verify(token, "http://example.invalid"));

        assertTrue(exception.getMessage().contains("must use HTTPS"));
    }

    @Test
    void rejectsMalformedIssuerScheme() throws Exception {
        String token = signToken("test-key-1", privateKey);

        JwtSignatureVerifier.JwtVerificationException exception = assertThrows(
                JwtSignatureVerifier.JwtVerificationException.class,
                () -> strictVerifier.verify(token, "ftp://issuer.example.com"));

        assertTrue(exception.getMessage().contains("must use HTTPS"));
    }

    @Test
    void rejectsPrivateJwksTargetReturnedByDiscovery() throws Exception {
        String token = signToken("test-key-1", privateKey);
        try (JwtSignatureVerifier privateJwksVerifier = new JwtSignatureVerifier(
                objectMapper, SystemDefaultDnsResolver.INSTANCE, false) {
            @Override
            String fetchJwksUri(String ignoredIssuer) {
                return "https://169.254.169.254/latest/meta-data";
            }
        }) {
            assertThrows(JwtSignatureVerifier.JwtVerificationException.class,
                    () -> privateJwksVerifier.verify(token, "https://issuer.example.com"));
        }
    }

    @Test
    void rejectsMalformedJwksSchemeReturnedByDiscovery() throws Exception {
        String token = signToken("test-key-1", privateKey);
        try (JwtSignatureVerifier malformedJwksVerifier = new JwtSignatureVerifier(
                objectMapper, SystemDefaultDnsResolver.INSTANCE, false) {
            @Override
            String fetchJwksUri(String ignoredIssuer) {
                return "ftp://issuer.example.com/jwks.json";
            }
        }) {
            assertThrows(JwtSignatureVerifier.JwtVerificationException.class,
                    () -> malformedJwksVerifier.verify(token, "https://issuer.example.com"));
        }
    }

    @Test
    void validatesResolvedAddressesAtTheConnectionBoundary() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        DnsResolver changingResolver = dnsResolver(host -> resolutions.incrementAndGet() == 1
                ? new InetAddress[]{InetAddress.ofLiteral("8.8.8.8")}
                : new InetAddress[]{InetAddress.ofLiteral("127.0.0.1")});
        JwtSignatureVerifier.JwtDnsResolver resolver =
                new JwtSignatureVerifier.JwtDnsResolver(changingResolver, false);

        assertEquals("8.8.8.8", resolver.resolve("issuer.example.com")[0].getHostAddress());
        assertThrows(UnknownHostException.class, () -> resolver.resolve("issuer.example.com"));
        assertEquals(2, resolutions.get());
    }

    @Test
    void cachedKeyVerificationDoesNotResolveDnsAgain() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger resolutions = new AtomicInteger();
        DnsResolver countingResolver = dnsResolver(host -> {
            resolutions.incrementAndGet();
            return new InetAddress[]{InetAddress.ofLiteral("127.0.0.1")};
        });
        try (JwtSignatureVerifier cachingVerifier = new JwtSignatureVerifier(
                objectMapper, countingResolver, false) {
            @Override
            Map<String, RSAPublicKey> fetchJwks(String ignoredIssuer) {
                fetches.incrementAndGet();
                return Map.of("test-key-1", publicKey);
            }
        }) {
            String token = signToken("test-key-1", privateKey);

            assertDoesNotThrow(() -> cachingVerifier.verify(token, "https://issuer.example.com"));
            assertDoesNotThrow(() -> cachingVerifier.verify(token, "https://issuer.example.com"));

            assertEquals(1, fetches.get());
            assertEquals(0, resolutions.get());
        }
    }

    private static DnsResolver dnsResolver(Function<String, InetAddress[]> lookup) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) {
                return lookup.apply(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    /**
     * With TLS on, the verifier Floci injects trusts the CA bundle it writes for its containers, so
     * an issuer served with a certificate of Floci's CA, as a Floci Cognito pool's is, verifies,
     * where the JVM's roots alone refuse it. Goes through the configuration and the bundle writer,
     * as production does.
     */
    @Test
    void trustsAnIssuerCertificateFromTheCaBundleFlociWrites(@TempDir Path dir) throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        Path tlsDir = dir.resolve("tls");
        FlociCertificateAuthority authority = FlociCertificateAuthority.loadOrCreate(tlsDir);
        ContainerCaBundle.write(tlsDir, authority.certificatePath());
        CertificateGenerator.GeneratedCertificate issued = authority.issueServerCertificate(
                "localhost", List.of("localhost"), KeyAlgorithm.RSA_2048, null);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(true);
        when(config.storage().persistentPath()).thenReturn(dir.toString());
        when(config.security().allowPrivateJwtTargets()).thenReturn(true);

        HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(authority.serverSslContext(
                Pem.parseCertificate(issued.certificatePem()), Pem.parsePrivateKey(issued.privateKeyPem()))));
        String httpsIssuer = "https://localhost:" + httpsServer.getAddress().getPort();
        httpsServer.createContext("/.well-known/openid-configuration", discovery(httpsIssuer));
        httpsServer.createContext("/jwks", this::serveJwks);
        httpsServer.start();
        try (JwtSignatureVerifier trustingVerifier = new JwtSignatureVerifier(objectMapper, config)) {
            String token = signToken("test-key-1", privateKey);

            assertThrows(JwtSignatureVerifier.JwtVerificationException.class,
                    () -> verifier.verify(token, httpsIssuer));
            assertDoesNotThrow(() -> trustingVerifier.verify(token, httpsIssuer));
        } finally {
            httpsServer.stop(0);
        }
    }

    @Test
    void doesNotFollowDiscoveryRedirects() throws Exception {
        AtomicInteger redirectedRequests = new AtomicInteger();
        HttpServer redirectServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirectServer.createContext("/.well-known/openid-configuration", exchange -> {
            exchange.getResponseHeaders().add("Location", "/discovery");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        redirectServer.createContext("/discovery", exchange -> {
            redirectedRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        redirectServer.start();
        try {
            String redirectIssuer = "http://127.0.0.1:" + redirectServer.getAddress().getPort();
            String token = signToken("test-key-1", privateKey);

            assertThrows(JwtSignatureVerifier.JwtVerificationException.class,
                    () -> verifier.verify(token, redirectIssuer));
            assertEquals(0, redirectedRequests.get());
        } finally {
            redirectServer.stop(0);
        }
    }

    private static HttpHandler discovery(String issuerUrl) {
        return exchange -> respond(exchange,
                "{\"issuer\":\"" + issuerUrl + "\",\"jwks_uri\":\"" + issuerUrl + "/jwks\"}");
    }

    private void serveJwks(HttpExchange exchange) throws IOException {
        String n = base64UrlUnsigned(publicKey.getModulus());
        String e = base64UrlUnsigned(publicKey.getPublicExponent());
        respond(exchange, "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"test-key-1\",\"alg\":\"RS256\",\"use\":\"sig\","
                + "\"n\":\"" + n + "\",\"e\":\"" + e + "\"}]}");
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String signToken(String kid, RSAPrivateKey signingKey) throws GeneralSecurityException {
        ObjectNode header = objectMapper.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", kid);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sub", "user1");
        payload.put("iss", issuer);
        payload.put("exp", System.currentTimeMillis() / 1000 + 3600);

        String signingInput = base64Url(header.toString()) + "." + base64Url(payload.toString());
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signingKey);
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        String encodedSignature = Base64Url.encode(signature.sign());

        return signingInput + "." + encodedSignature;
    }

    private static String base64Url(String s) {
        return Base64Url.encode(s);
    }

    private static String base64UrlUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64Url.encode(bytes);
    }
}
