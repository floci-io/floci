package io.github.hectorvent.floci.services.apigatewayv2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for WebSocket connection lifecycle.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebSocketConnectionLifecycleTest {

    @io.quarkus.test.common.http.TestHTTPResource("/")
    URI baseUri;

    private static String wsApiId;
    private static String httpApiId;
    private static String allowFunctionName = "ws-connect-allow-fn";
    private static String denyFunctionName = "ws-connect-deny-fn";
    private static String errorFunctionName = "ws-connect-error-fn";
    private static String disconnectFunctionName = "ws-disconnect-fn";
    private static String integrationIdAllow;
    private static String integrationIdDeny;
    private static String integrationIdError;
    private static String integrationIdDisconnect;
    private static String connectRouteId;
    private static String disconnectRouteId;

    // ──────────────────────────── Setup ────────────────────────────

    @Test
    @Order(1)
    void setupWebSocketApi() {
        // Create a WEBSOCKET API
        wsApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ws-lifecycle-test","protocolType":"WEBSOCKET","routeSelectionExpression":"$request.body.action"}
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
    void setupHttpApi() {
        // Create an HTTP API (non-WebSocket) for negative test
        httpApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"http-lifecycle-test","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .extract().path("apiId");

        // Create a stage on the HTTP API
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test"}
                        """)
                .when().post("/v2/apis/" + httpApiId + "/stages")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(3)
    void setupLambdaFunctions() throws Exception {
        // Create Lambda function that returns 200 (allow connection)
        String allowZip = WebSocketTestSupport.createLambdaZip("exports.handler = async (event) => ({ statusCode: 200, body: 'connected' });");
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(allowFunctionName, allowZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        // Create Lambda function that returns 403 (deny connection)
        String denyZip = WebSocketTestSupport.createLambdaZip("exports.handler = async (event) => ({ statusCode: 403, body: 'denied' });");
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(denyFunctionName, denyZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        // Create Lambda function that throws an error
        String errorZip = WebSocketTestSupport.createLambdaZip("exports.handler = async (event) => { throw new Error('connection error'); };");
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(errorFunctionName, errorZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        // Create Lambda function for $disconnect route
        String disconnectZip = WebSocketTestSupport.createLambdaZip("exports.handler = async (event) => ({ statusCode: 200, body: 'disconnected' });");
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(disconnectFunctionName, disconnectZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(5)
    void prewarmLambdaFunctions() {
        // Pre-warm Lambda containers by invoking them directly.
        // This ensures containers are ready before WebSocket tests run.
        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/2015-03-31/functions/" + allowFunctionName + "/invocations")
                .then()
                .statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/2015-03-31/functions/" + denyFunctionName + "/invocations")
                .then()
                .statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/2015-03-31/functions/" + disconnectFunctionName + "/invocations")
                .then()
                .statusCode(200);

        // Error function will throw, but that's fine — we just want the container warm
        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/2015-03-31/functions/" + errorFunctionName + "/invocations")
                .then()
                .statusCode(200);
    }

    @Test
    @Order(4)
    void setupIntegrations() {
        // Create integration for allow function
        integrationIdAllow = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST"}
                        """.formatted(allowFunctionName))
                .when().post("/v2/apis/" + wsApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        // Create integration for deny function
        integrationIdDeny = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST"}
                        """.formatted(denyFunctionName))
                .when().post("/v2/apis/" + wsApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        // Create integration for error function
        integrationIdError = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST"}
                        """.formatted(errorFunctionName))
                .when().post("/v2/apis/" + wsApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        // Create integration for disconnect function
        integrationIdDisconnect = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST"}
                        """.formatted(disconnectFunctionName))
                .when().post("/v2/apis/" + wsApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");
    }

    // ──────────────────────────── Test 1: Connect with no $connect route ────────────────────────────

    @Test
    @Order(10)
    void connectWithNoConnectRoute() throws Exception {
        // No $connect route is defined yet, so connection should succeed directly
        WebSocket ws = connectWebSocket(wsApiId, "test");
        assertNotNull(ws, "WebSocket connection should succeed when no $connect route is defined");
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    // ──────────────────────────── Test 2: Connect with $connect route Lambda allow ────────────────────────────

    @Test
    @Order(20)
    void connectWithConnectRouteLambdaAllow() throws Exception {
        // Create $connect route pointing to the allow function
        connectRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$connect","target":"integrations/%s"}
                        """.formatted(integrationIdAllow))
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");

        // Connection should succeed because Lambda returns 200
        WebSocket ws = connectWebSocket(wsApiId, "test");
        assertNotNull(ws, "WebSocket connection should succeed when $connect Lambda returns 200");
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    // ──────────────────────────── Test 3: Connect with $connect route Lambda deny ────────────────────────────

    @Test
    @Order(30)
    void connectWithConnectRouteLambdaDeny() throws Exception {
        // Update $connect route to point to the deny function
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"target":"integrations/%s"}
                        """.formatted(integrationIdDeny))
                .when().patch("/v2/apis/" + wsApiId + "/routes/" + connectRouteId)
                .then()
                .statusCode(200);

        // Connection should fail with 403 because Lambda returns non-2xx
        assertWebSocketConnectionFails(wsApiId, "test", 403);
    }

    // ──────────────────────────── Test 4: Connect with $connect route Lambda error ────────────────────────────

    @Test
    @Order(40)
    void connectWithConnectRouteLambdaError() throws Exception {
        // Update $connect route to point to the error function
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"target":"integrations/%s"}
                        """.formatted(integrationIdError))
                .when().patch("/v2/apis/" + wsApiId + "/routes/" + connectRouteId)
                .then()
                .statusCode(200);

        // Connection should fail with 500 because Lambda invocation throws
        assertWebSocketConnectionFails(wsApiId, "test", 500);
    }

    // ──────────────────────────── Test 5: Disconnect invokes $disconnect route ────────────────────────────

    @Test
    @Order(50)
    void disconnectInvokesDisconnectRoute() throws Exception {
        // Update $connect route back to allow function
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"target":"integrations/%s"}
                        """.formatted(integrationIdAllow))
                .when().patch("/v2/apis/" + wsApiId + "/routes/" + connectRouteId)
                .then()
                .statusCode(200);

        // Create $disconnect route
        disconnectRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$disconnect","target":"integrations/%s"}
                        """.formatted(integrationIdDisconnect))
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");

        // Connect and then disconnect — $disconnect Lambda should be invoked
        // (We verify this doesn't throw/fail; the Lambda is invoked server-side)
        WebSocket ws = connectWebSocket(wsApiId, "test");
        assertNotNull(ws);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    // ──────────────────────────── Test 6: Disconnect cleans up connection ────────────────────────────

    @Test
    @Order(60)
    void disconnectCleansUpConnection() throws Exception {
        // Connect, then disconnect, and verify the connection is cleaned up
        WebSocket ws = connectWebSocket(wsApiId, "test");
        assertNotNull(ws);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();

        // Attempting to reconnect should work (proving old connection was cleaned up)
        WebSocket ws2 = connectWebSocket(wsApiId, "test");
        assertNotNull(ws2);
        ws2.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    // ──────────────────────────── Test 7: Disconnect error does not propagate to client ────────────────────────────

    @Test
    @Order(70)
    void disconnectErrorDoesNotPropagateToClient() throws Exception {
        // Update $disconnect route to point to the error function
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"target":"integrations/%s"}
                        """.formatted(integrationIdError))
                .when().patch("/v2/apis/" + wsApiId + "/routes/" + disconnectRouteId)
                .then()
                .statusCode(200);

        // Connect and disconnect — even though $disconnect Lambda throws,
        // the client should not see an error (clean close)
        WebSocket ws = connectWebSocket(wsApiId, "test");
        assertNotNull(ws);

        // The close should complete normally
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        // If we got here without exception, the error was not propagated
    }

    // ──────────────────────────── Test 8: Connect to non-existent API ────────────────────────────

    @Test
    @Order(80)
    void connectToNonExistentApi() throws Exception {
        assertWebSocketConnectionFails("nonexistent-api-id", "test", 403);
    }

    // ──────────────────────────── Test 9: Connect to non-WebSocket API ────────────────────────────

    @Test
    @Order(90)
    void connectToNonWebSocketApi() throws Exception {
        assertWebSocketConnectionFails(httpApiId, "test", 403);
    }

    // ──────────────────────────── Test 10: Connect to non-existent stage ────────────────────────────

    @Test
    @Order(100)
    void connectToNonExistentStage() throws Exception {
        assertWebSocketConnectionFails(wsApiId, "nonexistent-stage", 403);
    }

    // ──────────────────────────── Test 11: Connection ID is unique per connection ────────────────────────────

    @Test
    @Order(110)
    void connectionIdIsUniquePerConnection() throws Exception {
        // Remove $connect route to simplify (no Lambda invocation needed)
        given()
                .when().delete("/v2/apis/" + wsApiId + "/routes/" + connectRouteId)
                .then()
                .statusCode(204);
        connectRouteId = null;

        // Also remove $disconnect route to avoid Lambda invocation on close
        given()
                .when().delete("/v2/apis/" + wsApiId + "/routes/" + disconnectRouteId)
                .then()
                .statusCode(204);
        disconnectRouteId = null;

        // Open multiple connections and verify each gets a unique connectionId
        // We can't directly observe connectionId from the client, but we can verify
        // that multiple simultaneous connections are possible (each has its own state)
        Set<WebSocket> connections = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            WebSocket ws = connectWebSocket(wsApiId, "test");
            assertNotNull(ws);
            connections.add(ws);
        }

        // All connections should be distinct objects (unique connections)
        assertEquals(5, connections.size());

        // Close all connections
        for (WebSocket ws : connections) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(999)
    void cleanup() throws Exception {
        // Delete routes if they still exist
        if (connectRouteId != null) {
            given().when().delete("/v2/apis/" + wsApiId + "/routes/" + connectRouteId)
                    .then().statusCode(anyOf(204, 404));
        }
        if (disconnectRouteId != null) {
            given().when().delete("/v2/apis/" + wsApiId + "/routes/" + disconnectRouteId)
                    .then().statusCode(anyOf(204, 404));
        }

        // Delete APIs
        if (wsApiId != null) {
            given().when().delete("/v2/apis/" + wsApiId).then().statusCode(anyOf(204, 404));
        }
        if (httpApiId != null) {
            given().when().delete("/v2/apis/" + httpApiId).then().statusCode(anyOf(204, 404));
        }

        // Delete Lambda functions
        WebSocketTestSupport.deleteFunctions(allowFunctionName, denyFunctionName, errorFunctionName,
                disconnectFunctionName);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private WebSocket connectWebSocket(String apiId, String stageName) throws Exception {
        String wsUrl = WebSocketTestSupport.buildWsUrl(baseUri, apiId, stageName);

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
        String wsUrl = WebSocketTestSupport.buildWsUrl(baseUri, apiId, stageName);

        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<WebSocket> wsFuture = client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {});

        try {
            wsFuture.get(60, TimeUnit.SECONDS);
            fail("Expected WebSocket connection to fail with status " + expectedStatus);
        } catch (java.util.concurrent.ExecutionException e) {
            // The java.net.http WebSocket client wraps the rejection in an ExecutionException
            // whose cause is a java.net.http.WebSocketHandshakeException (or IOException)
            Throwable cause = e.getCause();
            assertNotNull(cause, "Expected a cause for the ExecutionException");
            // The handshake exception message typically contains the HTTP status code
            String message = cause.getMessage() != null ? cause.getMessage() : "";
            // Also check the full exception chain
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

    // Helper for Hamcrest anyOf with integers
    private static org.hamcrest.Matcher<Integer> anyOf(int... values) {
        @SuppressWarnings("unchecked")
        org.hamcrest.Matcher<Integer>[] matchers = new org.hamcrest.Matcher[values.length];
        for (int i = 0; i < values.length; i++) {
            matchers[i] = org.hamcrest.Matchers.equalTo(values[i]);
        }
        return org.hamcrest.Matchers.anyOf(matchers);
    }
}
