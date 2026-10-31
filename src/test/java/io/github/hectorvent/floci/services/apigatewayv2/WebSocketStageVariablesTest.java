package io.github.hectorvent.floci.services.apigatewayv2;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
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
 * Integration tests for WebSocket stage variable substitution in integration URIs.
 *
 * Stage variable references in integrationUri are substituted with configured values.
 * Undefined stage variable references are replaced with empty string.
 * Multiple stage variable references in a single URI are all substituted.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebSocketStageVariablesTest {

    @TestHTTPResource("/")
    URI baseUri;

    private static String wsApiId;
    private static String stageVarFnName = "ws-stage-var-fn";

    // ──────────────────────────── Setup ────────────────────────────

    @Test
    @Order(1)
    void setupLambdaFunction() throws Exception {
        // Create a Lambda function that returns a fixed response to verify it was invoked
        String zip = WebSocketTestSupport.createLambdaZip(
                "exports.handler = async (event) => ({ statusCode: 200, body: JSON.stringify({handler:'stage-var-success', routeKey: (event.requestContext || {}).routeKey || 'none'}) });");
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(stageVarFnName, zip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        // Prewarm the Lambda function
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/2015-03-31/functions/" + stageVarFnName + "/invocations")
                .then().statusCode(200);
    }

    @Test
    @Order(2)
    void setupWebSocketApi() {
        // Create a WEBSOCKET API
        wsApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ws-stage-var-test","protocolType":"WEBSOCKET","routeSelectionExpression":"$request.body.action"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");
    }

    // ──────────────────────────── Test 1: Stage variable substituted in Lambda URI ────────────────────────────

    @Test
    @Order(10)
    void stageVariableSubstitutedInLambdaUri() throws Exception {
        // Stage variable in integration URI is substituted with the configured value
        // before Lambda invocation.

        // Create a stage with stageVariables containing the function name
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"stagevar1","stageVariables":{"functionName":"ws-stage-var-fn"}}
                        """)
                .when().post("/v2/apis/" + wsApiId + "/stages")
                .then()
                .statusCode(201);

        // Create an integration with a stage variable reference in the URI
        String integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:${stageVariables.functionName}/invocations","integrationMethod":"POST"}
                        """)
                .when().post("/v2/apis/" + wsApiId + "/integrations")
                .then()
                .statusCode(201)
                .body("integrationId", notNullValue())
                .extract().path("integrationId");

        // Create a $default route with routeResponseSelectionExpression so we get a response back
        String routeId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$default","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");

        try {
            // Connect and send a message
            WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
            WebSocket ws = connectWebSocketWithListener(wsApiId, "stagevar1", capture);
            assertNotNull(ws, "WebSocket connection should succeed");

            ws.sendText("{\"action\":\"test\",\"data\":\"stage-var-test\"}", true).join();

            // Wait for response — if stage variable substitution works, the Lambda is invoked successfully
            String response = capture.getResponse(15, TimeUnit.SECONDS);
            assertNotNull(response, "Should receive a response when stage variable is substituted correctly");
            assertTrue(response.contains("stage-var-success"),
                    "Response should indicate the Lambda was invoked successfully via stage variable substitution, got: " + response);

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            Thread.sleep(500);
        } finally {
            // Cleanup route
            given().when().delete("/v2/apis/" + wsApiId + "/routes/" + routeId)
                    .then().statusCode(204);
        }
    }

    // ──────────────────────────── Test 2: Undefined stage variable substituted with empty string ────────────────────────────

    @Test
    @Order(20)
    void undefinedStageVariableSubstitutedWithEmpty() throws Exception {
        // Undefined stage variable reference is replaced with empty string.
        // This results in an invalid/empty function name, so the Lambda invocation fails
        // and no successful response is returned to the client.

        // Create a stage WITHOUT the referenced variable
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"stagevar2","stageVariables":{"otherVar":"someValue"}}
                        """)
                .when().post("/v2/apis/" + wsApiId + "/stages")
                .then()
                .statusCode(201);

        // Create an integration referencing a variable that doesn't exist in the stage
        String missingVarIntegrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:${stageVariables.missingVar}/invocations","integrationMethod":"POST"}
                        """)
                .when().post("/v2/apis/" + wsApiId + "/integrations")
                .then()
                .statusCode(201)
                .body("integrationId", notNullValue())
                .extract().path("integrationId");

        // Create a $default route with routeResponseSelectionExpression
        String routeId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$default","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                        """.formatted(missingVarIntegrationId))
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");

        try {
            // Connect and send a message
            WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
            WebSocket ws = connectWebSocketWithListener(wsApiId, "stagevar2", capture);
            assertNotNull(ws, "WebSocket connection should succeed");

            ws.sendText("{\"action\":\"test\",\"data\":\"missing-var-test\"}", true).join();

            String response = capture.getResponse(3, TimeUnit.SECONDS);
            assertEquals("Internal server error", JsonPath.from(response).getString("message"));

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            Thread.sleep(500);
        } finally {
            // Cleanup route
            given().when().delete("/v2/apis/" + wsApiId + "/routes/" + routeId)
                    .then().statusCode(204);
        }
    }

    // ──────────────────────────── Test 3: Multiple stage variables substituted ────────────────────────────

    @Test
    @Order(30)
    void multipleStageVariablesSubstituted() throws Exception {
        // Multiple stage variable references in a single URI are all substituted.

        // Create a stage with multiple variables that together form the function name
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"stagevar3","stageVariables":{"prefix":"ws","suffix":"stage-var-fn"}}
                        """)
                .when().post("/v2/apis/" + wsApiId + "/stages")
                .then()
                .statusCode(201);

        // Create an integration with multiple stage variable references
        // After substitution: "...function:ws-stage-var-fn/invocations"
        String multiVarIntegrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:${stageVariables.prefix}-${stageVariables.suffix}/invocations","integrationMethod":"POST"}
                        """)
                .when().post("/v2/apis/" + wsApiId + "/integrations")
                .then()
                .statusCode(201)
                .body("integrationId", notNullValue())
                .extract().path("integrationId");

        // Create a $default route with routeResponseSelectionExpression
        String routeId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$default","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                        """.formatted(multiVarIntegrationId))
                .when().post("/v2/apis/" + wsApiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");

        try {
            // Connect and send a message
            WebSocketTestSupport.MessageCapture capture = new WebSocketTestSupport.MessageCapture();
            WebSocket ws = connectWebSocketWithListener(wsApiId, "stagevar3", capture);
            assertNotNull(ws, "WebSocket connection should succeed");

            ws.sendText("{\"action\":\"test\",\"data\":\"multi-var-test\"}", true).join();

            // After substitution, the URI becomes "...function:ws-stage-var-fn/invocations"
            // which should invoke the Lambda successfully
            String response = capture.getResponse(15, TimeUnit.SECONDS);
            assertNotNull(response, "Should receive a response when multiple stage variables are substituted");
            assertTrue(response.contains("stage-var-success"),
                    "Response should indicate the Lambda was invoked successfully via multiple stage variable substitution, got: " + response);

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            Thread.sleep(500);
        } finally {
            // Cleanup route
            given().when().delete("/v2/apis/" + wsApiId + "/routes/" + routeId)
                    .then().statusCode(204);
        }
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(999)
    void cleanup() {
        // Delete API (cascades integrations, routes, stages)
        if (wsApiId != null) {
            given().when().delete("/v2/apis/" + wsApiId);
        }

        // Delete Lambda function
        given().when().delete("/2015-03-31/functions/" + stageVarFnName);
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
