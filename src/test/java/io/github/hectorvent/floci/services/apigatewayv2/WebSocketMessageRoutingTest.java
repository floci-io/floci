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
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for WebSocket message routing.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebSocketMessageRoutingTest {

    @TestHTTPResource("/")
    URI baseUri;

    // API with $request.body.action (default expression)
    private static String wsApiId;
    // API with $request.body.type (custom expression)
    private static String wsApiTypeId;

    private static String sendMessageFnName = "ws-route-sendmsg-fn";
    private static String defaultFnName = "ws-route-default-fn";

    private static String integrationIdSendMessage;
    private static String integrationIdDefault;

    private static String sendMessageRouteId;
    private static String defaultRouteId;
    private static String typeRouteId;
    private static String typeDefaultRouteId;

    // ──────────────────────────── Setup ────────────────────────────

    @Test
    @Order(1)
    void setupApis() {
        // Create a WEBSOCKET API with routeSelectionExpression: $request.body.action
        wsApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ws-routing-test","protocolType":"WEBSOCKET","routeSelectionExpression":"$request.body.action"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        // Create a stage for the action-based API
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test"}
                        """)
                .when().post("/v2/apis/" + wsApiId + "/stages")
                .then()
                .statusCode(201);

        // Create a WEBSOCKET API with routeSelectionExpression: $request.body.type
        wsApiTypeId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ws-routing-type-test","protocolType":"WEBSOCKET","routeSelectionExpression":"$request.body.type"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        // Create a stage for the type-based API
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test"}
                        """)
                .when().post("/v2/apis/" + wsApiTypeId + "/stages")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(2)
    void setupLambdaFunctions() throws Exception {
        // Lambda for sendMessage route — returns a fixed identifier "sendMessage-handler"
        String sendMsgZip = WebSocketTestSupport.createLambdaZip(
                "exports.handler = async (event) => ({ statusCode: 200, body: JSON.stringify({handler:'sendMessage-handler', routeKey: (event.requestContext || {}).routeKey || 'none'}) });");
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(sendMessageFnName, sendMsgZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        // Lambda for $default route — returns a fixed identifier "default-handler"
        String defaultZip = WebSocketTestSupport.createLambdaZip(
                "exports.handler = async (event) => ({ statusCode: 200, body: JSON.stringify({handler:'default-handler', routeKey: (event.requestContext || {}).routeKey || 'none'}) });");
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(defaultFnName, defaultZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(3)
    void prewarmLambdaFunctions() {
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/2015-03-31/functions/" + sendMessageFnName + "/invocations")
                .then().statusCode(200);
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/2015-03-31/functions/" + defaultFnName + "/invocations")
                .then().statusCode(200);
    }

    @Test
    @Order(4)
    void setupIntegrationsAndRoutes() {
        // Integration for sendMessage function
        integrationIdSendMessage = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST"}
                        """.formatted(sendMessageFnName))
                .when().post("/v2/apis/" + wsApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        // Integration for default function
        integrationIdDefault = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST"}
                        """.formatted(defaultFnName))
                .when().post("/v2/apis/" + wsApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        // Create "sendMessage" route with routeResponseSelectionExpression so we get a response back
        sendMessageRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"sendMessage","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                        """.formatted(integrationIdSendMessage))
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");

        // Create "$default" route with routeResponseSelectionExpression
        defaultRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$default","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                        """.formatted(integrationIdDefault))
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");

        // Setup for the type-based API — reuse the same Lambda functions
        String typeIntegrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST"}
                        """.formatted(sendMessageFnName))
                .when().post("/v2/apis/" + wsApiTypeId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        // Create "chat" route on type-based API with routeResponseSelectionExpression
        typeRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"chat","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                        """.formatted(typeIntegrationId))
                .when().post("/v2/apis/" + wsApiTypeId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");

        // Create "$default" route on type-based API (for fallback tests)
        String typeDefaultIntegrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST"}
                        """.formatted(defaultFnName))
                .when().post("/v2/apis/" + wsApiTypeId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        typeDefaultRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$default","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                        """.formatted(typeDefaultIntegrationId))
                .when().post("/v2/apis/" + wsApiTypeId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");
    }

    // ──────────────────────────── Test 1: Route by action field ────────────────────────────

    @Test
    @Order(10)
    void routeByActionField() throws Exception {
        // Message with {"action":"sendMessage"} routes to the sendMessage route
        WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
        WebSocket ws = connectWebSocketWithListener(wsApiId, "test", capture);
        assertNotNull(ws);

        // Send a message with action field
        ws.sendText("{\"action\":\"sendMessage\",\"data\":\"hello\"}", true).join();

        // Wait for response
        String response = capture.getResponse(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive a response from the sendMessage route");
        assertTrue(response.contains("sendMessage-handler"),
                "Response should indicate the sendMessage route handled it, got: " + response);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Test 2: Route to $default when no match ────────────────────────────

    @Test
    @Order(20)
    void routeToDefaultWhenNoMatch() throws Exception {
        // Message with unmatched action routes to $default
        WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
        WebSocket ws = connectWebSocketWithListener(wsApiId, "test", capture);
        assertNotNull(ws);

        // Send a message with an action that doesn't match any route
        ws.sendText("{\"action\":\"unknownAction\",\"data\":\"test\"}", true).join();

        // Wait for response from $default route
        String response = capture.getResponse(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive a response from the $default route");
        assertTrue(response.contains("default-handler"),
                "Response should indicate the $default route handled it, got: " + response);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Test 3: Error frame when no match and no $default ────────────────────────────

    @Test
    @Order(30)
    void errorFrameWhenNoMatchAndNoDefault() throws Exception {
        // Message with unmatched action and no $default gets error frame
        // Remove the $default route temporarily
        given().when().delete("/v2/apis/" + wsApiId + "/routes/" + defaultRouteId)
                .then().statusCode(204);

        try {
            WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
            WebSocket ws = connectWebSocketWithListener(wsApiId, "test", capture);
            assertNotNull(ws);

            // Send a message with an action that doesn't match any route
            ws.sendText("{\"action\":\"noSuchRoute\",\"data\":\"test\"}", true).join();

            // Should receive an error frame
            String response = capture.getResponse(15, TimeUnit.SECONDS);
            assertNotNull(response, "Should receive an error frame");
            assertTrue(response.contains("No route found") || response.contains("no route"),
                    "Error frame should indicate no route found, got: " + response);

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            Thread.sleep(500);
        } finally {
            // Re-create the $default route
            defaultRouteId = given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {"routeKey":"$default","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                            """.formatted(integrationIdDefault))
                    .when().post("/v2/apis/" + wsApiId + "/routes")
                    .then()
                    .statusCode(201)
                    .extract().path("routeId");
        }
    }

    // ──────────────────────────── Test 4: Non-JSON message routes to $default ────────────────────────────

    @Test
    @Order(40)
    void nonJsonMessageRoutesToDefault() throws Exception {
        // Non-JSON message routes to $default
        WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
        WebSocket ws = connectWebSocketWithListener(wsApiId, "test", capture);
        assertNotNull(ws);

        // Send a non-JSON message
        ws.sendText("this is not json", true).join();

        // Should route to $default and get a response
        String response = capture.getResponse(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive a response from the $default route for non-JSON message");
        assertTrue(response.contains("default-handler"),
                "Response should indicate the $default route handled it, got: " + response);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Test 5: Error frame for non-JSON with no $default ────────────────────────────

    @Test
    @Order(50)
    void errorFrameForNonJsonWithNoDefault() throws Exception {
        // Non-JSON message with no $default gets error frame
        // Remove the $default route temporarily
        given().when().delete("/v2/apis/" + wsApiId + "/routes/" + defaultRouteId)
                .then().statusCode(204);

        try {
            WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
            WebSocket ws = connectWebSocketWithListener(wsApiId, "test", capture);
            assertNotNull(ws);

            // Send a non-JSON message
            ws.sendText("not json at all", true).join();

            // Should receive an error frame
            String response = capture.getResponse(15, TimeUnit.SECONDS);
            assertNotNull(response, "Should receive an error frame for non-JSON with no $default");
            assertTrue(response.contains("Could not route message") || response.contains("could not route"),
                    "Error frame should indicate message could not be routed, got: " + response);

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            Thread.sleep(500);
        } finally {
            // Re-create the $default route
            defaultRouteId = given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {"routeKey":"$default","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                            """.formatted(integrationIdDefault))
                    .when().post("/v2/apis/" + wsApiId + "/routes")
                    .then()
                    .statusCode(201)
                    .extract().path("routeId");
        }
    }

    // ──────────────────────────── Test 6: Route selection expression field extraction ────────────────────────────

    @Test
    @Order(60)
    void routeSelectionExpressionFieldExtraction() throws Exception {
        // Custom $request.body.type expression extracts the type field
        WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
        WebSocket ws = connectWebSocketWithListener(wsApiTypeId, "test", capture);
        assertNotNull(ws);

        // Send a message with "type" field matching the "chat" route
        ws.sendText("{\"type\":\"chat\",\"message\":\"hello\"}", true).join();

        // Should route to the "chat" route
        String response = capture.getResponse(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive a response from the chat route");
        assertTrue(response.contains("sendMessage-handler"),
                "Response should indicate the chat route's Lambda handled it, got: " + response);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Test 7: Non-string field value converted to string ────────────────────────────

    @Test
    @Order(70)
    void nonStringFieldValueConvertedToString() throws Exception {
        // Numeric field value is converted to string for route matching
        // Create a route with a numeric key on the action-based API
        String numericRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"42","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                        """.formatted(integrationIdSendMessage))
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");

        try {
            WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
            WebSocket ws = connectWebSocketWithListener(wsApiId, "test", capture);
            assertNotNull(ws);

            // Send a message with a numeric action value
            ws.sendText("{\"action\":42,\"data\":\"numeric\"}", true).join();

            // Should route to the "42" route (numeric converted to string)
            String response = capture.getResponse(15, TimeUnit.SECONDS);
            assertNotNull(response, "Should receive a response when numeric field is converted to string");
            // The sendMessage Lambda handles this route, so we should get its response
            assertTrue(response.contains("42"),
                    "Response should come from the route matching '42', got: " + response);

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            Thread.sleep(500);
        } finally {
            // Clean up the numeric route
            given().when().delete("/v2/apis/" + wsApiId + "/routes/" + numericRouteId)
                    .then().statusCode(204);
        }
    }

    // ──────────────────────────── Test 8: Missing field falls to $default ────────────────────────────

    @Test
    @Order(80)
    void missingFieldFallsToDefault() throws Exception {
        // Message missing the field in routeSelectionExpression falls to $default
        WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
        WebSocket ws = connectWebSocketWithListener(wsApiId, "test", capture);
        assertNotNull(ws);

        // Send a JSON message that doesn't have the "action" field
        ws.sendText("{\"data\":\"no action field here\"}", true).join();

        // Should fall to $default
        String response = capture.getResponse(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive a response from the $default route");
        assertTrue(response.contains("default-handler"),
                "Response should indicate the $default route handled it, got: " + response);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(999)
    void cleanup() {
        // Delete APIs (cascades routes/integrations in storage)
        if (wsApiId != null) {
            given().when().delete("/v2/apis/" + wsApiId);
        }
        if (wsApiTypeId != null) {
            given().when().delete("/v2/apis/" + wsApiTypeId);
        }

        // Delete Lambda functions
        given().when().delete("/2015-03-31/functions/" + sendMessageFnName);
        given().when().delete("/2015-03-31/functions/" + defaultFnName);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private WebSocket connectWebSocketWithListener(String apiId, String stageName, WebSocketTestSupport.MessageCapture capture)
            throws Exception {
        String wsUrl = WebSocketTestSupport.buildWsUrl(baseUri, apiId, stageName);
        HttpClient client = HttpClient.newHttpClient();
        return client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), capture)
                .get(60, TimeUnit.SECONDS);
    }
}
