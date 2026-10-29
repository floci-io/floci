package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.services.apigatewayv2.LambdaAuthorizerApiFixtures.AUTHORIZER;
import static io.github.hectorvent.floci.services.apigatewayv2.LambdaAuthorizerApiFixtures.result;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

@QuarkusTest
class HttpProxyLambdaContextMappingIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicInteger backendCalls = new AtomicInteger();
    private HttpServer backend;
    private String apiId;
    private String routeId;
    private String payloadFormat = "2.0";
    private boolean allowed = true;
    private ObjectNode context;

    @InjectMock LambdaService lambdaService;

    @BeforeEach
    void startBackendAndStubAuthorizer() throws Exception {
        context = MAPPER.createObjectNode().put("tenantId", "2").put("count", 7).put("active", true);
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            backendCalls.incrementAndGet();
            ObjectNode request = MAPPER.createObjectNode();
            for (String header : List.of("x-tenant", "x-count", "x-active", "x-missing", "x-static", "x-request-id")) {
                request.put(header, exchange.getRequestHeaders().getFirst(header));
            }
            request.put("path", exchange.getRequestURI().getPath());
            request.put("query", exchange.getRequestURI().getRawQuery());
            received.set(request);
            byte[] bytes = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        backend.start();
        LambdaAuthorizerApiFixtures.stubAuthorizer(lambdaService, event -> {
            ObjectNode response = MAPPER.createObjectNode();
            if ("2.0".equals(payloadFormat)) {
                response.put("isAuthorized", allowed);
            } else {
                response.put("principalId", "verified-user");
                ObjectNode policy = response.putObject("policyDocument").put("Version", "2012-10-17");
                policy.putArray("Statement").addObject().put("Action", "execute-api:Invoke")
                        .put("Effect", allowed ? "Allow" : "Deny").put("Resource", event.path("methodArn").asText());
            }
            if (context != null) {
                response.set("context", context);
            }
            return result(response.toString());
        });
    }

    @AfterEach
    void cleanUp() {
        try {
            if (apiId != null) {
                given().delete("/v2/apis/" + apiId).then().statusCode(204);
            }
        } finally {
            if (backend != null) {
                backend.stop(0);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "2.0"})
    void mapsVerifiedContextToHeadersQueryAndPath(String format) {
        payloadFormat = format;
        configureApi();
        invoke("first");
        JsonNode request = received.get();
        assertNotNull(request);
        assertEquals("2", request.path("x-tenant").asText());
        assertEquals("7", request.path("x-count").asText());
        assertEquals("true", request.path("x-active").asText());
        assertEquals("fixed", request.path("x-static").asText());
        assertFalse(request.path("x-request-id").asText().isBlank());
        assertTrue(request.path("x-missing").isNull());
        assertEquals("/tenant/2", request.path("path").asText());
        assertEquals("tenant=2", request.path("query").asText());
    }

    @Test
    void contextlessAllowDoesNotMapMissingValues() {
        context = null;
        configureApi();
        invoke("first");
        assertTrue(received.get().path("x-count").isNull());
        assertTrue(received.get().path("query").isNull());
    }

    @Test
    void eachRequestUsesItsAuthorizerResultAndPublicRoutesHaveNoContext() {
        configureApi();
        invoke("first");
        assertEquals("2", received.get().path("x-tenant").asText());
        context.put("tenantId", "3");
        invoke("second");
        assertEquals("3", received.get().path("x-tenant").asText());
        given().contentType(ContentType.JSON).body(Map.of("authorizationType", "NONE"))
                .patch("/v2/apis/" + apiId + "/routes/" + routeId).then().statusCode(200);
        invoke("third");
        assertTrue(received.get().path("x-count").isNull());
        assertTrue(received.get().path("query").isNull());
    }

    @Test
    void deniedRequestsNeverReachTheBackend() {
        allowed = false;
        configureApi();
        given().header("Authorization", "Bearer denied").get(path()).then().statusCode(403);
        assertEquals(0, backendCalls.get());
    }

    @Test
    void missingIdentityNeverInvokesAuthorizerOrBackend() {
        configureApi();
        given().get(path()).then().statusCode(401);
        verifyNoInteractions(lambdaService);
        assertEquals(0, backendCalls.get());
    }

    private void configureApi() {
        apiId = HttpProxyApiFixtures.createProxyApi("lambda-context-mapping", "", "test",
                backend.getAddress().getPort(), "/items");
        String apiPath = "/v2/apis/" + apiId;
        String integrationId = given().get(apiPath + "/integrations").then().statusCode(200)
                .extract().path("items[0].integrationId");
        given().contentType(ContentType.JSON).body(Map.of("requestParameters", Map.of(
                        "overwrite:header.x-tenant", "$context.authorizer.tenantId",
                        "append:header.x-count", "$context.authorizer.count",
                        "overwrite:header.x-active", "$context.authorizer.active",
                        "overwrite:header.x-missing", "$context.authorizer.missing",
                        "overwrite:header.x-static", "fixed",
                        "overwrite:header.x-request-id", "$context.requestId",
                        "overwrite:querystring.tenant", "$context.authorizer.tenantId",
                        "overwrite:path", "/tenant/$context.authorizer.tenantId")))
                .patch(apiPath + "/integrations/" + integrationId).then().statusCode(200);
        String authorizerId = given().contentType(ContentType.JSON).body(Map.of(
                        "name", "mapping-authorizer", "authorizerType", "REQUEST", "authorizerUri", AUTHORIZER,
                        "authorizerPayloadFormatVersion", payloadFormat, "enableSimpleResponses", "2.0".equals(payloadFormat),
                        "authorizerResultTtlInSeconds", 0, "identitySource", List.of("$request.header.Authorization")))
                .post(apiPath + "/authorizers").then().statusCode(201).extract().path("authorizerId");
        routeId = given().get(apiPath + "/routes").then().statusCode(200).extract().path("items[0].routeId");
        given().contentType(ContentType.JSON).body(Map.of("authorizationType", "CUSTOM", "authorizerId", authorizerId))
                .patch(apiPath + "/routes/" + routeId).then().statusCode(200);
    }

    private void invoke(String token) {
        given().header("Authorization", "Bearer " + token).header("x-tenant", "forged")
                .get(path()).then().statusCode(200);
    }

    private String path() {
        return "/execute-api/" + apiId + "/test/items";
    }
}
