package io.floci.aws.services.apigatewayv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class HttpProxyRequestSourceMappingIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> HEADERS = List.of("x-order", "x-backend", "x-stage", "x-route", "x-api",
            "x-account", "x-path", "x-epoch", "x-missing", "x-missing-stage");
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private HttpServer backend;
    private String apiId;

    @BeforeEach
    void startBackend() throws Exception {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            ObjectNode request = MAPPER.createObjectNode();
            for (String header : HEADERS) {
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

    @Test
    void mapsBodyStageVariablesAndContextToHeadersQueryAndPath() {
        configureApi();
        long before = System.currentTimeMillis();
        invoke("{\"order\":{\"id\":\"o-42\"}}");
        JsonNode request = received.get();
        assertNotNull(request);
        assertEquals("o-42", request.path("x-order").asText());
        assertEquals("blue", request.path("x-backend").asText());
        assertEquals("test", request.path("x-stage").asText());
        assertEquals("POST /orders", request.path("x-route").asText());
        assertEquals(apiId, request.path("x-api").asText());
        assertEquals("000000000000", request.path("x-account").asText());
        assertEquals("/test/orders", request.path("x-path").asText());
        assertTrue(Long.parseLong(request.path("x-epoch").asText()) >= before - 1000);
        assertTrue(request.path("x-missing").isNull());
        assertTrue(request.path("x-missing-stage").isNull());
        assertEquals("/orders/o-42/test", request.path("path").asText());
        assertEquals("order=o-42", request.path("query").asText());
    }

    @Test
    void bodyThatIsNotJsonSkipsBodyMappings() {
        configureApi();
        invoke("not json");
        JsonNode request = received.get();
        assertTrue(request.path("x-order").isNull());
        assertTrue(request.path("query").isNull());
        assertEquals("blue", request.path("x-backend").asText());
    }

    private void configureApi() {
        apiId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "request-source-mapping", "protocolType", "HTTP"))
                .post("/v2/apis").then().statusCode(201).extract().path("apiId");
        String apiPath = "/v2/apis/" + apiId;
        given().contentType(ContentType.JSON)
                .body(Map.of("stageName", "test", "autoDeploy", true, "stageVariables", Map.of("backend", "blue")))
                .post(apiPath + "/stages").then().statusCode(201);
        Map<String, String> requestParameters = Map.ofEntries(
                Map.entry("overwrite:header.x-order", "$request.body.order.id"),
                Map.entry("overwrite:header.x-backend", "$stageVariables.backend"),
                Map.entry("overwrite:header.x-stage", "$context.stage"),
                Map.entry("overwrite:header.x-route", "$context.routeKey"),
                Map.entry("overwrite:header.x-api", "$context.apiId"),
                Map.entry("overwrite:header.x-account", "$context.accountId"),
                Map.entry("overwrite:header.x-path", "$context.path"),
                Map.entry("overwrite:header.x-epoch", "$context.requestTimeEpoch"),
                Map.entry("overwrite:header.x-missing", "$request.body.order.missing"),
                Map.entry("overwrite:header.x-missing-stage", "$stageVariables.missing"),
                Map.entry("overwrite:querystring.order", "$request.body.order.id"),
                Map.entry("overwrite:path", "/orders/$request.body.order.id/$context.stage"));
        String integrationId = given().contentType(ContentType.JSON)
                .body(Map.of("integrationType", "HTTP_PROXY", "integrationMethod", "POST",
                        "integrationUri", "http://127.0.0.1:" + backend.getAddress().getPort() + "/orders",
                        "payloadFormatVersion", "1.0", "requestParameters", requestParameters))
                .post(apiPath + "/integrations").then().statusCode(201).extract().path("integrationId");
        given().contentType(ContentType.JSON)
                .body(Map.of("routeKey", "POST /orders", "target", "integrations/" + integrationId))
                .post(apiPath + "/routes").then().statusCode(201);
    }

    private void invoke(String body) {
        given().contentType(ContentType.JSON).body(body)
                .post("/execute-api/" + apiId + "/test/orders").then().statusCode(200);
    }
}
