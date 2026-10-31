package io.github.hectorvent.floci.services.apigatewayv2;

import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.services.apigatewayv2.LambdaAuthorizerApiFixtures.BACKEND;
import static io.github.hectorvent.floci.services.apigatewayv2.LambdaAuthorizerApiFixtures.result;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class LambdaAuthorizerPolicyIntegrationTest {

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
        LambdaAuthorizerApiFixtures.stubBackend(lambdaService);
        given().header("X-Forwarded-For", "198.51.100.42")
                .get("/execute-api/" + apiId + "/test/items").then().statusCode(expectedStatus);
        verify(lambdaService, times(expectedStatus == 200 ? 1 : 0))
                .invokeTarget(eq("us-east-1"), eq(BACKEND), any(byte[].class), eq(InvocationType.RequestResponse));
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
        apiId = LambdaAuthorizerApiFixtures.createApi(protocol, "test", Map.of(), Map.of("authorizerPayloadFormatVersion", "2.0"));
    }

    private void stubAuthorizer(String statements) {
        LambdaAuthorizerApiFixtures.stubAuthorizer(lambdaService, event -> {
            String arn = event.has("routeArn") ? event.path("routeArn").asText() : event.path("methodArn").asText();
            String suffix = event.has("routeArn") ? "/GET/items" : "/$connect";
            assertEquals("arn:aws:execute-api:us-east-1:000000000000:" + apiId + "/test" + suffix, arn);
            return result("{\"principalId\":\"user\",\"policyDocument\":{\"Version\":\"2012-10-17\",\"Statement\":"
                    + AuthorizerPolicyFixtures.renderStatements(statements, arn) + "}}");
        });
    }
}
