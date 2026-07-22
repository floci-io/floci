package io.github.hectorvent.floci.services.apigatewayv2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Regression test: HTTP API (v2) JWT authorizers support {@code $request.querystring.*}
 * identity sources, not just {@code $request.header.*} — a template/API call configuring
 * a query-string token source must not 401 a request carrying a valid token there.
 *
 * <p>Tokens here are unsigned (header.payload.signature-shaped strings whose payload is
 * plain base64url JSON) — {@code ApiGatewayExecuteController.parseJwtClaims} doesn't verify
 * a signature, matching this emulator's local-dev scope, so no real signing key is needed
 * to exercise the extraction and claims-matching logic under test.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpApiJwtAuthorizerQuerystringTest {

    private static String httpApiId;
    private static String integrationId;
    private static String routeId;
    private static String authorizerId;

    private static final String ISSUER = "https://issuer.example.com";
    private static final String AUDIENCE = "my-client-id";

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
    void requestWithValidTokenInQueryStringIsAuthorized() {
        // The integration target isn't a real reachable backend, so a successful proxy
        // round-trip isn't asserted here — only that the request got past the authorizer.
        // Before the fix, an invalid/missing token (or a source type extractToken can't read)
        // 401s before ever reaching the integration; getting a 502 (backend unreachable) rather
        // than a 401 proves the querystring token was actually extracted and accepted.
        String token = fakeJwt(ISSUER, AUDIENCE);

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
    void requestWithValidTokenOnlyInHeaderIsUnauthorizedWhenSourceIsQuerystring() {
        // The authorizer's IdentitySource is $request.querystring.token, not a header — a
        // token presented via Authorization must not satisfy an identity source it wasn't
        // configured for.
        String token = fakeJwt(ISSUER, AUDIENCE);

        given()
                .header("Authorization", "Bearer " + token)
                .when().get("/execute-api/" + httpApiId + "/test/hello")
                .then().statusCode(401);
    }

    private static String fakeJwt(String issuer, String audience) {
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url(
                "{\"iss\":\"" + issuer + "\",\"aud\":\"" + audience + "\",\"exp\":9999999999}");
        return header + "." + payload + ".signature";
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
