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
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for WebSocket AWS, HTTP_PROXY, and HTTP integration types.
 *
 * <ul>
 *   <li>AWS integration: Lambda invocation with VTL request/response template transformation</li>
 *   <li>HTTP_PROXY integration: passthrough HTTP POST forwarding, no VTL transformation</li>
 *   <li>HTTP integration: HTTP POST forwarding with VTL request/response template transformation</li>
 * </ul>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebSocketAwsHttpIntegrationTest {

    @io.quarkus.test.common.http.TestHTTPResource("/")
    URI baseUri;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -- AWS integration type --
    private static String awsApiId;
    private static String awsFnName = "ws-aws-integ-fn";
    private static String awsConnectFnName = "ws-aws-integ-connect-fn";

    // -- HTTP_PROXY integration type --
    private static String httpProxyApiId;
    private static String httpProxyConnectFnName = "ws-http-proxy-connect-fn";

    // -- HTTP integration type --
    private static String httpApiId;
    private static String httpConnectFnName = "ws-http-integ-connect-fn";

    // -- Stage variable test --
    private static String stageVarHttpApiId;
    private static String stageVarHttpFnName = "ws-sv-http-connect-fn";

    // ──────────────────────────── Setup: Lambda Functions ────────────────────────────

    @Test
    @Order(1)
    void setupLambdaFunctions() throws Exception {
        // Lambda for AWS integration: echoes the received payload wrapped in a response
        String awsZip = WebSocketTestSupport.createLambdaZip("""
                exports.handler = async (event) => {
                    // For AWS integration, the event is the VTL-transformed payload
                    return { transformed: true, input: event };
                };
                """);
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(awsFnName, awsZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        // Connect handler (simple allow)
        String connectZip = WebSocketTestSupport.createLambdaZip(
                "exports.handler = async (event) => ({ statusCode: 200, body: 'connected' });");
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(awsConnectFnName, connectZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(httpProxyConnectFnName, connectZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(httpConnectFnName, connectZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(stageVarHttpFnName, connectZip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        // Prewarm all functions
        for (String fn : new String[]{awsFnName, awsConnectFnName, httpProxyConnectFnName, httpConnectFnName, stageVarHttpFnName}) {
            given().contentType(ContentType.JSON).body("{}")
                    .when().post("/2015-03-31/functions/" + fn + "/invocations")
                    .then().statusCode(200);
        }
    }

    // ──────────────────────────── AWS Integration Type Tests ────────────────────────────

    @Test
    @Order(10)
    void setupAwsIntegrationApi() {
        awsApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ws-aws-integ-test","protocolType":"WEBSOCKET","routeSelectionExpression":"$request.body.action"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test"}
                        """)
                .when().post("/v2/apis/" + awsApiId + "/stages")
                .then()
                .statusCode(201);

        String connectIntegId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST"}
                        """.formatted(awsConnectFnName))
                .when().post("/v2/apis/" + awsApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        String awsIntegId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST","requestTemplates":{"$default":"{\\"wrapped\\": true, \\"originalBody\\": $input.body}"},"responseTemplates":{"$default":"{\\"result\\": $input.body}"},"templateSelectionExpression":"$default"}
                        """.formatted(awsFnName))
                .when().post("/v2/apis/" + awsApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$connect","target":"integrations/%s"}
                        """.formatted(connectIntegId))
                .when().post("/v2/apis/" + awsApiId + "/routes")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$default","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                        """.formatted(awsIntegId))
                .when().post("/v2/apis/" + awsApiId + "/routes")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(11)
    void awsIntegrationInvokesLambdaWithTemplateTransformation() throws Exception {
        WebSocketTestSupport.MultiMessageCapture capture = new WebSocketTestSupport.MultiMessageCapture();
        WebSocket ws = connectWebSocket(awsApiId, "test", capture);
        assertNotNull(ws, "WebSocket connection should succeed");

        ws.sendText("{\"action\":\"test\",\"data\":\"hello-aws\"}", true).join();
        String response = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive response from AWS integration");

        JsonNode responseNode = MAPPER.readTree(response);
        assertTrue(responseNode.has("result"), "Response should have 'result' field from response template");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    @Test
    @Order(12)
    void awsIntegrationWithoutTemplatesPassesThrough() throws Exception {
        WebSocketTestSupport.MultiMessageCapture capture = new WebSocketTestSupport.MultiMessageCapture();
        WebSocket ws = connectWebSocket(awsApiId, "test", capture);
        assertNotNull(ws, "WebSocket connection should succeed");

        ws.sendText("{\"action\":\"verify\",\"value\":42}", true).join();
        String response = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive response");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── HTTP_PROXY Integration Type Tests ────────────────────────────

    @Test
    @Order(20)
    void setupHttpProxyIntegrationApi() {
        httpProxyApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ws-http-proxy-test","protocolType":"WEBSOCKET","routeSelectionExpression":"$request.body.action"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test"}
                        """)
                .when().post("/v2/apis/" + httpProxyApiId + "/stages")
                .then()
                .statusCode(201);

        String connectIntegId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST"}
                        """.formatted(httpProxyConnectFnName))
                .when().post("/v2/apis/" + httpProxyApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        String httpTargetUrl = baseUri.toString() + "2015-03-31/functions/" + awsFnName + "/invocations";
        String httpProxyIntegId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"HTTP_PROXY","integrationUri":"%s","integrationMethod":"POST"}
                        """.formatted(httpTargetUrl))
                .when().post("/v2/apis/" + httpProxyApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$connect","target":"integrations/%s"}
                        """.formatted(connectIntegId))
                .when().post("/v2/apis/" + httpProxyApiId + "/routes")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$default","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                        """.formatted(httpProxyIntegId))
                .when().post("/v2/apis/" + httpProxyApiId + "/routes")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(21)
    void httpProxyIntegrationForwardsEventAsPost() throws Exception {
        WebSocketTestSupport.MultiMessageCapture capture = new WebSocketTestSupport.MultiMessageCapture();
        WebSocket ws = connectWebSocket(httpProxyApiId, "test", capture);
        assertNotNull(ws, "WebSocket connection should succeed");

        ws.sendText("{\"action\":\"test\",\"data\":\"hello-http-proxy\"}", true).join();
        String response = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive response from HTTP_PROXY integration");

        JsonNode responseNode = MAPPER.readTree(response);
        assertTrue(responseNode.has("transformed") || responseNode.has("input"),
                "Response should contain Lambda output forwarded via HTTP_PROXY");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    @Test
    @Order(22)
    void httpProxyIntegrationNoTemplateTransformation() throws Exception {
        WebSocketTestSupport.MultiMessageCapture capture = new WebSocketTestSupport.MultiMessageCapture();
        WebSocket ws = connectWebSocket(httpProxyApiId, "test", capture);
        assertNotNull(ws, "WebSocket connection should succeed");

        ws.sendText("{\"action\":\"raw\",\"payload\":\"no-transform\"}", true).join();
        String response = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive response without template transformation");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── HTTP Integration Type Tests ────────────────────────────

    @Test
    @Order(30)
    void setupHttpIntegrationApi() {
        httpApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ws-http-integ-test","protocolType":"WEBSOCKET","routeSelectionExpression":"$request.body.action"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test"}
                        """)
                .when().post("/v2/apis/" + httpApiId + "/stages")
                .then()
                .statusCode(201);

        String connectIntegId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST"}
                        """.formatted(httpConnectFnName))
                .when().post("/v2/apis/" + httpApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        String httpTargetUrl = baseUri.toString() + "2015-03-31/functions/" + awsFnName + "/invocations";
        String httpIntegId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"HTTP","integrationUri":"%s","integrationMethod":"POST","requestTemplates":{"$default":"{\\"httpWrapped\\": true, \\"body\\": $input.body}"},"responseTemplates":{"$default":"{\\"httpResult\\": $input.body}"},"templateSelectionExpression":"$default"}
                        """.formatted(httpTargetUrl))
                .when().post("/v2/apis/" + httpApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$connect","target":"integrations/%s"}
                        """.formatted(connectIntegId))
                .when().post("/v2/apis/" + httpApiId + "/routes")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$default","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                        """.formatted(httpIntegId))
                .when().post("/v2/apis/" + httpApiId + "/routes")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(31)
    void httpIntegrationAppliesRequestAndResponseTemplates() throws Exception {
        WebSocketTestSupport.MultiMessageCapture capture = new WebSocketTestSupport.MultiMessageCapture();
        WebSocket ws = connectWebSocket(httpApiId, "test", capture);
        assertNotNull(ws, "WebSocket connection should succeed");

        ws.sendText("{\"action\":\"test\",\"data\":\"hello-http\"}", true).join();
        String response = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive response from HTTP integration");

        JsonNode responseNode = MAPPER.readTree(response);
        assertTrue(responseNode.has("httpResult"),
                "Response should have 'httpResult' field from response template");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    @Test
    @Order(32)
    void httpIntegrationForwardsToCorrectEndpoint() throws Exception {
        WebSocketTestSupport.MultiMessageCapture capture = new WebSocketTestSupport.MultiMessageCapture();
        WebSocket ws = connectWebSocket(httpApiId, "test", capture);
        assertNotNull(ws, "WebSocket connection should succeed");

        ws.sendText("{\"action\":\"verify\",\"value\":\"http-forward\"}", true).join();
        String response = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive response from HTTP endpoint");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Stage Variable Substitution in HTTP URI ────────────────────────────

    @Test
    @Order(40)
    void setupStageVariableHttpApi() {
        stageVarHttpApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ws-sv-http-test","protocolType":"WEBSOCKET","routeSelectionExpression":"$request.body.action"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        String httpTargetUrl = baseUri.toString() + "2015-03-31/functions/" + awsFnName + "/invocations";
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test","stageVariables":{"httpTarget":"%s"}}
                        """.formatted(httpTargetUrl))
                .when().post("/v2/apis/" + stageVarHttpApiId + "/stages")
                .then()
                .statusCode(201);

        String connectIntegId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST"}
                        """.formatted(stageVarHttpFnName))
                .when().post("/v2/apis/" + stageVarHttpApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        String httpProxyIntegId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"HTTP_PROXY","integrationUri":"${stageVariables.httpTarget}","integrationMethod":"POST"}
                        """)
                .when().post("/v2/apis/" + stageVarHttpApiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$connect","target":"integrations/%s"}
                        """.formatted(connectIntegId))
                .when().post("/v2/apis/" + stageVarHttpApiId + "/routes")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$default","target":"integrations/%s","routeResponseSelectionExpression":"$default"}
                        """.formatted(httpProxyIntegId))
                .when().post("/v2/apis/" + stageVarHttpApiId + "/routes")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(41)
    void httpProxyIntegrationSubstitutesStageVariablesInUri() throws Exception {
        WebSocketTestSupport.MultiMessageCapture capture = new WebSocketTestSupport.MultiMessageCapture();
        WebSocket ws = connectWebSocket(stageVarHttpApiId, "test", capture);
        assertNotNull(ws, "WebSocket connection should succeed with stage variable URI");

        ws.sendText("{\"action\":\"test\",\"data\":\"stage-var-http\"}", true).join();
        String response = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive response after stage variable substitution in HTTP URI");

        JsonNode responseNode = MAPPER.readTree(response);
        assertTrue(responseNode.has("transformed") || responseNode.has("input"),
                "Response should come from Lambda invoked via stage-variable-resolved HTTP URI");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(999)
    void cleanup() throws Exception {
        if (awsApiId != null) {
            given().when().delete("/v2/apis/" + awsApiId);
        }
        if (httpProxyApiId != null) {
            given().when().delete("/v2/apis/" + httpProxyApiId);
        }
        if (httpApiId != null) {
            given().when().delete("/v2/apis/" + httpApiId);
        }
        if (stageVarHttpApiId != null) {
            given().when().delete("/v2/apis/" + stageVarHttpApiId);
        }

        WebSocketTestSupport.deleteFunctions(awsFnName, awsConnectFnName, httpProxyConnectFnName,
                httpConnectFnName, stageVarHttpFnName);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private WebSocket connectWebSocket(String apiId, String stageName,
                                       WebSocketTestSupport.MultiMessageCapture capture) throws Exception {
        String wsUrl = WebSocketTestSupport.buildWsUrl(baseUri, apiId, stageName);
        HttpClient client = HttpClient.newHttpClient();
        return client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), capture)
                .get(60, TimeUnit.SECONDS);
    }
}
