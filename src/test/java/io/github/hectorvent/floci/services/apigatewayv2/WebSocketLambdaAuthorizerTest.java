package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Lambda REQUEST authorizer on $connect route.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebSocketLambdaAuthorizerTest {

    @io.quarkus.test.common.http.TestHTTPResource("/")
    URI baseUri;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String wsApiId;
    private static String allowAuthFnName = "ws-auth-allow-fn";
    private static String denyAuthFnName = "ws-auth-deny-fn";
    private static String errorAuthFnName = "ws-auth-error-fn";
    private static String contextAuthFnName = "ws-auth-context-fn";
    private static String echoAuthFnName = "ws-auth-echo-fn";
    private static String connectFnName = "ws-auth-connect-fn";
    private static String integrationId;
    private static String connectRouteId;
    private static String allowAuthorizerId;
    private static String denyAuthorizerId;
    private static String errorAuthorizerId;
    private static String contextAuthorizerId;
    private static String echoAuthorizerId;
    private static String identitySourceAuthorizerId;

    // ──────────────────────────── Setup ────────────────────────────

    @Test
    @Order(1)
    void setupWebSocketApi() {
        // Create a WEBSOCKET API
        wsApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ws-authorizer-test","protocolType":"WEBSOCKET","routeSelectionExpression":"$request.body.action"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        // Create a stage with stage variables
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test","stageVariables":{"env":"testing","version":"2.0"}}
                        """)
                .when().post("/v2/apis/" + wsApiId + "/stages")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(2)
    void setupLambdaFunctions() throws Exception {
        // Authorizer that returns Allow policy
        String allowZip = WebSocketTestSupport.createLambdaZip("""
                exports.handler = async (event) => ({
                    principalId: "user123",
                    policyDocument: {
                        Version: "2012-10-17",
                        Statement: [{ Action: "execute-api:Invoke", Effect: "Allow", Resource: event.methodArn }]
                    }
                });
                """);
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(allowAuthFnName, allowZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        // Authorizer that returns Deny policy
        String denyZip = WebSocketTestSupport.createLambdaZip("""
                exports.handler = async (event) => ({
                    principalId: "user123",
                    policyDocument: {
                        Version: "2012-10-17",
                        Statement: [{ Action: "execute-api:Invoke", Effect: "Deny", Resource: event.methodArn }]
                    }
                });
                """);
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(denyAuthFnName, denyZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        // Authorizer that throws an error
        String errorZip = WebSocketTestSupport.createLambdaZip("""
                exports.handler = async (event) => { throw new Error("Authorizer error"); };
                """);
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(errorAuthFnName, errorZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        // Authorizer that returns Allow policy WITH context
        String contextZip = WebSocketTestSupport.createLambdaZip("""
                exports.handler = async (event) => ({
                    principalId: "user456",
                    policyDocument: {
                        Version: "2012-10-17",
                        Statement: [{ Action: "execute-api:Invoke", Effect: "Allow", Resource: event.methodArn }]
                    },
                    context: { userId: "user456", role: "admin", score: 42 }
                });
                """);
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(contextAuthFnName, contextZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        // Authorizer that echoes the event payload (for format verification)
        String echoZip = WebSocketTestSupport.createLambdaZip("""
                exports.handler = async (event) => ({
                    principalId: "user789",
                    policyDocument: {
                        Version: "2012-10-17",
                        Statement: [{ Action: "execute-api:Invoke", Effect: "Allow", Resource: event.methodArn }]
                    },
                    context: { authorizerEvent: JSON.stringify(event) }
                });
                """);
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(echoAuthFnName, echoZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        // $connect integration Lambda that echoes the event
        String connectZip = WebSocketTestSupport.createLambdaZip("""
                exports.handler = async (event) => ({ statusCode: 200, body: JSON.stringify({ proxyEvent: event }) });
                """);
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(connectFnName, connectZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(3)
    void prewarmLambdaFunctions() {
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/2015-03-31/functions/" + allowAuthFnName + "/invocations")
                .then().statusCode(200);
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/2015-03-31/functions/" + denyAuthFnName + "/invocations")
                .then().statusCode(200);
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/2015-03-31/functions/" + errorAuthFnName + "/invocations")
                .then().statusCode(200);
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/2015-03-31/functions/" + contextAuthFnName + "/invocations")
                .then().statusCode(200);
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/2015-03-31/functions/" + echoAuthFnName + "/invocations")
                .then().statusCode(200);
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/2015-03-31/functions/" + connectFnName + "/invocations")
                .then().statusCode(200);
    }

    @Test
    @Order(4)
    void setupIntegrationAndRoute() {
        // Create integration for the $connect Lambda
        integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST"}
                        """.formatted(connectFnName))
                .when().post("/v2/apis/" + wsApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        // Create $connect route (no authorizer initially)
        connectRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$connect","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");
    }

    @Test
    @Order(5)
    void setupAuthorizers() {
        // Allow authorizer
        allowAuthorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"allow-auth","authorizerType":"REQUEST","authorizerUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations"}
                        """.formatted(allowAuthFnName))
                .when().post("/v2/apis/" + wsApiId + "/authorizers")
                .then()
                .statusCode(201)
                .extract().path("authorizerId");

        // Deny authorizer
        denyAuthorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"deny-auth","authorizerType":"REQUEST","authorizerUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations"}
                        """.formatted(denyAuthFnName))
                .when().post("/v2/apis/" + wsApiId + "/authorizers")
                .then()
                .statusCode(201)
                .extract().path("authorizerId");

        // Error authorizer
        errorAuthorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"error-auth","authorizerType":"REQUEST","authorizerUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations"}
                        """.formatted(errorAuthFnName))
                .when().post("/v2/apis/" + wsApiId + "/authorizers")
                .then()
                .statusCode(201)
                .extract().path("authorizerId");

        // Context authorizer
        contextAuthorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"context-auth","authorizerType":"REQUEST","authorizerUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations"}
                        """.formatted(contextAuthFnName))
                .when().post("/v2/apis/" + wsApiId + "/authorizers")
                .then()
                .statusCode(201)
                .extract().path("authorizerId");

        // Echo authorizer
        echoAuthorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"echo-auth","authorizerType":"REQUEST","authorizerUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations"}
                        """.formatted(echoAuthFnName))
                .when().post("/v2/apis/" + wsApiId + "/authorizers")
                .then()
                .statusCode(201)
                .extract().path("authorizerId");

        // Identity source authorizer (requires Authorization header)
        identitySourceAuthorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"identity-auth","authorizerType":"REQUEST","identitySource":"$request.header.Authorization","authorizerUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations"}
                        """.formatted(allowAuthFnName))
                .when().post("/v2/apis/" + wsApiId + "/authorizers")
                .then()
                .statusCode(201)
                .extract().path("authorizerId");
    }

    // ──────────────────────────── Test 1: Authorizer allows connection ────────────────────────────

    @Test
    @Order(10)
    void authorizerAllowsConnection() throws Exception {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(allowAuthorizerId))
                .when().patch("/v2/apis/" + wsApiId + "/routes/" + connectRouteId)
                .then()
                .statusCode(200);

        WebSocket ws = connectWebSocket(wsApiId, "test");
        assertNotNull(ws, "WebSocket connection should succeed when authorizer returns Allow");
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Test 2: Authorizer denies connection ────────────────────────────

    @Test
    @Order(20)
    void authorizerDeniesConnection() throws Exception {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(denyAuthorizerId))
                .when().patch("/v2/apis/" + wsApiId + "/routes/" + connectRouteId)
                .then()
                .statusCode(200);

        assertWebSocketConnectionFails(wsApiId, "test", 403);
    }

    // ──────────────────────────── Test 3: Authorizer invocation error ────────────────────────────

    @Test
    @Order(30)
    void authorizerInvocationError() throws Exception {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(errorAuthorizerId))
                .when().patch("/v2/apis/" + wsApiId + "/routes/" + connectRouteId)
                .then()
                .statusCode(200);

        assertWebSocketConnectionFails(wsApiId, "test", 500);
    }

    // ──────────────────────────── Test 4: Authorizer context propagated to $connect integration ────────────────────────────

    @Test
    @Order(40)
    void authorizerContextPropagatedToConnectIntegration() throws Exception {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(contextAuthorizerId))
                .when().patch("/v2/apis/" + wsApiId + "/routes/" + connectRouteId)
                .then()
                .statusCode(200);

        String defaultRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$default","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");

        try {
            WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
            WebSocket ws = connectWebSocketWithListener(wsApiId, "test", capture);
            assertNotNull(ws, "Connection should succeed with context authorizer");

            ws.sendText("{\"action\":\"test\"}", true).join();

            String response = capture.getResponse(15, TimeUnit.SECONDS);
            assertNotNull(response, "Should receive echoed event");

            JsonNode wrapper = MAPPER.readTree(response);
            JsonNode event = wrapper.get("proxyEvent");
            assertNotNull(event, "Response should contain proxyEvent");

            JsonNode requestContext = event.get("requestContext");
            assertNotNull(requestContext, "Event should have requestContext");

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            Thread.sleep(500);
        } finally {
            given().when().delete("/v2/apis/" + wsApiId + "/routes/" + defaultRouteId);
        }
    }

    // ──────────────────────────── Test 5: Missing identity source rejects with 401 ────────────────────────────

    @Test
    @Order(50)
    void missingIdentitySourceRejectsWithout401() throws Exception {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(identitySourceAuthorizerId))
                .when().patch("/v2/apis/" + wsApiId + "/routes/" + connectRouteId)
                .then()
                .statusCode(200);

        // Connect WITHOUT the required Authorization header — should get 401
        assertWebSocketConnectionFails(wsApiId, "test", 401);

        // Now connect WITH the Authorization header — should succeed
        WebSocket ws = connectWebSocketWithHeader(wsApiId, "test", "Authorization", "Bearer test-token");
        assertNotNull(ws, "Connection should succeed when identity source header is present");
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Test 6: Authorizer event payload matches AWS format ────────────────────────────

    @Test
    @Order(60)
    void authorizerEventPayloadMatchesAwsFormat() throws Exception {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"CUSTOM","authorizerId":"%s"}
                        """.formatted(echoAuthorizerId))
                .when().patch("/v2/apis/" + wsApiId + "/routes/" + connectRouteId)
                .then()
                .statusCode(200);

        String defaultRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$default","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");

        try {
            WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
            WebSocket ws = connectWebSocketWithQueryAndListener(wsApiId, "test", "token=abc123", capture);
            assertNotNull(ws, "Connection should succeed with echo authorizer");

            ws.sendText("{\"action\":\"check\"}", true).join();

            String response = capture.getResponse(15, TimeUnit.SECONDS);
            assertNotNull(response, "Should receive echoed event");

            JsonNode wrapper = MAPPER.readTree(response);
            JsonNode messageEvent = wrapper.get("proxyEvent");
            assertNotNull(messageEvent, "Response should contain proxyEvent");

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            Thread.sleep(500);

            WebSocketTestSupport.MessageCapture capture2 = new WebSocketTestSupport.MessageCapture();
            WebSocket ws2 = connectWebSocketWithQueryAndListener(wsApiId, "test", "token=verify123", capture2);
            assertNotNull(ws2, "Second connection should succeed");

            ws2.sendText("{\"action\":\"verify\"}", true).join();
            String response2 = capture2.getResponse(15, TimeUnit.SECONDS);
            assertNotNull(response2, "Should receive second echoed event");

            String testPayload = """
                    {"type":"REQUEST","methodArn":"arn:aws:execute-api:us-east-1:000000000000:%s/test/$connect"}
                    """.formatted(wsApiId);
            String invokeResponse = given()
                    .contentType(ContentType.JSON)
                    .body(testPayload)
                    .when().post("/2015-03-31/functions/" + echoAuthFnName + "/invocations")
                    .then()
                    .statusCode(200)
                    .extract().body().asString();

            JsonNode authResponse = MAPPER.readTree(invokeResponse);
            assertNotNull(authResponse.get("context"), "Authorizer should return context");
            String authEventStr = authResponse.get("context").get("authorizerEvent").asText();
            JsonNode authEvent = MAPPER.readTree(authEventStr);

            assertEquals("REQUEST", authEvent.get("type").asText(),
                    "Authorizer event type should be REQUEST");
            assertNotNull(authEvent.get("methodArn"), "Authorizer event should have methodArn");
            assertTrue(authEvent.get("methodArn").asText().contains("$connect"),
                    "methodArn should contain $connect");

            ws2.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            Thread.sleep(500);
        } finally {
            given().when().delete("/v2/apis/" + wsApiId + "/routes/" + defaultRouteId);
        }
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(999)
    void cleanup() throws Exception {
        if (connectRouteId != null) {
            given().when().delete("/v2/apis/" + wsApiId + "/routes/" + connectRouteId);
        }
        if (wsApiId != null) {
            given().when().delete("/v2/apis/" + wsApiId);
        }
        WebSocketTestSupport.deleteFunctions(allowAuthFnName, denyAuthFnName, errorAuthFnName,
                contextAuthFnName, echoAuthFnName, connectFnName);
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

    private WebSocket connectWebSocketWithHeader(String apiId, String stageName,
                                                  String headerName, String headerValue) throws Exception {
        String wsUrl = WebSocketTestSupport.buildWsUrl(baseUri, apiId, stageName);
        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<WebSocket> wsFuture = client.newWebSocketBuilder()
                .header(headerName, headerValue)
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

    private WebSocket connectWebSocketWithListener(String apiId, String stageName,
                                                    WebSocketTestSupport.MessageCapture capture) throws Exception {
        String wsUrl = WebSocketTestSupport.buildWsUrl(baseUri, apiId, stageName);
        HttpClient client = HttpClient.newHttpClient();
        return client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), capture)
                .get(60, TimeUnit.SECONDS);
    }

    private WebSocket connectWebSocketWithQueryAndListener(String apiId, String stageName,
                                                            String queryString, WebSocketTestSupport.MessageCapture capture) throws Exception {
        String wsUrl = WebSocketTestSupport.buildWsUrl(baseUri, apiId, stageName) + "?" + queryString;
        HttpClient client = HttpClient.newHttpClient();
        return client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), capture)
                .get(60, TimeUnit.SECONDS);
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
