package io.github.hectorvent.floci.services.appsync.graphql.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.apigatewayv2.JwtSignatureVerifier;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Follows {@code JwtSignatureVerifierTest}'s pattern: a real local {@code com.sun.net.httpserver}
 * discovery/JWKS fixture and genuinely RS256-signed tokens, since {@link OidcAuthValidator} now
 * delegates its signature check to the real {@link JwtSignatureVerifier} rather than a fake.
 */
class OidcAuthValidatorTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String KEY_ID = "test-key-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private String issuer;
    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private OidcAuthValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        publicKey = (RSAPublicKey) pair.getPublic();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/openid-configuration", this::discovery);
        server.createContext("/jwks", this::jwks);
        server.start();
        issuer = "http://127.0.0.1:" + server.getAddress().getPort();

        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        validator = new OidcAuthValidator(new JwtClaimsDecoder(mapper),
                new JwtSignatureVerifier(mapper, httpClient), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void discovery(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String body = "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer + "/jwks\"}";
        respond(exchange, body);
    }

    private void jwks(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String n = base64UrlUnsigned(publicKey.getModulus());
        String e = base64UrlUnsigned(publicKey.getPublicExponent());
        String body = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"" + KEY_ID + "\",\"alg\":\"RS256\",\"use\":\"sig\","
                + "\"n\":\"" + n + "\",\"e\":\"" + e + "\"}]}";
        respond(exchange, body);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void soleModeSkipsIssuerClaimButStillVerifiesSignatureAgainstConfiguredIssuer() throws Exception {
        Map<String, Object> config = config(issuer, "my-client");
        Map<String, Object> claims = baseClaims("https://other-issuer", "my-client");
        Map<String, Object> identity = validator.validate(
                "Bearer " + signedJwt(claims, KEY_ID, privateKey), config, true);
        assertEquals("sub-1", identity.get("sub"));
        assertEquals("https://other-issuer", identity.get("issuer"));
        assertTrue(!identity.containsKey("sourceIp"));
    }

    @Test
    void multiModeEnforcesIssuerClaim() throws Exception {
        Map<String, Object> config = config(issuer, "my-client");
        Map<String, Object> claims = baseClaims("https://other-issuer", "my-client");
        assertThrows(AppSyncTransportException.class,
                () -> validator.validate("Bearer " + signedJwt(claims, KEY_ID, privateKey), config, false));
    }

    @Test
    void iatTtlExceededIs401() throws Exception {
        Map<String, Object> config = config(issuer, "my-client");
        config.put("iatTTL", 60);
        Map<String, Object> claims = baseClaims(issuer, "my-client");
        claims.put("iat", NOW.getEpochSecond() - 120);
        assertThrows(AppSyncTransportException.class,
                () -> validator.validate("Bearer " + signedJwt(claims, KEY_ID, privateKey), config, true));
    }

    @Test
    void clientIdRegexMatchesAzp() throws Exception {
        Map<String, Object> config = config(issuer, "client-.*");
        Map<String, Object> claims = baseClaims(issuer, "other");
        claims.remove("aud");
        claims.put("azp", "client-99");
        Map<String, Object> identity = validator.validate(
                "Bearer " + signedJwt(claims, KEY_ID, privateKey), config, true);
        assertEquals("sub-1", identity.get("sub"));
    }

    @Test
    void clientIdMismatchIs401() throws Exception {
        Map<String, Object> config = config(issuer, "client-1");
        Map<String, Object> claims = baseClaims(issuer, "nope");
        assertThrows(AppSyncTransportException.class,
                () -> validator.validate("Bearer " + signedJwt(claims, KEY_ID, privateKey), config, true));
    }

    @Test
    void unsignedAlgNoneTokenIs401() {
        Map<String, Object> config = config(issuer, "my-client");
        Map<String, Object> claims = baseClaims(issuer, "my-client");
        assertThrows(AppSyncTransportException.class,
                () -> validator.validate("Bearer " + JwtClaimsDecoder.encode(claims, mapper), config, true));
    }

    @Test
    void tokenSignedByAForeignKeyIs401() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        RSAPrivateKey forgedKey = (RSAPrivateKey) gen.generateKeyPair().getPrivate();
        Map<String, Object> config = config(issuer, "my-client");
        Map<String, Object> claims = baseClaims(issuer, "my-client");

        assertThrows(AppSyncTransportException.class,
                () -> validator.validate("Bearer " + signedJwt(claims, KEY_ID, forgedKey), config, true));
    }

    @Test
    void unreachableIssuerFailsClosed() throws Exception {
        Map<String, Object> config = config("http://127.0.0.1:1", "my-client"); // nothing listening
        Map<String, Object> claims = baseClaims("http://127.0.0.1:1", "my-client");

        assertThrows(AppSyncTransportException.class,
                () -> validator.validate("Bearer " + signedJwt(claims, KEY_ID, privateKey), config, true));
    }

    private Map<String, Object> config(String issuer, String clientId) {
        Map<String, Object> config = new HashMap<>();
        config.put("issuer", issuer);
        config.put("clientId", clientId);
        return config;
    }

    private Map<String, Object> baseClaims(String iss, String aud) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "sub-1");
        claims.put("iss", iss);
        claims.put("aud", aud);
        claims.put("iat", NOW.getEpochSecond() - 10);
        claims.put("exp", NOW.getEpochSecond() + 3600);
        return claims;
    }

    private String signedJwt(Map<String, Object> claims, String kid, RSAPrivateKey signingKey)
            throws GeneralSecurityException {
        ObjectNode header = mapper.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", kid);

        ObjectNode payload = mapper.createObjectNode();
        claims.forEach((key, value) -> payload.putPOJO(key, value));

        String signingInput = base64Url(header.toString()) + "." + base64Url(payload.toString());
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signingKey);
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        String encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        return signingInput + "." + encodedSignature;
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64UrlUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
