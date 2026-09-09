package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end coverage for JWT authorizer scope handling on HTTP API (v2) routes,
 * matching behavior verified against real API Gateway (2026-08):
 * <ul>
 *   <li>a route without {@code authorizationScopes} renders {@code jwt.scopes} as null
 *       even when the token carries a {@code scope} claim;</li>
 *   <li>a route with {@code authorizationScopes} surfaces the token's FULL scope list
 *       (not the intersection with the route's scopes) as a JSON array;</li>
 *   <li>a token whose scope claim matches none of the route's scopes is rejected with
 *       403 {@code {"message":"Forbidden"}} (Cognito ID tokens have no scope claim);</li>
 *   <li>array claims like {@code cognito:groups} render in the space-separated bracket
 *       form ({@code "[admin poweruser]"}).</li>
 * </ul>
 *
 * <p>Tokens are real RS256-signed JWTs verified against a local fixture issuer serving
 * {@code /.well-known/openid-configuration} and a JWKS document, the same pattern as
 * {@link HttpApiJwtAuthorizerQuerystringTest} — {@code JwtSignatureVerifier} rejects
 * anything else before the scope checks run.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpApiJwtAuthorizerScopesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BACKEND_FN = "httpv2-jwt-scopes-backend-fn";
    private static final String AUDIENCE = "test-client";
    private static final String KEY_ID = "scopes-test-key-1";

    private static HttpServer issuerServer;
    private static String issuer;
    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey;

    private static String httpApiId;
    private static String integrationId;
    private static String unscopedRouteId;
    private static String scopedRouteId;

    // ──────────────────────────── Fixture issuer ────────────────────────────

    @BeforeAll
    static void startIssuerServer() throws Exception {
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
    }

    @AfterAll
    static void stopIssuerServer() {
        if (issuerServer != null) {
            issuerServer.stop(0);
        }
    }

    // ──────────────────────────── Setup ────────────────────────────

    @Test
    @Order(1)
    void setup() throws Exception {
        httpApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"http-v2-jwt-scopes-test","protocolType":"HTTP"}
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

        String zipBase64 = Base64.getEncoder().encodeToString(zipEntries(Map.of("index.js", """
                exports.handler = async (event) => ({
                    statusCode: 200,
                    body: JSON.stringify({ authorizer: (event.requestContext || {}).authorizer || null })
                });
                """)));
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(BACKEND_FN, zipBase64))
                .when().post("/2015-03-31/functions")
                .then().statusCode(201);
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/2015-03-31/functions/" + BACKEND_FN + "/invocations")
                .then().statusCode(200);

        integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST","payloadFormatVersion":"2.0"}
                        """.formatted(BACKEND_FN))
                .when().post("/v2/apis/" + httpApiId + "/integrations")
                .then().statusCode(201)
                .extract().path("integrationId");

        String authorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "authorizerType":"JWT",
                          "name":"jwt-scopes-auth",
                          "identitySource":["$request.header.Authorization"],
                          "jwtConfiguration":{"issuer":"%s","audience":["%s"]}
                        }
                        """.formatted(issuer, AUDIENCE))
                .when().post("/v2/apis/" + httpApiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("authorizerId");

        unscopedRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET /unscoped","authorizationType":"JWT","authorizerId":"%s","target":"integrations/%s"}
                        """.formatted(authorizerId, integrationId))
                .when().post("/v2/apis/" + httpApiId + "/routes")
                .then().statusCode(201)
                .extract().path("routeId");

        scopedRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET /scoped","authorizationType":"JWT","authorizerId":"%s","authorizationScopes":["read","admin/other"],"target":"integrations/%s"}
                        """.formatted(authorizerId, integrationId))
                .when().post("/v2/apis/" + httpApiId + "/routes")
                .then().statusCode(201)
                .extract().path("routeId");
    }

    // ──────────────────────────── Tests ────────────────────────────

    @Test
    @Order(10)
    void unscopedRouteRendersScopesNullDespiteScopeClaim() throws Exception {
        String body = given()
                .header("Authorization", "Bearer " + signedToken(Map.of(
                        "iss", issuer, "aud", AUDIENCE, "exp", farFuture(),
                        "scope", "read write",
                        "cognito:groups", java.util.List.of("admin", "poweruser"))))
                .when().get("/execute-api/" + httpApiId + "/test/unscoped")
                .then().statusCode(200)
                .extract().asString();

        JsonNode jwt = MAPPER.readTree(body).path("authorizer").path("jwt");
        assertTrue(jwt.get("scopes").isNull(),
                "route without authorizationScopes must render scopes: null even for a scoped token");
        assertEquals("read write", jwt.path("claims").get("scope").asText());
        assertEquals("[admin poweruser]", jwt.path("claims").get("cognito:groups").asText());
    }

    @Test
    @Order(11)
    void scopedRouteSurfacesFullTokenScopeList() throws Exception {
        String body = given()
                .header("Authorization", "Bearer " + signedToken(Map.of(
                        "iss", issuer, "aud", AUDIENCE, "exp", farFuture(),
                        "scope", "read write")))
                .when().get("/execute-api/" + httpApiId + "/test/scoped")
                .then().statusCode(200)
                .extract().asString();

        JsonNode scopes = MAPPER.readTree(body).path("authorizer").path("jwt").get("scopes");
        assertTrue(scopes.isArray());
        assertEquals(2, scopes.size(),
                "the token's full scope list is surfaced, not the intersection with the route's scopes");
        assertEquals("read", scopes.get(0).asText());
        assertEquals("write", scopes.get(1).asText());
    }

    @Test
    @Order(12)
    void scopedRouteRejectsTokenWithoutMatchingScope() throws Exception {
        // No scope claim at all (the Cognito ID-token case)
        given()
                .header("Authorization", "Bearer " + signedToken(Map.of(
                        "iss", issuer, "aud", AUDIENCE, "exp", farFuture())))
                .when().get("/execute-api/" + httpApiId + "/test/scoped")
                .then().statusCode(403)
                .body("message", equalTo("Forbidden"));

        // Scope claim present but matching none of the route's scopes
        given()
                .header("Authorization", "Bearer " + signedToken(Map.of(
                        "iss", issuer, "aud", AUDIENCE, "exp", farFuture(),
                        "scope", "something.else")))
                .when().get("/execute-api/" + httpApiId + "/test/scoped")
                .then().statusCode(403)
                .body("message", equalTo("Forbidden"));
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(999)
    void cleanup() {
        if (unscopedRouteId != null) given().when().delete("/v2/apis/" + httpApiId + "/routes/" + unscopedRouteId);
        if (scopedRouteId != null) given().when().delete("/v2/apis/" + httpApiId + "/routes/" + scopedRouteId);
        if (httpApiId != null) given().when().delete("/v2/apis/" + httpApiId);
        int statusCode = given()
                .when().delete("/2015-03-31/functions/" + BACKEND_FN)
                .then().extract().statusCode();
        assertTrue(statusCode == 204 || statusCode == 404);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static String signedToken(Map<String, Object> claims) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString(
                ("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + KEY_ID + "\"}").getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(claims));
        String signingInput = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + enc.encodeToString(signature.sign());
    }

    private static long farFuture() {
        return (System.currentTimeMillis() / 1000) + 3600;
    }

    private static String base64UrlUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] zipEntries(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }
}
