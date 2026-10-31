package io.github.hectorvent.floci.services.apigatewayv2;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for WebSocket MOCK integration type.
 *
 * MOCK integration does NOT invoke any backend service or Lambda function.
 * MOCK integration with no matching response template returns default 200.
 * MOCK integration on $connect with 200 allows upgrade; non-2xx denies upgrade.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebSocketMockIntegrationTest {

    @TestHTTPResource("/")
    URI baseUri;

    private static String wsApiId;
    private static String mockIntegrationIdDefault;
    private static String mockIntegrationIdDeny;
    private static String connectRouteId;
    private static String defaultRouteId;

    // ──────────────────────────── Setup ────────────────────────────

    @Test
    @Order(1)
    void setupWebSocketApi() {
        // Create a WEBSOCKET API
        wsApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ws-mock-integration-test","protocolType":"WEBSOCKET","routeSelectionExpression":"$request.body.action"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        // Create a stage
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test"}
                        """)
                .when().post("/v2/apis/" + wsApiId + "/stages")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(2)
    void setupMockIntegrations() {
        // Create a MOCK integration with default behavior (no templateSelectionExpression → returns 200)
        mockIntegrationIdDefault = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"MOCK"}
                        """)
                .when().post("/v2/apis/" + wsApiId + "/integrations")
                .then()
                .statusCode(201)
                .body("integrationId", notNullValue())
                .extract().path("integrationId");

        // Create a MOCK integration configured to return 403
        // templateSelectionExpression of "403" causes invokeMock to use 403 as the status code
        mockIntegrationIdDeny = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"MOCK","templateSelectionExpression":"403"}
                        """)
                .when().post("/v2/apis/" + wsApiId + "/integrations")
                .then()
                .statusCode(201)
                .body("integrationId", notNullValue())
                .extract().path("integrationId");
    }

    // ──────────────────────────── Test 1: MOCK integration does not invoke backend ────────────────────────────

    @Test
    @Order(10)
    void mockIntegrationDoesNotInvokeBackend() throws Exception {
        // Create a $default route with the MOCK integration (default 200).
        // If MOCK tried to invoke a Lambda, it would fail because there's no integrationUri.
        // The fact that the connection works and messages don't cause errors proves no backend is invoked.
        defaultRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$default","target":"integrations/%s"}
                        """.formatted(mockIntegrationIdDefault))
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");

        // Connect (no $connect route, so upgrade succeeds directly)
        WebSocket ws = connectWebSocket(wsApiId, "test");
        assertNotNull(ws, "WebSocket connection should succeed");

        // Send a message — it will route to $default which uses MOCK integration.
        // MOCK integration does NOT invoke any Lambda.
        // If it tried to invoke a Lambda, it would fail since there's no integrationUri.
        ws.sendText("{\"action\":\"hello\",\"data\":\"world\"}", true).join();

        // Wait a bit to ensure no errors occur server-side
        Thread.sleep(1000);

        // The connection should still be open (no error frame sent for MOCK integration success)
        // Send another message to verify the connection is still alive
        ws.sendText("{\"action\":\"ping\"}", true).join();
        Thread.sleep(500);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Test 2: MOCK integration on $connect allows upgrade ────────────────────────────

    @Test
    @Order(20)
    void mockIntegrationOnConnectAllowsUpgrade() throws Exception {
        // Remove the $default route from previous test
        if (defaultRouteId != null) {
            given().when().delete("/v2/apis/" + wsApiId + "/routes/" + defaultRouteId)
                    .then().statusCode(204);
            defaultRouteId = null;
        }

        // Create $connect route with MOCK integration that returns 200 (default behavior)
        connectRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$connect","target":"integrations/%s"}
                        """.formatted(mockIntegrationIdDefault))
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");

        // Connection should succeed because MOCK returns 200
        WebSocket ws = connectWebSocket(wsApiId, "test");
        assertNotNull(ws, "WebSocket connection should succeed with MOCK integration returning 200");
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Test 3: MOCK integration on $connect denies upgrade ────────────────────────────

    @Test
    @Order(30)
    void mockIntegrationOnConnectDeniesUpgrade() throws Exception {
        // Update $connect route to use the MOCK integration that returns 403
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"target":"integrations/%s"}
                        """.formatted(mockIntegrationIdDeny))
                .when().patch("/v2/apis/" + wsApiId + "/routes/" + connectRouteId)
                .then()
                .statusCode(200);

        // Connection should fail with 403 because MOCK returns 403 (non-2xx → deny upgrade)
        assertWebSocketConnectionFails(wsApiId, "test", 403);
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(999)
    void cleanup() {
        // Delete routes
        if (connectRouteId != null) {
            given().when().delete("/v2/apis/" + wsApiId + "/routes/" + connectRouteId);
        }
        if (defaultRouteId != null) {
            given().when().delete("/v2/apis/" + wsApiId + "/routes/" + defaultRouteId);
        }

        // Delete API (cascades integrations, routes, stages)
        if (wsApiId != null) {
            given().when().delete("/v2/apis/" + wsApiId);
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private WebSocket connectWebSocket(String apiId, String stageName) throws Exception {
        String wsUrl = baseUri.toString().replaceFirst("^http", "ws") + "ws/" + apiId + "/" + stageName;
        wsUrl = wsUrl.replace("//ws/", "/ws/");

        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<WebSocket> wsFuture = client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        WebSocket.Listener.super.onOpen(webSocket);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        return WebSocket.Listener.super.onText(webSocket, data, last);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        WebSocket.Listener.super.onError(webSocket, error);
                    }
                });

        return wsFuture.get(60, TimeUnit.SECONDS);
    }

    private void assertWebSocketConnectionFails(String apiId, String stageName, int expectedStatus) throws Exception {
        String wsUrl = baseUri.toString().replaceFirst("^http", "ws") + "ws/" + apiId + "/" + stageName;
        wsUrl = wsUrl.replace("//ws/", "/ws/");

        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<WebSocket> wsFuture = client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {});

        try {
            wsFuture.get(60, TimeUnit.SECONDS);
            fail("Expected WebSocket connection to fail with status " + expectedStatus);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            assertNotNull(cause, "Expected a cause for the ExecutionException");
            String message = cause.getMessage() != null ? cause.getMessage() : "";
            if (!message.contains(String.valueOf(expectedStatus))) {
                Throwable inner = cause.getCause();
                if (inner != null && inner.getMessage() != null) {
                    message = inner.getMessage();
                }
            }
            assertTrue(
                    message.contains(String.valueOf(expectedStatus)),
                    "Expected connection failure with status " + expectedStatus + " but got exception: " + cause);
        }
    }
}
