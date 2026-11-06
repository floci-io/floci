package io.floci.aws.services.apigatewayv2;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for API Gateway v2 WebSocket support via the JSON 1.1 path.
 * Verifies PascalCase key normalization and all WebSocket CRUD operations
 * through the AmazonApiGatewayV2.* X-Amz-Target header.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2WebSocketJson11Test {

    private static final String AMZ_JSON = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AmazonApiGatewayV2.";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260413/us-east-1/apigatewayv2/aws4_request";

    private static String wsApiId;
    private static String wsRouteId;
    private static String taggedApiId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // ──────────────────────────── CreateApi (WebSocket) ────────────────────────────

    @Test
    @Order(1)
    void json11CreateWebSocketApiWithPascalCaseKeys() {
        wsApiId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"Name":"ws-json11-test","ProtocolType":"WEBSOCKET","RouteSelectionExpression":"$request.body.action","Description":"JSON 1.1 WS API","ApiKeySelectionExpression":"$request.header.x-api-key"}
                        """)
                .when().post("/")
                .then()
                .statusCode(201)
                .body("ApiId", notNullValue())
                .body("Name", equalTo("ws-json11-test"))
                .body("ProtocolType", equalTo("WEBSOCKET"))
                .body("RouteSelectionExpression", equalTo("$request.body.action"))
                .body("Description", equalTo("JSON 1.1 WS API"))
                .body("ApiKeySelectionExpression", equalTo("$request.header.x-api-key"))
                .body("ApiEndpoint", startsWith("wss://"))
                .extract().path("ApiId");
    }

    // ──────────────────────────── GetApi ────────────────────────────

    @Test
    @Order(2)
    void json11GetWebSocketApiReturnsPascalCaseFields() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s"}
                        """.formatted(wsApiId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("ApiId", equalTo(wsApiId))
                .body("Name", equalTo("ws-json11-test"))
                .body("ProtocolType", equalTo("WEBSOCKET"))
                .body("RouteSelectionExpression", equalTo("$request.body.action"))
                .body("Description", equalTo("JSON 1.1 WS API"))
                .body("ApiKeySelectionExpression", equalTo("$request.header.x-api-key"))
                .body("ApiEndpoint", startsWith("wss://"))
                .body("CreatedDate", notNullValue());
    }

    // ──────────────────────────── GetApis ────────────────────────────

    @Test
    @Order(3)
    void json11GetApisReturnsPascalCaseWebSocketFields() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetApis")
                .header("Authorization", AUTH_HEADER)
                .body("{}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Items.ApiId", hasItem(wsApiId))
                .body("Items.find { it.ApiId == '" + wsApiId + "' }.RouteSelectionExpression",
                        equalTo("$request.body.action"))
                .body("Items.find { it.ApiId == '" + wsApiId + "' }.ProtocolType",
                        equalTo("WEBSOCKET"));
    }

    // ──────────────────────────── UpdateApi ────────────────────────────

    @Test
    @Order(4)
    void json11UpdateApiViaPascalCaseKeys() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","Name":"ws-json11-updated","Description":"Updated via JSON 1.1"}
                        """.formatted(wsApiId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("ApiId", equalTo(wsApiId))
                .body("Name", equalTo("ws-json11-updated"))
                .body("Description", equalTo("Updated via JSON 1.1"))
                // Non-provided fields should be preserved
                .body("ProtocolType", equalTo("WEBSOCKET"))
                .body("RouteSelectionExpression", equalTo("$request.body.action"))
                .body("ApiKeySelectionExpression", equalTo("$request.header.x-api-key"))
                .body("ApiEndpoint", startsWith("wss://"));
    }

    // ──────────────────────────── CreateRoute ────────────────────────────

    @Test
    @Order(5)
    void json11CreateRouteWithRouteResponseSelectionExpression() {
        wsRouteId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateRoute")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteKey":"$default","AuthorizationType":"NONE","RouteResponseSelectionExpression":"$default"}
                        """.formatted(wsApiId))
                .when().post("/")
                .then()
                .statusCode(201)
                .body("RouteId", notNullValue())
                .body("RouteKey", equalTo("$default"))
                .body("AuthorizationType", equalTo("NONE"))
                .body("RouteResponseSelectionExpression", equalTo("$default"))
                .extract().path("RouteId");
    }

    // ──────────────────────────── GetRoute ────────────────────────────

    @Test
    @Order(6)
    void json11GetRouteReturnsPascalCaseRouteResponseSelectionExpression() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetRoute")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteId":"%s"}
                        """.formatted(wsApiId, wsRouteId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("RouteId", equalTo(wsRouteId))
                .body("RouteKey", equalTo("$default"))
                .body("AuthorizationType", equalTo("NONE"))
                .body("RouteResponseSelectionExpression", equalTo("$default"));
    }

    // ──────────────────────────── GetRoutes ────────────────────────────

    @Test
    @Order(7)
    void json11GetRoutesReturnsPascalCaseRouteResponseSelectionExpression() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetRoutes")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s"}
                        """.formatted(wsApiId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Items.RouteId", hasItem(wsRouteId))
                .body("Items.find { it.RouteId == '" + wsRouteId + "' }.RouteResponseSelectionExpression",
                        equalTo("$default"));
    }

    // ──────────────────────────── UpdateRoute ────────────────────────────

    @Test
    @Order(8)
    void json11UpdateRouteViaPascalCaseKeys() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateRoute")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteId":"%s","Target":"integrations/int456","RouteResponseSelectionExpression":"$default"}
                        """.formatted(wsApiId, wsRouteId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("RouteId", equalTo(wsRouteId))
                .body("Target", equalTo("integrations/int456"))
                .body("RouteResponseSelectionExpression", equalTo("$default"))
                // Non-provided fields preserved
                .body("RouteKey", equalTo("$default"))
                .body("AuthorizationType", equalTo("NONE"));
    }

    // ──────────────────────────── DeleteRoute ────────────────────────────

    @Test
    @Order(9)
    void json11DeleteRouteViaJson11Path() {
        // Create a temporary route to delete
        String tempRouteId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateRoute")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteKey":"$connect","AuthorizationType":"NONE"}
                        """.formatted(wsApiId))
                .when().post("/")
                .then()
                .statusCode(201)
                .extract().path("RouteId");

        // Delete it via JSON 1.1
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteRoute")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteId":"%s"}
                        """.formatted(wsApiId, tempRouteId))
                .when().post("/")
                .then()
                .statusCode(204);

        // Verify it's gone
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetRoute")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteId":"%s"}
                        """.formatted(wsApiId, tempRouteId))
                .when().post("/")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── DeleteApi ────────────────────────────

    @Test
    @Order(10)
    void json11DeleteApiViaJson11Path() {
        // Create a temporary API to delete
        String tempApiId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"Name":"ws-to-delete-json11","ProtocolType":"WEBSOCKET","RouteSelectionExpression":"$request.body.action"}
                        """)
                .when().post("/")
                .then()
                .statusCode(201)
                .extract().path("ApiId");

        // Delete it via JSON 1.1
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s"}
                        """.formatted(tempApiId))
                .when().post("/")
                .then()
                .statusCode(204);

        // Verify it's gone
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s"}
                        """.formatted(tempApiId))
                .when().post("/")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── Tags via JSON 1.1 ────────────────────────────

    @Test
    @Order(12)
    void json11CreateApiWithTagsAndVerifyInGetApi() {
        taggedApiId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"Name":"ws-tagged-json11","ProtocolType":"WEBSOCKET","RouteSelectionExpression":"$request.body.action","Tags":{"env":"staging","team":"backend"}}
                        """)
                .when().post("/")
                .then()
                .statusCode(201)
                .body("Tags.env", equalTo("staging"))
                .body("Tags.team", equalTo("backend"))
                .extract().path("ApiId");

        // GetApi returns tags
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s"}
                        """.formatted(taggedApiId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Tags.env", equalTo("staging"))
                .body("Tags.team", equalTo("backend"));
    }

    @Test
    @Order(13)
    void json11UpdateApiTagsReplacement() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","Tags":{"env":"prod","release":"v3"}}
                        """.formatted(taggedApiId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Tags.env", equalTo("prod"))
                .body("Tags.release", equalTo("v3"))
                .body("Tags.team", nullValue())
                .body("Name", equalTo("ws-tagged-json11"))
                .body("RouteSelectionExpression", equalTo("$request.body.action"));

        // Cleanup
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s"}
                        """.formatted(taggedApiId))
                .when().post("/")
                .then()
                .statusCode(204);
    }

    // ──────────────────────────── PascalCase normalization verification ────────────────────────────

    @Test
    @Order(11)
    void json11PascalCaseNormalizationWorksForAllNewFields() {
        // Create an API with all WebSocket-specific fields using PascalCase
        String verifyApiId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"Name":"ws-pascal-verify","ProtocolType":"WEBSOCKET","RouteSelectionExpression":"$request.body.type","Description":"Pascal test","ApiKeySelectionExpression":"$request.header.key"}
                        """)
                .when().post("/")
                .then()
                .statusCode(201)
                .body("RouteSelectionExpression", equalTo("$request.body.type"))
                .body("Description", equalTo("Pascal test"))
                .body("ApiKeySelectionExpression", equalTo("$request.header.key"))
                .extract().path("ApiId");

        // Create a route with RouteResponseSelectionExpression using PascalCase
        String verifyRouteId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateRoute")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteKey":"$disconnect","AuthorizationType":"NONE","RouteResponseSelectionExpression":"$default"}
                        """.formatted(verifyApiId))
                .when().post("/")
                .then()
                .statusCode(201)
                .body("RouteResponseSelectionExpression", equalTo("$default"))
                .extract().path("RouteId");

        // Update the API with PascalCase keys and verify normalization
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteSelectionExpression":"$request.body.updated","Description":"Updated pascal"}
                        """.formatted(verifyApiId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("RouteSelectionExpression", equalTo("$request.body.updated"))
                .body("Description", equalTo("Updated pascal"))
                // Preserved fields
                .body("ApiKeySelectionExpression", equalTo("$request.header.key"))
                .body("Name", equalTo("ws-pascal-verify"));

        // Update the route with PascalCase keys and verify normalization
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateRoute")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteId":"%s","RouteResponseSelectionExpression":"$custom","Target":"integrations/int789"}
                        """.formatted(verifyApiId, verifyRouteId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("RouteResponseSelectionExpression", equalTo("$custom"))
                .body("Target", equalTo("integrations/int789"))
                // Preserved fields
                .body("RouteKey", equalTo("$disconnect"))
                .body("AuthorizationType", equalTo("NONE"));

        // Cleanup
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s"}
                        """.formatted(verifyApiId))
                .when().post("/")
                .then()
                .statusCode(204);
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(99)
    void cleanup() {
        if (wsApiId != null) {
            given()
                    .contentType(AMZ_JSON)
                    .header("X-Amz-Target", TARGET_PREFIX + "DeleteApi")
                    .header("Authorization", AUTH_HEADER)
                    .body("""
                            {"ApiId":"%s"}
                            """.formatted(wsApiId))
                    .when().post("/")
                    .then()
                    .statusCode(anyOf(equalTo(204), equalTo(404)));
        }
    }
}
