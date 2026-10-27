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
import java.util.concurrent.TimeoutException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for WebSocket route response selection expression.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebSocketRouteResponseTest {

    @TestHTTPResource("/")
    URI baseUri;

    private static String wsApiId;
    private static String lambdaFnName = "ws-route-response-fn";
    private static String integrationId;
    private static String routeWithResponseId;
    private static String routeWithoutResponseId;

    // ──────────────────────────── Setup ────────────────────────────

    @Test
    @Order(1)
    void setupApi() {
        // Create a WEBSOCKET API with routeSelectionExpression: $request.body.action
        wsApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ws-route-response-test","protocolType":"WEBSOCKET","routeSelectionExpression":"$request.body.action"}
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
    void setupLambdaFunction() throws Exception {
        // Lambda that returns {"statusCode": 200, "body": "extracted-body"}
        String zip = WebSocketTestSupport.createLambdaZip(
                "exports.handler = async (event) => ({ statusCode: 200, body: 'extracted-body' });");
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(lambdaFnName, zip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(3)
    void prewarmLambdaFunction() {
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/2015-03-31/functions/" + lambdaFnName + "/invocations")
                .then().statusCode(200);
    }

    @Test
    @Order(4)
    void setupIntegrationsAndRoutes() {
        // Create integration pointing to the Lambda function
        integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST"}
                        """.formatted(lambdaFnName))
                .when().post("/v2/apis/" + wsApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        // Route WITH routeResponseSelectionExpression (response should be sent back)
        routeWithResponseId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"withResponse","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");

        // Route WITHOUT routeResponseSelectionExpression (response should NOT be sent back)
        routeWithoutResponseId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"noResponse","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");
    }

    // ──────────────────────────── Test 1: Response returned when routeResponseSelectionExpression set ────────────────────────────

    @Test
    @Order(10)
    void responseReturnedWhenRouteResponseExpressionSet() throws Exception {
        // When a route has a non-null routeResponseSelectionExpression,
        // the integration response body is sent back to the client
        WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
        WebSocket ws = connectWebSocketWithListener(wsApiId, "test", capture);
        assertNotNull(ws);

        // Send a message that routes to the "withResponse" route
        ws.sendText("{\"action\":\"withResponse\",\"data\":\"hello\"}", true).join();

        // Should receive a response back from the Lambda
        String response = capture.getResponse(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive a response when routeResponseSelectionExpression is set");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Test 2: No response when routeResponseSelectionExpression is null ────────────────────────────

    @Test
    @Order(20)
    void noResponseWhenRouteResponseExpressionNull() throws Exception {
        // When a route has null routeResponseSelectionExpression,
        // no response is sent back to the client
        WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
        WebSocket ws = connectWebSocketWithListener(wsApiId, "test", capture);
        assertNotNull(ws);

        // Send a message that routes to the "noResponse" route
        ws.sendText("{\"action\":\"noResponse\",\"data\":\"hello\"}", true).join();

        // Should NOT receive a response — expect a timeout
        assertThrows(TimeoutException.class, () -> {
            capture.getResponse(3, TimeUnit.SECONDS);
        }, "Should NOT receive a response when routeResponseSelectionExpression is null");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Test 3: Body field extracted from Lambda response ────────────────────────────

    @Test
    @Order(30)
    void responseBodyExtractedFromLambdaResponse() throws Exception {
        // The "body" field is extracted from the Lambda JSON response
        // and sent to the client (not the full JSON)
        WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
        WebSocket ws = connectWebSocketWithListener(wsApiId, "test", capture);
        assertNotNull(ws);

        // Send a message that routes to the "withResponse" route
        ws.sendText("{\"action\":\"withResponse\",\"data\":\"test\"}", true).join();

        // Should receive exactly "extracted-body" (the body field value from the Lambda response)
        String response = capture.getResponse(15, TimeUnit.SECONDS);
        assertEquals("extracted-body", response,
                "Response should be the extracted 'body' field value from the Lambda JSON response");

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
        given().when().delete("/2015-03-31/functions/" + lambdaFnName);
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
