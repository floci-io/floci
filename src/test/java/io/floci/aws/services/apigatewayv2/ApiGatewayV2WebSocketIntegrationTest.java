package io.floci.aws.services.apigatewayv2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2WebSocketIntegrationTest {

    private static String wsApiId;
    private static String wsRouteId;
    private static String httpApiId;
    private static String taggedApiId;

    // ──────────────────────────── WebSocket API Creation ────────────────────────────

    @Test
    @Order(1)
    void createWebSocketApi() {
        wsApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ws-test-api","protocolType":"WEBSOCKET","routeSelectionExpression":"$request.body.action","description":"A test WS API","apiKeySelectionExpression":"$request.header.x-api-key"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .body("name", equalTo("ws-test-api"))
                .body("protocolType", equalTo("WEBSOCKET"))
                .body("routeSelectionExpression", equalTo("$request.body.action"))
                .body("description", equalTo("A test WS API"))
                .body("apiKeySelectionExpression", equalTo("$request.header.x-api-key"))
                .body("apiEndpoint", startsWith("wss://"))
                .extract().path("apiId");
    }

    @Test
    @Order(2)
    void createWebSocketApiMissingRouteSelectionExpression() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ws-no-rse","protocolType":"WEBSOCKET"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(400);
    }

    // ──────────────────────────── GetApi ────────────────────────────

    @Test
    @Order(3)
    void getWebSocketApi() {
        given()
                .when().get("/v2/apis/" + wsApiId)
                .then()
                .statusCode(200)
                .body("apiId", equalTo(wsApiId))
                .body("name", equalTo("ws-test-api"))
                .body("protocolType", equalTo("WEBSOCKET"))
                .body("routeSelectionExpression", equalTo("$request.body.action"))
                .body("description", equalTo("A test WS API"))
                .body("apiKeySelectionExpression", equalTo("$request.header.x-api-key"))
                .body("apiEndpoint", startsWith("wss://"))
                .body("createdDate", notNullValue());
    }

    // ──────────────────────────── GetApis ────────────────────────────

    @Test
    @Order(4)
    void getApisIncludesWebSocket() {
        given()
                .when().get("/v2/apis")
                .then()
                .statusCode(200)
                .body("items.apiId", hasItem(wsApiId))
                .body("items.find { it.apiId == '" + wsApiId + "' }.routeSelectionExpression",
                        equalTo("$request.body.action"));
    }

    // ──────────────────────────── UpdateApi ────────────────────────────

    @Test
    @Order(5)
    void updateWebSocketApi() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ws-updated-api","description":"Updated description"}
                        """)
                .when().patch("/v2/apis/" + wsApiId)
                .then()
                .statusCode(200)
                .body("apiId", equalTo(wsApiId))
                .body("name", equalTo("ws-updated-api"))
                .body("description", equalTo("Updated description"))
                // Non-provided fields should be preserved
                .body("protocolType", equalTo("WEBSOCKET"))
                .body("routeSelectionExpression", equalTo("$request.body.action"))
                .body("apiKeySelectionExpression", equalTo("$request.header.x-api-key"))
                .body("apiEndpoint", startsWith("wss://"));
    }

    @Test
    @Order(6)
    void updateApiNotFound() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ghost"}
                        """)
                .when().patch("/v2/apis/nonexistent999")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── DeleteApi ────────────────────────────

    @Test
    @Order(7)
    void deleteWebSocketApiAndVerify() {
        // Create a temporary API to delete
        String tempApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ws-to-delete","protocolType":"WEBSOCKET","routeSelectionExpression":"$request.body.action"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .extract().path("apiId");

        // Delete it
        given()
                .when().delete("/v2/apis/" + tempApiId)
                .then()
                .statusCode(204);

        // Verify it's gone
        given()
                .when().get("/v2/apis/" + tempApiId)
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── Tags ────────────────────────────

    @Test
    @Order(8)
    void createApiWithTagsAndVerifyInGetApi() {
        taggedApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ws-tagged","protocolType":"WEBSOCKET","routeSelectionExpression":"$request.body.action","tags":{"env":"dev","team":"platform"}}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("tags.env", equalTo("dev"))
                .body("tags.team", equalTo("platform"))
                .extract().path("apiId");

        // GetApi returns tags
        given()
                .when().get("/v2/apis/" + taggedApiId)
                .then()
                .statusCode(200)
                .body("tags.env", equalTo("dev"))
                .body("tags.team", equalTo("platform"));

        // GetApis returns tags
        given()
                .when().get("/v2/apis")
                .then()
                .statusCode(200)
                .body("items.find { it.apiId == '" + taggedApiId + "' }.tags.env", equalTo("dev"));
    }

    @Test
    @Order(9)
    void updateApiTagsReplacement() {
        // Replace tags entirely
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"tags":{"env":"prod","version":"2"}}
                        """)
                .when().patch("/v2/apis/" + taggedApiId)
                .then()
                .statusCode(200)
                .body("tags.env", equalTo("prod"))
                .body("tags.version", equalTo("2"))
                .body("tags.team", nullValue())
                // Non-tag fields preserved
                .body("name", equalTo("ws-tagged"))
                .body("routeSelectionExpression", equalTo("$request.body.action"));

        // Cleanup
        given().when().delete("/v2/apis/" + taggedApiId).then().statusCode(204);
    }

    // ──────────────────────────── CreateRoute with routeResponseSelectionExpression ────────────────────────────

    @Test
    @Order(10)
    void createRouteWithRouteResponseSelectionExpression() {
        wsRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$default","authorizationType":"NONE","routeResponseSelectionExpression":"$default"}
                        """)
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .body("routeId", notNullValue())
                .body("routeKey", equalTo("$default"))
                .body("authorizationType", equalTo("NONE"))
                .body("routeResponseSelectionExpression", equalTo("$default"))
                .extract().path("routeId");
    }

    // ──────────────────────────── WebSocket lifecycle route keys ────────────────────────────

    @Test
    @Order(11)
    void createConnectRoute() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$connect","authorizationType":"NONE","routeResponseSelectionExpression":"$default"}
                        """)
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .body("routeKey", equalTo("$connect"))
                .body("routeResponseSelectionExpression", equalTo("$default"));
    }

    @Test
    @Order(12)
    void createDisconnectRoute() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$disconnect","authorizationType":"NONE"}
                        """)
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .body("routeKey", equalTo("$disconnect"));
    }

    // ──────────────────────────── GetRoute ────────────────────────────

    @Test
    @Order(13)
    void getRouteReturnsRouteResponseSelectionExpression() {
        given()
                .when().get("/v2/apis/" + wsApiId + "/routes/" + wsRouteId)
                .then()
                .statusCode(200)
                .body("routeId", equalTo(wsRouteId))
                .body("routeKey", equalTo("$default"))
                .body("routeResponseSelectionExpression", equalTo("$default"));
    }

    // ──────────────────────────── GetRoutes ────────────────────────────

    @Test
    @Order(14)
    void getRoutesReturnsAllRoutesWithRouteResponseSelectionExpression() {
        given()
                .when().get("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(200)
                .body("items.routeId", hasItem(wsRouteId))
                .body("items.find { it.routeId == '" + wsRouteId + "' }.routeResponseSelectionExpression",
                        equalTo("$default"));
    }

    // ──────────────────────────── UpdateRoute ────────────────────────────

    @Test
    @Order(15)
    void updateRouteMergePatch() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"target":"integrations/int123","routeResponseSelectionExpression":"$default"}
                        """)
                .when().patch("/v2/apis/" + wsApiId + "/routes/" + wsRouteId)
                .then()
                .statusCode(200)
                .body("routeId", equalTo(wsRouteId))
                .body("target", equalTo("integrations/int123"))
                .body("routeResponseSelectionExpression", equalTo("$default"))
                // Non-provided fields preserved
                .body("routeKey", equalTo("$default"))
                .body("authorizationType", equalTo("NONE"));
    }

    @Test
    @Order(16)
    void updateRouteNotFound() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$connect"}
                        """)
                .when().patch("/v2/apis/" + wsApiId + "/routes/nonexistent999")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── DeleteRoute ────────────────────────────

    @Test
    @Order(17)
    void deleteRouteAndVerify() {
        // Create a temporary route to delete
        String tempRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"temp-route","authorizationType":"NONE"}
                        """)
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");

        // Delete it
        given()
                .when().delete("/v2/apis/" + wsApiId + "/routes/" + tempRouteId)
                .then()
                .statusCode(204);

        // Verify it's gone
        given()
                .when().get("/v2/apis/" + wsApiId + "/routes/" + tempRouteId)
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── HTTP API backward compatibility ────────────────────────────

    @Test
    @Order(20)
    void httpApiCrudStillWorks() {
        // Create HTTP API
        httpApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"http-compat-api","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .body("name", equalTo("http-compat-api"))
                .body("protocolType", equalTo("HTTP"))
                .body("apiEndpoint", startsWith("https://"))
                // AWS defaults must be populated even when not provided
                .body("routeSelectionExpression", equalTo("${request.method} ${request.path}"))
                .body("apiKeySelectionExpression", equalTo("$request.header.x-api-key"))
                .extract().path("apiId");

        // Get HTTP API — verify defaults are persisted
        given()
                .when().get("/v2/apis/" + httpApiId)
                .then()
                .statusCode(200)
                .body("apiId", equalTo(httpApiId))
                .body("protocolType", equalTo("HTTP"))
                .body("routeSelectionExpression", equalTo("${request.method} ${request.path}"))
                .body("apiKeySelectionExpression", equalTo("$request.header.x-api-key"))
                .body("createdDate", notNullValue());

        // Create route on HTTP API
        String httpRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET /health","authorizationType":"NONE"}
                        """)
                .when().post("/v2/apis/" + httpApiId + "/routes")
                .then()
                .statusCode(201)
                .body("routeId", notNullValue())
                .body("routeKey", equalTo("GET /health"))
                .extract().path("routeId");

        // Get route on HTTP API
        given()
                .when().get("/v2/apis/" + httpApiId + "/routes/" + httpRouteId)
                .then()
                .statusCode(200)
                .body("routeId", equalTo(httpRouteId))
                .body("routeKey", equalTo("GET /health"));

        // Delete route
        given()
                .when().delete("/v2/apis/" + httpApiId + "/routes/" + httpRouteId)
                .then()
                .statusCode(204);

        // Delete HTTP API
        given()
                .when().delete("/v2/apis/" + httpApiId)
                .then()
                .statusCode(204);

        // Verify deleted
        given()
                .when().get("/v2/apis/" + httpApiId)
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(99)
    void cleanupWebSocketApi() {
        if (wsApiId != null) {
            given()
                    .when().delete("/v2/apis/" + wsApiId)
                    .then()
                    .statusCode(anyOf(equalTo(204), equalTo(404)));
        }
    }
}
