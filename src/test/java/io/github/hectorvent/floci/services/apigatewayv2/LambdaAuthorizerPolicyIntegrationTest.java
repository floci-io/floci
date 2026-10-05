package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class LambdaAuthorizerPolicyIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUTHORIZER_FUNCTION = "policy-authorizer";
    private static final String BACKEND_FUNCTION = "policy-backend";

    @InjectMock
    LambdaService lambdaService;

    @TestHTTPResource("/")
    URI baseUri;

    private String apiId;

    @AfterEach
    void deleteApi() {
        if (apiId != null) {
            given().delete("/v2/apis/" + apiId).then().statusCode(204);
        }
    }

    @ParameterizedTest(name = "HTTP: {0}")
    @MethodSource("io.github.hectorvent.floci.services.apigatewayv2.AuthorizerPolicyFixtures#policies")
    void httpRequestsEnforceAuthorizerPolicies(String scenario, String statements, int expectedStatus) {
        configureApi("HTTP");
        stubAuthorizer(statements);
        when(lambdaService.invoke(eq("us-east-1"), eq(BACKEND_FUNCTION), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(result("{\"statusCode\":200,\"body\":\"allowed\"}"));
        given().header("X-Forwarded-For", "198.51.100.42")
                .get("/execute-api/" + apiId + "/test/items").then().statusCode(expectedStatus);
        verify(lambdaService, times(expectedStatus == 200 ? 1 : 0))
                .invoke(eq("us-east-1"), eq(BACKEND_FUNCTION), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @ParameterizedTest(name = "WebSocket: {0}")
    @MethodSource("io.github.hectorvent.floci.services.apigatewayv2.AuthorizerPolicyFixtures#policies")
    void webSocketHandshakeEnforcesAuthorizerPolicies(String scenario, String statements, int expectedStatus) throws Exception {
        configureApi("WEBSOCKET");
        stubAuthorizer(statements);
        URI uri = URI.create(WebSocketTestSupport.buildWsUrl(baseUri, apiId, "test"));
        try (HttpClient client = HttpClient.newHttpClient()) {
            CompletableFuture<WebSocket> handshakeFuture = client.newWebSocketBuilder()
                    .header("X-Forwarded-For", "198.51.100.42")
                    .buildAsync(uri, new WebSocket.Listener() {});
            try {
                assertHandshake(handshakeFuture, expectedStatus);
            } finally {
                if (handshakeFuture.isDone() && !handshakeFuture.isCompletedExceptionally()) {
                    handshakeFuture.join().abort();
                } else {
                    handshakeFuture.cancel(true);
                }
            }
        }
    }

    private void assertHandshake(CompletableFuture<WebSocket> handshakeFuture, int expectedStatus) throws Exception {
        if (expectedStatus == 200) {
            WebSocket socket = handshakeFuture.get(10, TimeUnit.SECONDS);
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
            return;
        }
        ExecutionException exception = assertThrows(ExecutionException.class,
                () -> handshakeFuture.get(10, TimeUnit.SECONDS));
        WebSocketHandshakeException handshake = assertInstanceOf(WebSocketHandshakeException.class, exception.getCause());
        assertEquals(expectedStatus, handshake.getResponse().statusCode());
    }

    private void configureApi(String protocol) {
        apiId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "policy-evaluation", "protocolType", protocol,
                        "routeSelectionExpression", "$request.body.action"))
                .post("/v2/apis").then().statusCode(201).extract().path("apiId");
        String apiPath = "/v2/apis/" + apiId;
        given().contentType(ContentType.JSON).body(Map.of("stageName", "test"))
                .post(apiPath + "/stages").then().statusCode(201);
        String authorizerId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "policy", "authorizerType", "REQUEST", "authorizerPayloadFormatVersion", "2.0",
                        "authorizerUri", "arn:aws:lambda:us-east-1:000000000000:function:" + AUTHORIZER_FUNCTION))
                .post(apiPath + "/authorizers").then().statusCode(201).extract().path("authorizerId");
        boolean webSocket = "WEBSOCKET".equals(protocol);
        Map<String, String> integration = webSocket ? Map.of("integrationType", "MOCK")
                : Map.of("integrationType", "AWS_PROXY", "payloadFormatVersion", "2.0",
                        "integrationUri", "arn:aws:lambda:us-east-1:000000000000:function:" + BACKEND_FUNCTION);
        String integrationId = given().contentType(ContentType.JSON).body(integration)
                .post(apiPath + "/integrations").then().statusCode(201).extract().path("integrationId");
        given().contentType(ContentType.JSON)
                .body(Map.of("routeKey", webSocket ? "$connect" : "GET /items", "authorizationType", "CUSTOM",
                        "authorizerId", authorizerId, "target", "integrations/" + integrationId))
                .post(apiPath + "/routes").then().statusCode(201);
    }

    private void stubAuthorizer(String statements) {
        when(lambdaService.invoke(eq("us-east-1"), eq(AUTHORIZER_FUNCTION), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    JsonNode event = MAPPER.readTree((byte[]) invocation.getArgument(2));
                    String arn = event.has("routeArn") ? event.path("routeArn").asText() : event.path("methodArn").asText();
                    String suffix = event.has("routeArn") ? "/GET/items" : "/$connect";
                    assertEquals("arn:aws:execute-api:us-east-1:000000000000:" + apiId + "/test" + suffix, arn);
                    return result("{\"principalId\":\"user\",\"policyDocument\":{\"Version\":\"2012-10-17\",\"Statement\":"
                            + AuthorizerPolicyFixtures.renderStatements(statements, arn) + "}}");
                });
    }

    private InvokeResult result(String payload) {
        return new InvokeResult(200, null, payload.getBytes(StandardCharsets.UTF_8), null, "request");
    }
}
