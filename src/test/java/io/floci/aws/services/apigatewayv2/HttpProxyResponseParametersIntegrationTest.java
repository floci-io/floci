package io.floci.aws.services.apigatewayv2;

import com.sun.net.httpserver.HttpServer;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class HttpProxyResponseParametersIntegrationTest {
    private HttpServer backend;
    private String apiId;
    private String integrationId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void startBackend() throws Exception {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            int status = query != null && query.startsWith("status=") ? Integer.parseInt(query.substring(7)) : 200;
            exchange.getResponseHeaders().add("X-Backend", "one");
            exchange.getResponseHeaders().add("X-Drop", "gone");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] bytes = "{\"error\":{\"code\":\"E-7\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        backend.start();
        apiId = HttpProxyApiFixtures.createProxyApi("response-parameters", "", "test",
                backend.getAddress().getPort(), "/orders");
        integrationId = given().get("/v2/apis/" + apiId + "/integrations").then().statusCode(200)
                .extract().path("items[0].integrationId");
    }

    @AfterEach
    void cleanUp() {
        try {
            given().delete("/v2/apis/" + apiId).then().statusCode(204);
        } finally {
            backend.stop(0);
        }
    }

    @Test
    void createReturnsAndGetReadsResponseParameters() {
        Map<String, Object> responseParameters = Map.of(
                "500", Map.of("overwrite:statuscode", "403", "append:header.header1", "$context.requestId"),
                "404", Map.of("append:header.error", "$stageVariables.environmentId"));

        String createdId = given().contentType(ContentType.JSON)
                .body(Map.of("integrationType", "HTTP_PROXY", "integrationUri", "https://api.example.com",
                        "integrationMethod", "ANY", "payloadFormatVersion", "1.0",
                        "responseParameters", responseParameters))
                .post("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(201)
                .body("responseParameters", equalTo(responseParameters))
                .extract().path("integrationId");

        given().get("/v2/apis/" + apiId + "/integrations/" + createdId)
                .then().statusCode(200)
                .body("responseParameters", equalTo(responseParameters));
        given().get("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(200)
                .body("items.find { it.integrationId == '" + createdId + "' }.responseParameters",
                        equalTo(responseParameters));
    }

    @Test
    void updateReplacesAStatusCodesMappingsAndAnEmptyMapRemovesIt() {
        patchResponseParameters(Map.of(
                "500", Map.of("overwrite:statuscode", "403"),
                "502", Map.of("overwrite:statuscode", "200")));

        patchResponseParameters(Map.of(
                "500", Map.of(),
                "502", Map.of("append:header.x-retry", "later"),
                "404", Map.of("remove:header.x-drop", "")));

        given().get("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(200)
                .body("responseParameters", equalTo(Map.of(
                        "502", Map.of("append:header.x-retry", "later"),
                        "404", Map.of("remove:header.x-drop", ""))));

        patchResponseParameters(Map.of("502", Map.of(), "404", Map.of()));

        assertNull(given().get("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(200).extract().path("responseParameters"));
    }

    @Test
    void json11CreateAndGetKeepResponseParameters() {
        String target = "AmazonApiGatewayV2.";
        String json11 = "application/x-amz-json-1.1";
        String auth = "AWS4-HMAC-SHA256 Credential=test/20260413/us-east-1/apigatewayv2/aws4_request";
        String createdId = given().contentType(json11).header("X-Amz-Target", target + "CreateIntegration")
                .header("Authorization", auth)
                .body("""
                        {"ApiId":"%s","IntegrationType":"HTTP_PROXY","IntegrationUri":"http://example.com",
                         "PayloadFormatVersion":"1.0","ResponseParameters":{"500":{"overwrite:statuscode":"200"}}}
                        """.formatted(apiId))
                .post("/")
                .then().statusCode(201)
                .body("ResponseParameters.'500'.'overwrite:statuscode'", equalTo("200"))
                .extract().path("IntegrationId");

        given().contentType(json11).header("X-Amz-Target", target + "GetIntegration")
                .header("Authorization", auth)
                .body("{\"ApiId\":\"%s\",\"IntegrationId\":\"%s\"}".formatted(apiId, createdId))
                .post("/")
                .then().statusCode(200)
                .body("ResponseParameters.'500'.'overwrite:statuscode'", equalTo("200"));
    }

    @Test
    void statusCodeOutsideTwoHundredToFiveNinetyNineIsRejected() {
        given().contentType(ContentType.JSON)
                .body(Map.of("responseParameters", Map.of("600", Map.of("overwrite:statuscode", "200"))))
                .patch("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", containsString("ResponseParameters key 600"));
    }

    @Test
    void unknownMappingIsRejectedAndLeavesTheIntegrationUnchanged() {
        patchResponseParameters(Map.of("500", Map.of("overwrite:statuscode", "403")));

        given().contentType(ContentType.JSON)
                .body(Map.of("responseParameters", Map.of(
                        "500", Map.of("overwrite:statuscode", "200"),
                        "404", Map.of("overwrite:querystring.page", "1"))))
                .patch("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", containsString("ResponseParameters key overwrite:querystring.page"));

        given().get("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(200)
                .body("responseParameters", equalTo(Map.of("500", Map.of("overwrite:statuscode", "403"))));
    }

    @Test
    void statusCodeOverwriteAppliesOnlyToTheMatchingBackendStatus() {
        patchResponseParameters(Map.of("500", Map.of("overwrite:statuscode", "200")));

        given().get("/execute-api/" + apiId + "/test/orders?status=500")
                .then().statusCode(200)
                .body("error.code", equalTo("E-7"));
        given().get("/execute-api/" + apiId + "/test/orders?status=502")
                .then().statusCode(502);
    }

    @Test
    void headerActionsReadTheBackendResponseAndTheRequestContext() {
        patchResponseParameters(Map.of("200", Map.of(
                "append:header.x-copy", "$response.header.x-backend",
                "overwrite:header.X-Backend", "two",
                "remove:header.x-drop", "''",
                "append:header.x-code", "$response.body.error.code",
                "append:header.x-stage", "$context.stage",
                "append:header.x-missing", "$response.header.x-absent")));

        Response response = given().get("/execute-api/" + apiId + "/test/orders");

        assertEquals(200, response.statusCode());
        assertEquals("one", response.header("x-copy"));
        assertEquals("two", response.header("x-backend"));
        assertEquals(1, response.headers().getValues("x-backend").size());
        assertNull(response.header("x-drop"));
        assertEquals("E-7", response.header("x-code"));
        assertEquals("test", response.header("x-stage"));
        assertNull(response.header("x-missing"));
    }

    private void patchResponseParameters(Map<String, Object> responseParameters) {
        given().contentType(ContentType.JSON).body(Map.of("responseParameters", responseParameters))
                .patch("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(200);
    }
}
