package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test: an HTTP_PROXY integration on a JWT-authorized route maps
 * {@code $context.authorizer.claims.*} into the outgoing backend request from the token the
 * authorizer actually verified — not from a second, independently (and previously unverified)
 * parsed token.
 *
 * <p>Before the fix, {@code dispatchHttpProxyV2} re-extracted a token from the
 * {@code Authorization} header (regardless of the authorizer's configured identity source) and
 * re-parsed its claims without checking the signature. This authorizer's identity source is a
 * query-string parameter, not a header, so a caller presenting a validly-signed token there
 * *and* an unrelated, attacker-controlled {@code Authorization} header would have had claim
 * mappings resolve from the unverified header token instead of the one the authorizer checked.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpProxyJwtClaimsMappingIntegrationTest {

    private static String httpApiId;
    private static String routeId;

    private static HttpServer issuerServer;
    private static HttpServer backendServer;
    private static String issuer;
    private static final String AUDIENCE = "my-client-id";
    private static final String KEY_ID = "test-key-1";

    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final AtomicReference<String> receivedSubHeader = new AtomicReference<>();

    @BeforeAll
    static void startFixtureServers() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        publicKey = (RSAPublicKey) pair.getPublic();

        issuerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + issuerServer.getAddress().getPort();
        issuerServer.createContext("/.well-known/openid-configuration", exchange -> {
            String body = "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer + "/jwks\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        issuerServer.createContext("/jwks", exchange -> {
            String n = base64UrlUnsigned(publicKey.getModulus());
            String e = base64UrlUnsigned(publicKey.getPublicExponent());
            String body = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"" + KEY_ID + "\",\"alg\":\"RS256\","
                    + "\"use\":\"sig\",\"n\":\"" + n + "\",\"e\":\"" + e + "\"}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        issuerServer.start();

        backendServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendServer.createContext("/", exchange -> {
            receivedSubHeader.set(exchange.getRequestHeaders().getFirst("x-user-id"));
            byte[] resp = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        backendServer.start();
    }

    @AfterAll
    static void stopFixtureServers() {
        if (issuerServer != null) issuerServer.stop(0);
        if (backendServer != null) backendServer.stop(0);
    }

    @Test
    @Order(1)
    void setup() {
        httpApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"http-proxy-jwt-claims-mapping-test","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test"}
                        """)
                .when().post("/v2/apis/" + httpApiId + "/stages")
                .then().statusCode(201);

        String backendUrl = "http://127.0.0.1:" + backendServer.getAddress().getPort();
        String integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"HTTP_PROXY","integrationUri":"%s","payloadFormatVersion":"1.0",\
                        "requestParameters":{"append:header.x-user-id":"$context.authorizer.claims.sub"}}
                        """.formatted(backendUrl))
                .when().post("/v2/apis/" + httpApiId + "/integrations")
                .then().statusCode(201)
                .extract().path("integrationId");

        routeId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET /hello","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + httpApiId + "/routes")
                .then().statusCode(201)
                .extract().path("routeId");

        String authorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"jwt-querystring-auth","authorizerType":"JWT",\
                        "identitySource":"$request.querystring.token",\
                        "jwtConfiguration":{"audience":["%s"],"issuer":"%s"}}
                        """.formatted(AUDIENCE, issuer))
                .when().post("/v2/apis/" + httpApiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("authorizerId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"JWT","authorizerId":"%s"}
                        """.formatted(authorizerId))
                .when().patch("/v2/apis/" + httpApiId + "/routes/" + routeId)
                .then().statusCode(200);
    }

    @Test
    @Order(10)
    void claimsMappingUsesTheVerifiedQuerystringTokenNotASpoofedHeaderToken() throws Exception {
        receivedSubHeader.set(null);

        // The authorizer's identitySource is the querystring token, and that's the one that
        // gets verified — this token's claims are what $context.authorizer.claims.sub must
        // resolve to. The Authorization header carries a different, entirely unsigned token
        // naming a different subject; if the backend receives THAT subject, the vulnerability
        // Greptile flagged (unverified header claims leaking into request mappings) is back.
        String verifiedToken = signedJwt(issuer, AUDIENCE, "verified-user", privateKey);
        String spoofedHeaderToken = unsignedJwt("attacker-controlled-user");

        given()
                .header("Authorization", "Bearer " + spoofedHeaderToken)
                .when().get("/execute-api/" + httpApiId + "/test/hello?token=" + verifiedToken)
                .then().statusCode(200);

        assertEquals("verified-user", receivedSubHeader.get());
    }

    @Test
    @Order(20)
    void requestWithNoAuthorizationHeaderStillMapsTheVerifiedQuerystringToken() throws Exception {
        receivedSubHeader.set(null);

        String verifiedToken = signedJwt(issuer, AUDIENCE, "only-querystring-user", privateKey);

        given()
                .when().get("/execute-api/" + httpApiId + "/test/hello?token=" + verifiedToken)
                .then().statusCode(200);

        assertEquals("only-querystring-user", receivedSubHeader.get());
    }

    private static String signedJwt(String issuer, String audience, String subject, RSAPrivateKey signingKey)
            throws Exception {
        ObjectNode header = OBJECT_MAPPER.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", KEY_ID);

        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("iss", issuer);
        payload.put("aud", audience);
        payload.put("sub", subject);
        payload.put("exp", 9999999999L);

        String signingInput = base64Url(header.toString()) + "." + base64Url(payload.toString());
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signingKey);
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        String encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());

        return signingInput + "." + encodedSignature;
    }

    private static String unsignedJwt(String subject) {
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url("{\"sub\":\"" + subject + "\"}");
        return header + "." + payload + ".";
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64UrlUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
