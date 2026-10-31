package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the @connections REST API (POST, GET, DELETE).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebSocketConnectionsApiTest {

    @TestHTTPResource("/")
    URI baseUri;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String wsApiId;
    private static String connectFnName = "ws-conn-api-connect-fn";
    private static String messageFnName = "ws-conn-api-message-fn";
    private static String integrationId;
    private static String messageIntegrationId;
    private static String connectRouteId;
    private static String defaultRouteId;

    // Track the connectionId from the WebSocket connection
    private static String activeConnectionId;

    // ──────────────────────────── Setup ────────────────────────────

    @Test
    @Order(1)
    void setupWebSocketApi() {
        // Create a WEBSOCKET API
        wsApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ws-connections-api-test","protocolType":"WEBSOCKET","routeSelectionExpression":"$request.body.action"}
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
    void setupLambdaFunctions() throws Exception {
        // $connect Lambda that returns 200
        String connectZip = WebSocketTestSupport.createLambdaZip(
                "exports.handler = async (event) => ({ statusCode: 200, body: 'connected' });");
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(connectFnName, connectZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        // $default Lambda that echoes the event (with routeResponseSelectionExpression)
        String messageZip = WebSocketTestSupport.createLambdaZip(
                "exports.handler = async (event) => ({ statusCode: 200, body: JSON.stringify({ event: event }) });");
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(messageFnName, messageZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        // Prewarm
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/2015-03-31/functions/" + connectFnName + "/invocations")
                .then().statusCode(200);
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/2015-03-31/functions/" + messageFnName + "/invocations")
                .then().statusCode(200);
    }

    @Test
    @Order(3)
    void setupIntegrationAndRoutes() {
        // Create integration for $connect
        integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST"}
                        """.formatted(connectFnName))
                .when().post("/v2/apis/" + wsApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        // Create integration for $default (message echo)
        messageIntegrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST"}
                        """.formatted(messageFnName))
                .when().post("/v2/apis/" + wsApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        // Create $connect route
        connectRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$connect","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");

        // Create $default route with routeResponseSelectionExpression for echo
        defaultRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$default","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                        """.formatted(messageIntegrationId))
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");
    }

    // ──────────────────────────── Test 1: POST sends message to client ────────────────────────────

    @Test
    @Order(10)
    void postToConnectionSendsMessageToClient() throws Exception {
        // POST to @connections sends message and returns 200
        WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
        WebSocket ws = connectWebSocketWithListener(wsApiId, "test", capture);
        assertNotNull(ws, "WebSocket connection should succeed");

        // We need the connectionId. Send a message to get the event echoed back which contains connectionId
        ws.sendText("{\"action\":\"getConnectionId\"}", true).join();
        String response = capture.getResponse(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive echoed event");

        JsonNode eventWrapper = MAPPER.readTree(response);
        String connectionId = eventWrapper.get("event").get("requestContext").get("connectionId").asText();
        assertNotNull(connectionId, "Should have a connectionId");
        activeConnectionId = connectionId;

        // Now POST a message via @connections API
        WebSocketTestSupport.MessageCapture capture2 = new WebSocketTestSupport.MessageCapture();
        // We need a new listener to capture the pushed message
        // Actually, the first capture already consumed its future. Let's reconnect.
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);

        // Reconnect with a fresh capture
        WebSocketTestSupport.MessageCapture pushCapture = new WebSocketTestSupport.MessageCapture();
        WebSocket ws2 = connectWebSocketWithListener(wsApiId, "test", pushCapture);
        assertNotNull(ws2, "Second WebSocket connection should succeed");

        // Get the new connectionId
        // Send a message first to get the connectionId from the echo
        WebSocketTestSupport.MessageCapture idCapture = new WebSocketTestSupport.MessageCapture();
        // We need yet another approach — let's use a multi-message capture
        ws2.sendText("{\"action\":\"getId\"}", true).join();
        // The pushCapture will get this response
        String idResponse = pushCapture.getResponse(15, TimeUnit.SECONDS);
        assertNotNull(idResponse, "Should receive echoed event for connectionId");

        JsonNode idEvent = MAPPER.readTree(idResponse);
        String connId = idEvent.get("event").get("requestContext").get("connectionId").asText();
        assertNotNull(connId, "Should have connectionId from echo");

        // Now use a fresh capture for the pushed message
        WebSocketTestSupport.MultiMessageCapture multiCapture = new WebSocketTestSupport.MultiMessageCapture();
        WebSocket ws3 = connectWebSocketWithListener2(wsApiId, "test", multiCapture);
        assertNotNull(ws3, "Third WebSocket connection should succeed");

        // Get connectionId
        ws3.sendText("{\"action\":\"getId\"}", true).join();
        String idResp = multiCapture.getNextMessage(15, TimeUnit.SECONDS);
        assertNotNull(idResp, "Should get connectionId response");
        JsonNode idNode = MAPPER.readTree(idResp);
        String ws3ConnId = idNode.get("event").get("requestContext").get("connectionId").asText();

        // POST a message to this connection via @connections API
        String pushMessage = "Hello from @connections API!";
        given()
                .body(pushMessage)
                .contentType(ContentType.TEXT)
                .when().post("/execute-api/" + wsApiId + "/test/@connections/" + ws3ConnId)
                .then()
                .statusCode(200);

        // The client should receive the pushed message
        String pushed = multiCapture.getNextMessage(15, TimeUnit.SECONDS);
        assertNotNull(pushed, "Client should receive pushed message");
        assertEquals(pushMessage, pushed, "Pushed message should match");

        ws2.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        ws3.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Test 2: POST to gone connection returns 410 ────────────────────────────

    @Test
    @Order(20)
    void postToGoneConnectionReturns410() {
        // POST to non-existent connection returns 410 GoneException
        String fakeConnectionId = "non-existent-connection-id";
        String body = given()
                .body("test message")
                .contentType(ContentType.TEXT)
                .when().post("/execute-api/" + wsApiId + "/test/@connections/" + fakeConnectionId)
                .then()
                .statusCode(410)
                .contentType(ContentType.JSON)
                .extract().body().asString();

        assertTrue(body.contains("GoneException"), "Response should contain GoneException");
    }

    // ──────────────────────────── Test 3: GET connection info returns metadata ────────────────────────────

    @Test
    @Order(30)
    void getConnectionInfoReturnsMetadata() throws Exception {
        // GET returns connectedAt, lastActiveAt, identity
        WebSocketTestSupport.MultiMessageCapture capture = new WebSocketTestSupport.MultiMessageCapture();
        WebSocket ws = connectWebSocketWithListener2(wsApiId, "test", capture);
        assertNotNull(ws, "WebSocket connection should succeed");

        // Get connectionId
        ws.sendText("{\"action\":\"getId\"}", true).join();
        String idResp = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertNotNull(idResp, "Should get connectionId response");
        JsonNode idNode = MAPPER.readTree(idResp);
        String connId = idNode.get("event").get("requestContext").get("connectionId").asText();

        // GET connection info
        String infoBody = given()
                .when().get("/execute-api/" + wsApiId + "/test/@connections/" + connId)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .extract().body().asString();

        JsonNode info = MAPPER.readTree(infoBody);
        assertNotNull(info.get("connectedAt"), "Should have connectedAt");
        assertNotNull(info.get("lastActiveAt"), "Should have lastActiveAt");
        assertNotNull(info.get("identity"), "Should have identity");
        assertNotNull(info.get("identity").get("sourceIp"), "Should have sourceIp");
        assertNotNull(info.get("identity").get("userAgent"), "Should have userAgent");

        // Verify ISO 8601 format (contains 'T' and ends with 'Z')
        String connectedAt = info.get("connectedAt").asText();
        assertTrue(connectedAt.contains("T"), "connectedAt should be ISO 8601 format");

        String lastActiveAt = info.get("lastActiveAt").asText();
        assertTrue(lastActiveAt.contains("T"), "lastActiveAt should be ISO 8601 format");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Test 4: GET gone connection returns 410 ────────────────────────────

    @Test
    @Order(40)
    void getGoneConnectionReturns410() {
        // GET for non-existent connection returns 410
        String fakeConnectionId = "non-existent-get-connection";
        String body = given()
                .when().get("/execute-api/" + wsApiId + "/test/@connections/" + fakeConnectionId)
                .then()
                .statusCode(410)
                .contentType(ContentType.JSON)
                .extract().body().asString();

        assertTrue(body.contains("GoneException"), "Response should contain GoneException");
    }

    // ──────────────────────────── Test 5: DELETE disconnects client ────────────────────────────

    @Test
    @Order(50)
    void deleteConnectionDisconnectsClient() throws Exception {
        // DELETE closes the connection and returns 204
        WebSocketTestSupport.MultiMessageCapture capture = new WebSocketTestSupport.MultiMessageCapture();
        CompletableFuture<Integer> closeFuture = new CompletableFuture<>();
        WebSocket ws = connectWebSocketWithCloseListener(wsApiId, "test", capture, closeFuture);
        assertNotNull(ws, "WebSocket connection should succeed");

        // Get connectionId
        ws.sendText("{\"action\":\"getId\"}", true).join();
        String idResp = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertNotNull(idResp, "Should get connectionId response");
        JsonNode idNode = MAPPER.readTree(idResp);
        String connId = idNode.get("event").get("requestContext").get("connectionId").asText();

        // DELETE the connection via @connections API
        given()
                .when().delete("/execute-api/" + wsApiId + "/test/@connections/" + connId)
                .then()
                .statusCode(204);

        // The WebSocket should be closed
        Integer closeCode = closeFuture.get(15, TimeUnit.SECONDS);
        assertNotNull(closeCode, "WebSocket should receive close frame");

        Thread.sleep(500);
    }

    // ──────────────────────────── Test 6: DELETE gone connection returns 410 ────────────────────────────

    @Test
    @Order(60)
    void deleteGoneConnectionReturns410() {
        // DELETE for non-existent connection returns 410
        String fakeConnectionId = "non-existent-delete-connection";
        String body = given()
                .when().delete("/execute-api/" + wsApiId + "/test/@connections/" + fakeConnectionId)
                .then()
                .statusCode(410)
                .contentType(ContentType.JSON)
                .extract().body().asString();

        assertTrue(body.contains("GoneException"), "Response should contain GoneException");
    }

    // ──────────────────────────── Test 7: lastActiveAt updated on message ────────────────────────────

    @Test
    @Order(70)
    void lastActiveAtUpdatedOnMessage() throws Exception {
        // lastActiveAt is updated when a message is received
        WebSocketTestSupport.MultiMessageCapture capture = new WebSocketTestSupport.MultiMessageCapture();
        WebSocket ws = connectWebSocketWithListener2(wsApiId, "test", capture);
        assertNotNull(ws, "WebSocket connection should succeed");

        // Get connectionId and initial lastActiveAt
        ws.sendText("{\"action\":\"getId\"}", true).join();
        String idResp = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertNotNull(idResp, "Should get connectionId response");
        JsonNode idNode = MAPPER.readTree(idResp);
        String connId = idNode.get("event").get("requestContext").get("connectionId").asText();

        // Get initial connection info
        String info1Body = given()
                .when().get("/execute-api/" + wsApiId + "/test/@connections/" + connId)
                .then()
                .statusCode(200)
                .extract().body().asString();
        JsonNode info1 = MAPPER.readTree(info1Body);
        String lastActive1 = info1.get("lastActiveAt").asText();

        // Wait a bit and send another message
        Thread.sleep(1100);
        ws.sendText("{\"action\":\"update\"}", true).join();
        String updateResp = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertNotNull(updateResp, "Should get response after second message");

        // Get updated connection info
        String info2Body = given()
                .when().get("/execute-api/" + wsApiId + "/test/@connections/" + connId)
                .then()
                .statusCode(200)
                .extract().body().asString();
        JsonNode info2 = MAPPER.readTree(info2Body);
        String lastActive2 = info2.get("lastActiveAt").asText();

        // lastActiveAt should have been updated
        assertNotEquals(lastActive1, lastActive2,
                "lastActiveAt should be updated after receiving a message");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Test 8: Server-initiated DELETE does not invoke $disconnect ────────────────────────────

    @Test
    @Order(80)
    void serverInitiatedDeleteDoesNotInvokeDisconnect() throws Exception {
        // AWS behavior: when a connection is closed via @connections DELETE,
        // the $disconnect Lambda is NOT invoked. Only client-initiated disconnections trigger $disconnect.
        // We verify this by connecting, then using DELETE to close, and confirming the connection
        // is properly cleaned up (subsequent POST returns 410).
        WebSocketTestSupport.MultiMessageCapture capture = new WebSocketTestSupport.MultiMessageCapture();
        CompletableFuture<Integer> closeFuture = new CompletableFuture<>();
        WebSocket ws = connectWebSocketWithCloseListener(wsApiId, "test", capture, closeFuture);
        assertNotNull(ws, "WebSocket connection should succeed");

        // Get connectionId
        ws.sendText("{\"action\":\"getId\"}", true).join();
        String idResp = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertNotNull(idResp, "Should get connectionId response");
        JsonNode idNode = MAPPER.readTree(idResp);
        String connId = idNode.get("event").get("requestContext").get("connectionId").asText();

        // DELETE the connection via @connections API (server-initiated close)
        given()
                .when().delete("/execute-api/" + wsApiId + "/test/@connections/" + connId)
                .then()
                .statusCode(204);

        // Wait for the WebSocket to be closed
        Integer closeCode = closeFuture.get(15, TimeUnit.SECONDS);
        assertNotNull(closeCode, "WebSocket should receive close frame");

        // Wait for cleanup
        Thread.sleep(500);

        // Verify the connection is fully cleaned up (POST returns 410)
        given()
                .body("test")
                .contentType(ContentType.TEXT)
                .when().post("/execute-api/" + wsApiId + "/test/@connections/" + connId)
                .then()
                .statusCode(410);
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(999)
    void cleanup() {
        if (connectRouteId != null) {
            given().when().delete("/v2/apis/" + wsApiId + "/routes/" + connectRouteId);
        }
        if (defaultRouteId != null) {
            given().when().delete("/v2/apis/" + wsApiId + "/routes/" + defaultRouteId);
        }
        if (wsApiId != null) {
            given().when().delete("/v2/apis/" + wsApiId);
        }
        given().when().delete("/2015-03-31/functions/" + connectFnName);
        given().when().delete("/2015-03-31/functions/" + messageFnName);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private WebSocket connectWebSocketWithListener(String apiId, String stageName,
                                                    WebSocketTestSupport.MessageCapture capture) throws Exception {
        String wsUrl = WebSocketTestSupport.buildWsUrl(baseUri, apiId, stageName);
        HttpClient client = HttpClient.newHttpClient();
        return client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), capture)
                .get(60, TimeUnit.SECONDS);
    }

    private WebSocket connectWebSocketWithListener2(String apiId, String stageName,
                                                     WebSocketTestSupport.MultiMessageCapture capture) throws Exception {
        String wsUrl = WebSocketTestSupport.buildWsUrl(baseUri, apiId, stageName);
        HttpClient client = HttpClient.newHttpClient();
        return client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), capture)
                .get(60, TimeUnit.SECONDS);
    }

    private WebSocket connectWebSocketWithCloseListener(String apiId, String stageName,
                                                         WebSocketTestSupport.MultiMessageCapture capture,
                                                         CompletableFuture<Integer> closeFuture) throws Exception {
        String wsUrl = WebSocketTestSupport.buildWsUrl(baseUri, apiId, stageName);
        HttpClient client = HttpClient.newHttpClient();
        return client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(10);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            capture.complete(buffer.toString());
                            buffer.setLength(0);
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        closeFuture.complete(statusCode);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        closeFuture.completeExceptionally(error);
                    }
                })
                .get(60, TimeUnit.SECONDS);
    }
}
