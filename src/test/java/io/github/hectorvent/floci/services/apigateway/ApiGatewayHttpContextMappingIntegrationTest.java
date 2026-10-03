package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@QuarkusTest
class ApiGatewayHttpContextMappingIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FUNCTION = "http-context-authorizer";
    private static final String AUTHORIZER_URI = "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
            + "arn:aws:lambda:us-east-1:000000000000:function:" + FUNCTION + "/invocations";
    private static final AtomicInteger backendRequests = new AtomicInteger();
    private static HttpServer backend;

    @InjectMock
    LambdaService lambdaService;

    private String apiId;
    private String resourceId;
    private String methodPath;

    @BeforeAll
    static void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", ApiGatewayHttpContextMappingIntegrationTest::echo);
        backend.start();
    }

    @AfterAll
    static void stopBackend() {
        backend.stop(0);
    }

    private static void echo(HttpExchange exchange) throws IOException {
        backendRequests.incrementAndGet();
        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values));
        response.put("headers", headers);
        response.put("path", exchange.getRequestURI().getPath());
        response.put("query", exchange.getRequestURI().getQuery());
        response.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] payload = MAPPER.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    @BeforeEach
    void createApi() {
        backendRequests.set(0);
        apiId = given().contentType(ContentType.JSON).body(Map.of("name", "http-context-mapping"))
                .post("/restapis").then().statusCode(201).extract().path("id");
        String rootId = given().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        resourceId = given().contentType(ContentType.JSON).body(Map.of("pathPart", "{proxy+}"))
                .post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");
        methodPath = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/";
    }

    @AfterEach
    void deleteApi() {
        if (apiId != null) {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_PROXY", "HTTP"})
    void forwardsVerifiedAuthorizerScalarsAndRequestContext(String type) throws Exception {
        configureAuthorizer(0);
        configureIntegration(type, "GET");
        deploy();

        for (String endpoint : List.of("/execute-api/" + apiId + "/test/",
                "/restapis/" + apiId + "/test/_user_request_/")) {
            JsonNode response = MAPPER.readTree(given().header("Authorization", "Bearer allowed")
                    .header("x-user-claims", "forged", "also-forged")
                    .header("x-missing", "forged-missing")
                    .header("x-object", "forged-object")
                    .header("x-request-id", "client-id")
                    .header("X-Source", "client-value")
                    .get(endpoint + "orders/42?client=acme")
                    .then().statusCode(200).extract().asByteArray());
            assertMappedContext(response);
            assertEquals("/test/orders/42", response.path("headers").path("x-context-path").get(0).asText());
            assertEquals("GET", response.path("headers").path("x-context-method").get(0).asText());
            assertEquals(apiId, response.path("headers").path("x-context-api").get(0).asText());
            assertEquals(resourceId, response.path("headers").path("x-context-resource").get(0).asText());
            assertEquals("/users/verified-user/orders/42", response.path("path").asText());
            assertTrue(response.path("query").asText().contains("claims=verified-claims"));
            assertTrue(response.path("query").asText().contains("client=acme"));
            assertEquals("client-value", response.path("headers").path("x-from-client").get(0).asText());
            assertEquals("static-value", response.path("headers").path("x-static").get(0).asText());
            if ("HTTP".equals(type)) {
                assertEquals(response.path("headers").path("x-request-id").get(0).asText(),
                        MAPPER.readTree(response.path("body").asText()).path("requestId").asText());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_PROXY", "HTTP"})
    void missingAuthorizerClaimDoesNotFallBackToClientHeader(String type) throws Exception {
        configureAuthorizer(0, Map.of());
        configureIntegration(type, "GET");
        deploy();

        for (String endpoint : List.of("/execute-api/" + apiId + "/test/",
                "/restapis/" + apiId + "/test/_user_request_/")) {
            JsonNode response = MAPPER.readTree(given().header("Authorization", "Bearer allowed")
                    .header("x-user-claims", "forged", "also-forged")
                    .get(endpoint + "orders/42")
                    .then().statusCode(200).extract().asByteArray());
            assertFalse(response.path("headers").has("x-user-claims"));
            assertEquals(MAPPER.valueToTree(List.of("verified-user")), response.path("headers").path("x-principal"));
        }
    }

    @Test
    void missingMethodRequestMappingPreservesInboundProxyHeader() throws Exception {
        configureAuthorizer(0);
        configureIntegration("HTTP_PROXY", "GET");
        deploy();
        JsonNode response = MAPPER.readTree(given().header("Authorization", "Bearer allowed")
                .header("X-Unresolved", "client-value")
                .get("/execute-api/" + apiId + "/test/orders/42")
                .then().statusCode(200).extract().asByteArray());
        assertEquals(MAPPER.valueToTree(List.of("client-value")), response.path("headers").path("x-unresolved"));
    }

    @ParameterizedTest
    @CsvSource({"HTTP_PROXY,true,true", "HTTP_PROXY,true,false", "HTTP_PROXY,false,true", "HTTP_PROXY,false,false",
            "HTTP,true,true", "HTTP,true,false", "HTTP,false,true", "HTTP,false,false"})
    void requestMappingsReadOriginalInputs(String type, boolean hasClaim, boolean copiesFirst) throws Exception {
        configureAuthorizer(0, hasClaim ? Map.of("userClaims", "verified-claims") : Map.of());
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("integration.request.header.X-User-Claims", "context.authorizer.userClaims");
        replacements.put("integration.request.header.X-Principal", "context.authorizer.principalId");
        replacements.put("integration.request.querystring.claims", "'mapped-query'");
        replacements.put("integration.request.path.principal", "context.authorizer.principalId");
        replacements.put("integration.request.path.proxy", "'mapped-path'");
        Map<String, String> copies = new LinkedHashMap<>();
        copies.put("integration.request.header.X-Original-Claims", "method.request.header.x-user-claims");
        copies.put("integration.request.header.X-Original-Query", "method.request.querystring.claims");
        copies.put("integration.request.header.X-Original-Path", "method.request.path.proxy");
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.putAll(copiesFirst ? copies : replacements);
        parameters.putAll(copiesFirst ? replacements : copies);
        configureIntegration(type, "GET", parameters);
        deploy();

        for (String endpoint : List.of("/execute-api/" + apiId + "/test/",
                "/restapis/" + apiId + "/test/_user_request_/")) {
            JsonNode response = MAPPER.readTree(given().header("Authorization", "Bearer allowed")
                    .header("x-user-claims", "client-claims")
                    .get(endpoint + "orders/42?claims=client-query")
                    .then().statusCode(200).extract().asByteArray());
            JsonNode headers = response.path("headers");
            assertEquals("client-claims", headers.path("x-original-claims").path(0).asText());
            assertEquals("client-query", headers.path("x-original-query").path(0).asText());
            assertEquals("orders/42", headers.path("x-original-path").path(0).asText());
            assertEquals("/users/verified-user/mapped-path", response.path("path").asText());
            assertEquals("claims=mapped-query", response.path("query").asText());
            if (hasClaim) {
                assertEquals(MAPPER.valueToTree(List.of("verified-claims")), headers.path("x-user-claims"));
            } else {
                assertFalse(headers.has("x-user-claims"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_PROXY", "HTTP"})
    void retainsContextOnRepeatedRequestsWithAuthorizerTtl(String type) throws Exception {
        configureAuthorizer(300);
        configureIntegration(type, "GET");
        deploy();
        String previousRequestId = null;
        for (int request = 0; request < 2; request++) {
            JsonNode response = MAPPER.readTree(given().header("Authorization", "Bearer allowed")
                    .header("X-User-Claims", "forged")
                    .get("/execute-api/" + apiId + "/test/orders/42")
                    .then().statusCode(200).extract().asByteArray());
            assertMappedContext(response);
            String requestId = response.path("headers").path("x-request-id").get(0).asText();
            assertNotEquals(previousRequestId, requestId);
            previousRequestId = requestId;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_PROXY", "HTTP"})
    void rejectedTokensNeverReachBackend(String type) throws Exception {
        configureAuthorizer(0);
        configureIntegration(type, "GET");
        deploy();
        for (String token : List.of("", "Bearer forged")) {
            RequestSpecification request = given().header("X-User-Claims", "forged");
            if (!token.isEmpty()) {
                request.header("Authorization", token);
            }
            request.get("/execute-api/" + apiId + "/test/orders/42").then().statusCode(403);
        }
        assertEquals(0, backendRequests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_PROXY", "HTTP"})
    void unauthenticatedOptionsHasRequestContextWithoutBearerClaims(String type) throws Exception {
        configureAuthorizer(0);
        configureIntegration(type, "GET");
        given().contentType(ContentType.JSON).body(Map.of("authorizationType", "NONE"))
                .put(methodPath + "OPTIONS").then().statusCode(201);
        configureIntegration(type, "OPTIONS");
        deploy();
        JsonNode response = MAPPER.readTree(given().header("Authorization", "Bearer forged")
                .header("x-user-claims", "forged", "also-forged")
                .options("/execute-api/" + apiId + "/test/orders/42")
                .then().statusCode(200).extract().asByteArray());
        assertFalse(response.path("headers").has("x-user-claims"));
        assertFalse(response.path("headers").has("x-principal"));
        assertFalse(response.path("headers").has("x-missing"));
        assertFalse(response.path("headers").has("x-object"));
        assertEquals("OPTIONS", response.path("headers").path("x-context-method").path(0).asText());
        assertDoesNotThrow(() -> UUID.fromString(response.path("headers").path("x-request-id").path(0).asText()));
    }

    private void assertMappedContext(JsonNode response) {
        JsonNode headers = response.path("headers");
        assertEquals(MAPPER.valueToTree(List.of("verified-claims")), headers.path("x-user-claims"));
        assertEquals(MAPPER.valueToTree(List.of("verified-user")), headers.path("x-principal"));
        assertEquals(MAPPER.valueToTree(List.of("123")), headers.path("x-number"));
        assertEquals(MAPPER.valueToTree(List.of("true")), headers.path("x-boolean"));
        assertEquals(MAPPER.valueToTree(List.of("test")), headers.path("x-context-stage"));
        assertFalse(headers.has("x-missing"));
        assertFalse(headers.has("x-object"));
        assertEquals(1, headers.path("x-request-id").size());
        assertDoesNotThrow(() -> UUID.fromString(headers.path("x-request-id").get(0).asText()));
    }

    private void configureAuthorizer(int ttl) throws Exception {
        configureAuthorizer(ttl, Map.of("userClaims", "verified-claims", "principalId", "forged-principal",
                "numberKey", 123, "booleanKey", true, "objectKey", Map.of("nested", "value")));
    }

    private void configureAuthorizer(int ttl, Map<String, Object> context) throws Exception {
        String authorizerId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "claims", "type", "TOKEN", "authorizerUri", AUTHORIZER_URI,
                        "identitySource", "method.request.header.Authorization", "authorizerResultTtlInSeconds", ttl))
                .post("/restapis/" + apiId + "/authorizers")
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON)
                .body(Map.of("authorizationType", "CUSTOM", "authorizerId", authorizerId))
                .put(methodPath + "GET").then().statusCode(201);
        when(lambdaService.invoke(eq("us-east-1"), eq(FUNCTION), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    JsonNode event = MAPPER.readTree((byte[]) invocation.getArgument(2));
                    String effect = "Bearer allowed".equals(event.path("authorizationToken").asText()) ? "Allow" : "Deny";
                    byte[] payload = MAPPER.writeValueAsBytes(Map.of("principalId", "verified-user",
                            "policyDocument", Map.of("Version", "2012-10-17", "Statement", List.of(Map.of(
                                    "Action", "execute-api:Invoke", "Effect", effect, "Resource", "*"))),
                            "context", context));
                    return new InvokeResult(200, null, payload, null, "authorizer-request");
                });
    }

    private void configureIntegration(String type, String method) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("integration.request.header.X-User-Claims", "context.authorizer.userClaims");
        parameters.put("integration.request.header.X-Principal", "context.authorizer.principalId");
        parameters.put("integration.request.header.X-Number", "context.authorizer.numberKey");
        parameters.put("integration.request.header.X-Boolean", "context.authorizer.booleanKey");
        parameters.put("integration.request.header.X-Missing", "context.authorizer.missing");
        parameters.put("integration.request.header.X-Object", "context.authorizer.objectKey");
        parameters.put("integration.request.header.X-Request-Id", "context.requestId");
        parameters.put("integration.request.header.X-Context-Stage", "context.stage");
        parameters.put("integration.request.header.X-Context-Path", "context.path");
        parameters.put("integration.request.header.X-Context-Method", "context.httpMethod");
        parameters.put("integration.request.header.X-Context-Api", "context.apiId");
        parameters.put("integration.request.header.X-Context-Resource", "context.resourceId");
        parameters.put("integration.request.header.X-From-Client", "method.request.header.X-Source");
        parameters.put("integration.request.header.X-Unresolved", "method.request.querystring.missing");
        parameters.put("integration.request.header.X-Static", "'static-value'");
        parameters.put("integration.request.querystring.claims", "context.authorizer.userClaims");
        parameters.put("integration.request.querystring.client", "method.request.querystring.client");
        parameters.put("integration.request.path.principal", "context.authorizer.principalId");
        parameters.put("integration.request.path.proxy", "method.request.path.proxy");
        configureIntegration(type, method, parameters);
    }

    private void configureIntegration(String type, String method, Map<String, String> parameters) {
        // POST makes the HTTP integration's rendered request body observable at the echo backend.
        String integrationMethod = "HTTP".equals(type) && "GET".equals(method) ? "POST" : method;
        given().contentType(ContentType.JSON)
                .body(Map.of("type", type, "httpMethod", integrationMethod,
                        "uri", "http://127.0.0.1:" + backend.getAddress().getPort() + "/users/{principal}/{proxy}",
                        "requestParameters", parameters, "requestTemplates", Map.of("application/json",
                                "{\"requestId\":\"$context.requestId\"}")))
                .put(methodPath + method + "/integration").then().statusCode(201);
    }

    private void deploy() {
        given().contentType(ContentType.JSON).body(Map.of("stageName", "test"))
                .post("/restapis/" + apiId + "/deployments").then().statusCode(201);
    }
}
