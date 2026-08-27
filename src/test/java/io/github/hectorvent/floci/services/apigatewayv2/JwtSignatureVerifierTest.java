package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        publicKey = (RSAPublicKey) pair.getPublic();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/.well-known/openid-configuration", exchange -> {
            String issuerUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            String body = "{\"issuer\":\"" + issuerUrl + "\",\"jwks_uri\":\"" + issuerUrl + "/jwks\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        server.createContext("/jwks", exchange -> {
            String n = base64UrlUnsigned(publicKey.getModulus());
            String e = base64UrlUnsigned(publicKey.getPublicExponent());
            String body = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"test-key-1\",\"alg\":\"RS256\",\"use\":\"sig\","
                    + "\"n\":\"" + n + "\",\"e\":\"" + e + "\"}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        server.start();
        issuer = "http://127.0.0.1:" + server.getAddress().getPort();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        verifier = new JwtSignatureVerifier(objectMapper, client);
    }

    @AfterEach
    void tearDown() {
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
        String encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());

        return signingInput + "." + encodedSignature;
    }

    private static String base64Url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64UrlUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
