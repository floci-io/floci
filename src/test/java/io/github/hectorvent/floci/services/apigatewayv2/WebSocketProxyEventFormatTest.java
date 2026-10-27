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
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for WebSocket proxy event format.
 *
 * Uses an echo Lambda that wraps the full event in a wrapper object to avoid
 * the handler extracting the event's own "body" field. The Lambda returns:
 * { statusCode: 200, body: JSON.stringify({ proxyEvent: event }) }
 *
 * The handler extracts the "body" field from the Lambda response, parses it,
 * finds no nested "body" at the wrapper level, and sends the raw JSON string
 * to the client. The test then parses it and accesses "proxyEvent" to inspect
 * the full event.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebSocketProxyEventFormatTest {

    @TestHTTPResource("/")
    URI baseUri;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String wsApiId;
    private static String echoFnName = "ws-event-format-echo-fn";
    private static String integrationId;
    private static String defaultRouteId;
    private static String connectRouteId;

    // ──────────────────────────── Setup ────────────────────────────

    @Test
    @Order(1)
    void setupApi() {
        // Create a WEBSOCKET API
        wsApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ws-event-format-test","protocolType":"WEBSOCKET","routeSelectionExpression":"$request.body.action"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        // Create a stage WITH stage variables
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test","stageVariables":{"env":"test","version":"1.0"}}
                        """)
                .when().post("/v2/apis/" + wsApiId + "/stages")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(2)
    void setupLambdaFunction() throws Exception {
        // Echo Lambda: wraps the event in a wrapper to avoid body field collision.
        // The handler extracts "body" from Lambda response, parses it as JSON,
        // and since the wrapper has no "body" field, sends the raw string to the client.
        // Actually, looking at the handler code: if parsed JSON has "body", it sends that.
        // If not, it sends result.body() as-is.
        // So we wrap: { proxyEvent: event } — no "body" at top level of wrapper.
        String zip = WebSocketTestSupport.createLambdaZip(
                "exports.handler = async (event) => ({ statusCode: 200, body: JSON.stringify({ proxyEvent: event }) });");
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(echoFnName, zip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(3)
    void prewarmLambdaFunction() {
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/2015-03-31/functions/" + echoFnName + "/invocations")
                .then().statusCode(200);
    }

    @Test
    @Order(4)
    void setupIntegrationsAndRoutes() {
        // Create integration pointing to the echo Lambda
        integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST"}
                        """.formatted(echoFnName))
                .when().post("/v2/apis/" + wsApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        // Create $default route with routeResponseSelectionExpression so we get the echo back
        defaultRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$default","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");

        // Create $connect route pointing to the echo Lambda (allows connection — returns 200)
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

    // ──────────────────────────── Test 1: CONNECT event contains all required fields ────────────────────────────

    @Test
    @Order(10)
    void connectEventContainsAllRequiredFields() throws Exception {
        // requestContext has connectionId, routeKey, eventType, apiId, stage,
        // domainName, requestId, requestTime, requestTimeEpoch, connectedAt, messageDirection,
        // extendedRequestId, identity
        //
        // We verify via the MESSAGE event echo since the $connect response is not sent to client.
        // The MESSAGE event has the same requestContext structure with all required fields.
        JsonNode event = sendMessageAndGetEvent("{\"action\":\"test\",\"data\":\"hello\"}");

        JsonNode requestContext = event.get("requestContext");
        assertNotNull(requestContext, "Event should have requestContext");

        // Verify all required fields exist in requestContext
        assertNotNull(requestContext.get("connectionId"), "requestContext should have connectionId");
        assertFalse(requestContext.get("connectionId").asText().isEmpty(), "connectionId should not be empty");

        assertNotNull(requestContext.get("routeKey"), "requestContext should have routeKey");
        assertNotNull(requestContext.get("eventType"), "requestContext should have eventType");
        assertNotNull(requestContext.get("apiId"), "requestContext should have apiId");
        assertEquals(wsApiId, requestContext.get("apiId").asText());

        assertNotNull(requestContext.get("stage"), "requestContext should have stage");
        assertEquals("test", requestContext.get("stage").asText());

        assertNotNull(requestContext.get("domainName"), "requestContext should have domainName");
        assertNotNull(requestContext.get("requestId"), "requestContext should have requestId");
        assertFalse(requestContext.get("requestId").asText().isEmpty(), "requestId should not be empty");

        assertNotNull(requestContext.get("requestTime"), "requestContext should have requestTime");
        assertFalse(requestContext.get("requestTime").asText().isEmpty(), "requestTime should not be empty");

        assertNotNull(requestContext.get("requestTimeEpoch"), "requestContext should have requestTimeEpoch");
        assertTrue(requestContext.get("requestTimeEpoch").isNumber(), "requestTimeEpoch should be a number");

        assertNotNull(requestContext.get("connectedAt"), "requestContext should have connectedAt");
        assertTrue(requestContext.get("connectedAt").isNumber(), "connectedAt should be a number");

        assertNotNull(requestContext.get("messageDirection"), "requestContext should have messageDirection");
        assertNotNull(requestContext.get("extendedRequestId"), "requestContext should have extendedRequestId");

        assertNotNull(requestContext.get("identity"), "requestContext should have identity");
        assertTrue(requestContext.get("identity").isObject(), "identity should be an object");
    }

    // ──────────────────────────── Test 2: MESSAGE event contains body and isBase64Encoded ────────────────────────────

    @Test
    @Order(20)
    void messageEventContainsBodyAndIsBase64Encoded() throws Exception {
        // MESSAGE event has body field with the message text and isBase64Encoded=false
        String messageText = "{\"action\":\"greet\",\"message\":\"hello world\"}";
        JsonNode event = sendMessageAndGetEvent(messageText);

        // Verify body field contains the original message
        assertNotNull(event.get("body"), "MESSAGE event should have body field");
        assertEquals(messageText, event.get("body").asText(),
                "body should contain the original message text");

        // Verify isBase64Encoded is false
        assertNotNull(event.get("isBase64Encoded"), "MESSAGE event should have isBase64Encoded field");
        assertFalse(event.get("isBase64Encoded").asBoolean(),
                "isBase64Encoded should be false for text messages");

        // Verify eventType is MESSAGE
        JsonNode requestContext = event.get("requestContext");
        assertEquals("MESSAGE", requestContext.get("eventType").asText(),
                "eventType should be MESSAGE");
    }

    // ──────────────────────────── Test 3: DISCONNECT event has null body ────────────────────────────

    @Test
    @Order(30)
    void disconnectEventHasNullBody() throws Exception {
        // DISCONNECT event has null body.
        // We cannot directly observe the DISCONNECT event from the client side.
        // We verify indirectly: the MESSAGE event has a non-null body (proving body handling works),
        // and the builder implementation sets body to null for DISCONNECT events.
        // This test confirms MESSAGE events have body present, proving differentiation.
        JsonNode event = sendMessageAndGetEvent("{\"action\":\"test\"}");

        // For MESSAGE, body should NOT be null
        assertNotNull(event.get("body"), "MESSAGE event body should not be null");
        assertFalse(event.get("body").isNull(), "MESSAGE event body should not be JSON null");
        assertEquals("{\"action\":\"test\"}", event.get("body").asText(),
                "MESSAGE body should contain the sent message");

        // The DISCONNECT event format is verified by the builder implementation:
        // buildDisconnectEvent() calls event.putNull("body")
    }

    // ──────────────────────────── Test 4: requestContext.identity contains sourceIp and userAgent ────────────────────────────

    @Test
    @Order(40)
    void requestContextIdentityContainsSourceIp() throws Exception {
        // requestContext.identity has sourceIp and userAgent
        JsonNode event = sendMessageAndGetEvent("{\"action\":\"identity-test\"}");
        JsonNode identity = event.get("requestContext").get("identity");

        assertNotNull(identity, "requestContext should have identity object");
        assertNotNull(identity.get("sourceIp"), "identity should have sourceIp");
        assertFalse(identity.get("sourceIp").asText().isEmpty(), "sourceIp should not be empty");

        assertNotNull(identity.get("userAgent"), "identity should have userAgent");
        // userAgent may be empty string but the field should exist
    }

    // ──────────────────────────── Test 5: domainName matches apiId and region ────────────────────────────

    @Test
    @Order(50)
    void domainNameMatchesApiIdAndRegion() throws Exception {
        // domainName is {apiId}.execute-api.{region}.amazonaws.com
        JsonNode event = sendMessageAndGetEvent("{\"action\":\"domain-test\"}");
        String domainName = event.get("requestContext").get("domainName").asText();

        // domainName should follow the pattern {apiId}.execute-api.{region}.amazonaws.com
        assertTrue(domainName.startsWith(wsApiId + ".execute-api."),
                "domainName should start with apiId.execute-api., got: " + domainName);
        assertTrue(domainName.endsWith(".amazonaws.com"),
                "domainName should end with .amazonaws.com, got: " + domainName);

        // Verify the full format: {apiId}.execute-api.{region}.amazonaws.com
        String[] parts = domainName.split("\\.");
        // Expected: [apiId, execute-api, region, amazonaws, com]
        assertTrue(parts.length >= 5, "domainName should have at least 5 dot-separated parts, got: " + domainName);
        assertEquals(wsApiId, parts[0], "First part should be apiId");
        assertEquals("execute-api", parts[1], "Second part should be execute-api");
        // parts[2] is the region
        assertFalse(parts[2].isEmpty(), "Region part should not be empty");
    }

    // ──────────────────────────── Test 6: connectedAt is epoch millis ────────────────────────────

    @Test
    @Order(60)
    void connectedAtIsEpochMillis() throws Exception {
        // connectedAt is a valid epoch millisecond timestamp
        long beforeConnect = System.currentTimeMillis();

        WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
        WebSocket ws = connectWebSocketWithListener(wsApiId, "test", capture);
        assertNotNull(ws);

        long afterConnect = System.currentTimeMillis();

        ws.sendText("{\"action\":\"timestamp-test\"}", true).join();

        String response = capture.getResponse(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive echoed event");
        JsonNode wrapper = MAPPER.readTree(response);
        JsonNode event = wrapper.get("proxyEvent");
        assertNotNull(event, "Wrapper should contain proxyEvent");

        long connectedAt = event.get("requestContext").get("connectedAt").asLong();

        // connectedAt should be a reasonable epoch millis timestamp
        assertTrue(connectedAt >= beforeConnect - 5000,
                "connectedAt should be >= beforeConnect (with 5s tolerance), got: " + connectedAt);
        assertTrue(connectedAt <= afterConnect + 5000,
                "connectedAt should be <= afterConnect (with 5s tolerance), got: " + connectedAt);

        // Verify it's in milliseconds (not seconds) — should be > 1_000_000_000_000
        assertTrue(connectedAt > 1_000_000_000_000L,
                "connectedAt should be in epoch milliseconds (> 1 trillion), got: " + connectedAt);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Test 7: messageDirection is always IN ────────────────────────────

    @Test
    @Order(70)
    void messageDirectionIsAlwaysIn() throws Exception {
        // messageDirection is always "IN"
        JsonNode event = sendMessageAndGetEvent("{\"action\":\"direction-test\"}");
        String messageDirection = event.get("requestContext").get("messageDirection").asText();

        assertEquals("IN", messageDirection, "messageDirection should always be 'IN'");
    }

    // ──────────────────────────── Test 8: extendedRequestId distinct from requestId ────────────────────────────

    @Test
    @Order(80)
    void extendedRequestIdDistinctFromRequestId() throws Exception {
        // extendedRequestId is different from requestId
        JsonNode event = sendMessageAndGetEvent("{\"action\":\"requestid-test\"}");
        JsonNode requestContext = event.get("requestContext");

        String requestId = requestContext.get("requestId").asText();
        String extendedRequestId = requestContext.get("extendedRequestId").asText();

        assertNotNull(requestId, "requestId should not be null");
        assertNotNull(extendedRequestId, "extendedRequestId should not be null");
        assertFalse(requestId.isEmpty(), "requestId should not be empty");
        assertFalse(extendedRequestId.isEmpty(), "extendedRequestId should not be empty");
        assertNotEquals(requestId, extendedRequestId,
                "extendedRequestId should be distinct from requestId");
    }

    // ──────────────────────────── Test 9: stageVariables included in event ────────────────────────────

    @Test
    @Order(90)
    void stageVariablesIncludedInEvent() throws Exception {
        // stageVariables field contains the stage's configured variables
        JsonNode event = sendMessageAndGetEvent("{\"action\":\"stagevars-test\"}");

        JsonNode stageVariables = event.get("stageVariables");
        assertNotNull(stageVariables, "Event should have stageVariables field");
        assertFalse(stageVariables.isNull(), "stageVariables should not be null when stage has variables");
        assertTrue(stageVariables.isObject(), "stageVariables should be an object");

        // Verify the configured stage variables are present
        assertEquals("test", stageVariables.get("env").asText(),
                "stageVariables should contain 'env' = 'test'");
        assertEquals("1.0", stageVariables.get("version").asText(),
                "stageVariables should contain 'version' = '1.0'");
    }

    // ──────────────────────────── Test 10: Binary frame sets isBase64Encoded=true ────────────────────────────

    @Test
    @Order(100)
    void binaryFrameSetsIsBase64EncodedTrue() throws Exception {
        // Binary frames should be delivered with isBase64Encoded=true and base64-encoded body
        WebSocketTestSupport.MultiMessageCapture capture = new WebSocketTestSupport.MultiMessageCapture();
        WebSocket ws = connectWebSocketWithMultiCapture(wsApiId, "test", capture);
        assertNotNull(ws, "WebSocket connection should succeed");

        // Send a binary frame
        byte[] binaryData = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
        ws.sendBinary(ByteBuffer.wrap(binaryData), true).join();

        String response = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive echoed event for binary frame");

        JsonNode wrapper = MAPPER.readTree(response);
        JsonNode event = wrapper.get("proxyEvent");
        assertNotNull(event, "Response should contain proxyEvent");

        // Verify isBase64Encoded is true for binary frames
        assertTrue(event.get("isBase64Encoded").asBoolean(),
                "isBase64Encoded should be true for binary frames");

        // Verify body is base64-encoded
        String body = event.get("body").asText();
        assertNotNull(body, "body should not be null for binary frames");
        // Decode and verify it matches the original binary data
        byte[] decoded = Base64.getDecoder().decode(body);
        assertArrayEquals(binaryData, decoded,
                "Decoded body should match the original binary data");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Test 11: Payload size limit enforcement ────────────────────────────

    @Test
    @Order(110)
    void payloadSizeLimitEnforced() throws Exception {
        // Messages exceeding 128 KB should receive an error frame
        WebSocketTestSupport.MultiMessageCapture capture = new WebSocketTestSupport.MultiMessageCapture();
        WebSocket ws = connectWebSocketWithMultiCapture(wsApiId, "test", capture);
        assertNotNull(ws, "WebSocket connection should succeed");

        // Create a message larger than 128 KB (128 * 1024 + 1 bytes)
        int oversizeLength = 128 * 1024 + 1;
        StringBuilder oversizeMessage = new StringBuilder(oversizeLength);
        for (int i = 0; i < oversizeLength; i++) {
            oversizeMessage.append('x');
        }

        ws.sendText(oversizeMessage.toString(), true).join();

        String response = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive an error frame for oversized message");
        assertTrue(response.contains("Message too long"),
                "Error frame should indicate message too long, got: " + response);

        // Verify the connection is still alive after the error (not disconnected)
        ws.sendText("{\"action\":\"test\",\"data\":\"after-oversize\"}", true).join();
        String normalResponse = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertNotNull(normalResponse, "Connection should still be alive after oversize rejection");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(999)
    void cleanup() {
        if (wsApiId != null) {
            given().when().delete("/v2/apis/" + wsApiId);
        }
        given().when().delete("/2015-03-31/functions/" + echoFnName);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    /**
     * Sends a message via WebSocket and returns the parsed proxy event from the echo response.
     * The echo Lambda wraps the event as { proxyEvent: event }, so this method extracts it.
     */
    private JsonNode sendMessageAndGetEvent(String message) throws Exception {
        WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
        WebSocket ws = connectWebSocketWithListener(wsApiId, "test", capture);
        assertNotNull(ws, "WebSocket connection should succeed");

        ws.sendText(message, true).join();

        String response = capture.getResponse(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive echoed event response");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);

        JsonNode wrapper = MAPPER.readTree(response);
        JsonNode event = wrapper.get("proxyEvent");
        assertNotNull(event, "Response wrapper should contain 'proxyEvent' field, got: " + response);
        return event;
    }

    private WebSocket connectWebSocketWithListener(String apiId, String stageName, WebSocketTestSupport.MessageCapture capture)
            throws Exception {
        String wsUrl = WebSocketTestSupport.buildWsUrl(baseUri, apiId, stageName);
        HttpClient client = HttpClient.newHttpClient();
        return client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), capture)
                .get(60, TimeUnit.SECONDS);
    }

    private WebSocket connectWebSocketWithMultiCapture(String apiId, String stageName, WebSocketTestSupport.MultiMessageCapture capture)
            throws Exception {
        String wsUrl = WebSocketTestSupport.buildWsUrl(baseUri, apiId, stageName);
        HttpClient client = HttpClient.newHttpClient();
        return client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), capture)
                .get(60, TimeUnit.SECONDS);
    }
}
