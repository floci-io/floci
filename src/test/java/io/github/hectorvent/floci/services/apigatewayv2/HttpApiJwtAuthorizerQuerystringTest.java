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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Regression test: HTTP API (v2) JWT authorizers support {@code $request.querystring.*}
 * identity sources, not just {@code $request.header.*} — a template/API call configuring
 * a query-string token source must not 401 a request carrying a valid token there.
 *
 * <p>Tokens here are real RS256-signed JWTs verified against a local fixture server serving
 * {@code /.well-known/openid-configuration} and a JWKS document — {@code JwtSignatureVerifier}
 * checks every JWT authorizer's token against its issuer's real published keys (the same as real
 * API Gateway), so an unsigned or wrongly-signed token is correctly rejected regardless of which
 * identity source carried it.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpApiJwtAuthorizerQuerystringTest {

    private static String httpApiId;
    private static String integrationId;
    private static String routeId;
    private static String authorizerId;

    private static HttpServer issuerServer;
    private static String ISSUER;
    private static final String AUDIENCE = "my-client-id";
    private static final String KEY_ID = "test-key-1";

    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeAll
    static void startIssuerServer() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        publicKey = (RSAPublicKey) pair.getPublic();

        issuerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ISSUER = "http://127.0.0.1:" + issuerServer.getAddress().getPort();

        issuerServer.createContext("/.well-known/openid-configuration", exchange -> {
            String body = "{\"issuer\":\"" + ISSUER + "\",\"jwks_uri\":\"" + ISSUER + "/jwks\"}";
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
    }

    @AfterAll
    static void stopIssuerServer() {
        if (issuerServer != null) {
            issuerServer.stop(0);
        }
    }

    @Test
    @Order(1)
    void setup() {
        httpApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"http-v2-jwt-querystring-test","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test"}
                        """)
                .when().post("/v2/apis/" + httpApiId + "/stages")
                .then()
                .statusCode(201);

        integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"HTTP_PROXY","integrationUri":"https://backend.example.com","payloadFormatVersion":"1.0"}
                        """)
                .when().post("/v2/apis/" + httpApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        routeId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET /hello","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + httpApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");

        authorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"jwt-querystring-auth","authorizerType":"JWT",\
                        "identitySource":"$request.querystring.token",\
                        "jwtConfiguration":{"audience":["%s"],"issuer":"%s"}}
                        """.formatted(AUDIENCE, ISSUER))
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
    void requestWithValidTokenInQueryStringIsAuthorized() throws Exception {
        // The integration target isn't a real reachable backend, so a successful proxy
        // round-trip isn't asserted here — only that the request got past the authorizer.
        // Before the fix, an invalid/missing token (or a source type extractToken can't read)
        // 401s before ever reaching the integration; getting a 502 (backend unreachable) rather
        // than a 401 proves the querystring token was actually extracted, signature-verified,
        // and accepted.
        String token = signedJwt(ISSUER, AUDIENCE);

        given()
                .when().get("/execute-api/" + httpApiId + "/test/hello?token=" + token)
                .then().statusCode(502);
    }

    @Test
    @Order(20)
    void requestWithNoTokenAnywhereIsUnauthorized() {
        given()
                .when().get("/execute-api/" + httpApiId + "/test/hello")
                .then().statusCode(401);
    }

    @Test
    @Order(30)
    void requestWithValidTokenOnlyInHeaderIsUnauthorizedWhenSourceIsQuerystring() throws Exception {
        // The authorizer's IdentitySource is $request.querystring.token, not a header — a
        // token presented via Authorization must not satisfy an identity source it wasn't
        // configured for.
        String token = signedJwt(ISSUER, AUDIENCE);

        given()
                .header("Authorization", "Bearer " + token)
                .when().get("/execute-api/" + httpApiId + "/test/hello")
                .then().statusCode(401);
    }

    @Test
    @Order(40)
    void requestWithForgedSignatureInQueryStringIsUnauthorized() throws Exception {
        // Same kid, same claims, but signed with an unrelated keypair — this must not verify
        // against the issuer's real published key.
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        RSAPrivateKey forgedKey = (RSAPrivateKey) gen.generateKeyPair().getPrivate();
        String token = signedJwt(ISSUER, AUDIENCE, forgedKey);

        given()
                .when().get("/execute-api/" + httpApiId + "/test/hello?token=" + token)
                .then().statusCode(401);
    }

    @Test
    @Order(50)
    void requestWithValidSignatureButExpiredTokenIsUnauthorized() throws Exception {
        // Signature verification (checked first) passes here - this exercises the exp check
        // that runs afterward, which a change to the check ordering could otherwise silently
        // skip without any test catching it.
        String token = signedJwt(ISSUER, AUDIENCE, privateKey, 1L); // epoch second 1: long expired

        given()
                .when().get("/execute-api/" + httpApiId + "/test/hello?token=" + token)
                .then().statusCode(401);
    }

    @Test
    @Order(60)
    void requestWithValidSignatureButWrongAudienceIsUnauthorized() throws Exception {
        // Same: signature is genuinely valid, only the aud claim is wrong, isolating the
        // audience check from the signature check that now runs ahead of it.
        String token = signedJwt(ISSUER, "someone-elses-client-id");

        given()
                .when().get("/execute-api/" + httpApiId + "/test/hello?token=" + token)
                .then().statusCode(401);
    }

    private static String signedJwt(String issuer, String audience) throws Exception {
        return signedJwt(issuer, audience, privateKey, 9999999999L);
    }

    private static String signedJwt(String issuer, String audience, RSAPrivateKey signingKey) throws Exception {
        return signedJwt(issuer, audience, signingKey, 9999999999L);
    }

    private static String signedJwt(String issuer, String audience, RSAPrivateKey signingKey, long exp)
            throws Exception {
        ObjectNode header = OBJECT_MAPPER.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", KEY_ID);

        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("iss", issuer);
        payload.put("aud", audience);
        payload.put("exp", exp);

        String signingInput = base64Url(header.toString()) + "." + base64Url(payload.toString());
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signingKey);
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        String encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());

        return signingInput + "." + encodedSignature;
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
