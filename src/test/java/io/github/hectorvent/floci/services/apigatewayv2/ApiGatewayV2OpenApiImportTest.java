package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for HTTP API (v2) OpenAPI import.
 * Covers ImportApi (PUT /v2/apis) and ReimportApi (PUT /v2/apis/{apiId}).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2OpenApiImportTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String SPEC_WITH_AUTHORIZER = """
            {
              "openapi": "3.0.1",
              "info": {"title": "ImportedHttpApi", "description": "imported", "version": "1.0"},
              "security": [{"LambdaAuth": []}],
              "components": {
                "securitySchemes": {
                  "LambdaAuth": {
                    "type": "apiKey",
                    "name": "Authorization",
                    "in": "header",
                    "x-amazon-apigateway-authorizer": {
                      "type": "request",
                      "authorizerUri": "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:auth/invocations",
                      "authorizerPayloadFormatVersion": "2.0",
                      "enableSimpleResponses": true,
                      "identitySource": "$request.header.Authorization,$context.routeKey",
                      "authorizerResultTtlInSeconds": 300
                    }
                  }
                }
              },
              "paths": {
                "/api/items": {
                  "get": {
                    "x-amazon-apigateway-integration": {
                      "type": "aws_proxy",
                      "httpMethod": "POST",
                      "uri": "arn:aws:lambda:us-east-1:000000000000:function:items",
                      "payloadFormatVersion": "2.0",
                      "timeoutInMillis": 30000
                    }
                  }
                },
                "/api/public": {
                  "get": {
                    "security": [],
                    "x-amazon-apigateway-integration": {
                      "type": "aws_proxy",
                      "httpMethod": "POST",
                      "uri": "arn:aws:lambda:us-east-1:000000000000:function:public",
                      "payloadFormatVersion": "2.0"
                    }
                  }
                }
              }
            }
            """;

    /** ImportApi/ReimportApi take the document inside a restJson1 envelope. */
    private static String envelope(String spec) throws Exception {
        return mapper.writeValueAsString(mapper.createObjectNode().put("body", spec));
    }

    private static JsonNode get(String path) throws Exception {
        return mapper.readTree(given().when().get(path).then().statusCode(200).extract().asString());
    }

    private static JsonNode findRoute(JsonNode routes, String routeKey) {
        for (JsonNode route : routes.get("items")) {
            if (routeKey.equals(route.path("routeKey").asText())) {
                return route;
            }
        }
        return null;
    }

    @Test
    @Order(1)
    void importApi_createsRoutesIntegrationsAndAuthorizer() throws Exception {
        String response = given()
                .contentType(ContentType.JSON)
                .body(envelope(SPEC_WITH_AUTHORIZER))
                .when().put("/v2/apis")
                .then().statusCode(201)
                .extract().asString();

        JsonNode api = mapper.readTree(response);
        String apiId = api.get("apiId").asText();
        assertEquals("ImportedHttpApi", api.get("name").asText());
        assertEquals("HTTP", api.get("protocolType").asText());

        JsonNode authorizers = get("/v2/apis/" + apiId + "/authorizers");
        assertEquals(1, authorizers.get("items").size());
        JsonNode authorizer = authorizers.get("items").get(0);
        assertEquals("LambdaAuth", authorizer.get("name").asText());
        assertEquals("REQUEST", authorizer.get("authorizerType").asText());
        assertEquals("2.0", authorizer.get("authorizerPayloadFormatVersion").asText());
        assertTrue(authorizer.get("enableSimpleResponses").asBoolean());
        assertEquals(300, authorizer.get("authorizerResultTtlInSeconds").asInt());
        // The comma-separated extension value becomes the AWS list form.
        assertEquals(2, authorizer.get("identitySource").size());
        assertEquals("$request.header.Authorization", authorizer.get("identitySource").get(0).asText());
        assertEquals("$context.routeKey", authorizer.get("identitySource").get(1).asText());

        JsonNode integrations = get("/v2/apis/" + apiId + "/integrations");
        assertEquals(2, integrations.get("items").size());
        JsonNode integration = integrations.get("items").get(0);
        assertEquals("AWS_PROXY", integration.get("integrationType").asText());
        assertEquals("POST", integration.get("integrationMethod").asText());
        assertEquals("2.0", integration.get("payloadFormatVersion").asText());

        JsonNode routes = get("/v2/apis/" + apiId + "/routes");
        assertEquals(2, routes.get("items").size());

        JsonNode secured = findRoute(routes, "GET /api/items");
        assertNotNull(secured);
        assertEquals("CUSTOM", secured.get("authorizationType").asText());
        assertEquals(authorizer.get("authorizerId").asText(), secured.get("authorizerId").asText());
        assertTrue(secured.get("target").asText().startsWith("integrations/"));

        // security: [] on the operation overrides the document-level requirement.
        JsonNode publicRoute = findRoute(routes, "GET /api/public");
        assertNotNull(publicRoute);
        assertEquals("NONE", publicRoute.get("authorizationType").asText());
        assertTrue(publicRoute.path("authorizerId").isMissingNode()
                || publicRoute.get("authorizerId").isNull());
    }

    @Test
    @Order(2)
    void reimportApi_replacesDefinitionAndKeepsResourceLevelCors() throws Exception {
        // CreateApi carries CORS, exactly as Terraform's cors_configuration block does.
        String created = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "ReimportTarget",
                          "protocolType": "HTTP",
                          "corsConfiguration": {"allowOrigins": ["https://example.com"], "allowMethods": ["GET"]}
                        }
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().asString();
        String apiId = mapper.readTree(created).get("apiId").asText();

        // A route that the spec does not declare must not survive the reimport.
        given().contentType(ContentType.JSON)
                .body("{\"routeKey\": \"GET /stale\"}")
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body(envelope(SPEC_WITH_AUTHORIZER))
                .when().put("/v2/apis/" + apiId)
                .then().statusCode(201);

        JsonNode routes = get("/v2/apis/" + apiId + "/routes");
        assertEquals(2, routes.get("items").size());
        assertNull(findRoute(routes, "GET /stale"));
        assertNotNull(findRoute(routes, "GET /api/items"));

        JsonNode api = get("/v2/apis/" + apiId);
        assertEquals("ImportedHttpApi", api.get("name").asText());
        // The spec has no x-amazon-apigateway-cors, so the API keeps the CORS it was created with.
        assertEquals("https://example.com",
                api.get("corsConfiguration").get("allowOrigins").get(0).asText());
    }

    @Test
    @Order(3)
    void reimportApi_isIdempotent() throws Exception {
        String created = given()
                .contentType(ContentType.JSON)
                .body("{\"name\": \"IdempotentTarget\", \"protocolType\": \"HTTP\"}")
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().asString();
        String apiId = mapper.readTree(created).get("apiId").asText();

        for (int i = 0; i < 2; i++) {
            given().contentType(ContentType.JSON)
                    .body(envelope(SPEC_WITH_AUTHORIZER))
                    .when().put("/v2/apis/" + apiId)
                    .then().statusCode(201);
        }

        assertEquals(2, get("/v2/apis/" + apiId + "/routes").get("items").size());
        assertEquals(2, get("/v2/apis/" + apiId + "/integrations").get("items").size());
        assertEquals(1, get("/v2/apis/" + apiId + "/authorizers").get("items").size());
    }

    @Test
    @Order(4)
    void importApi_supportsYamlAndAnyMethod() throws Exception {
        String yamlSpec = """
                openapi: 3.0.1
                info:
                  title: YamlApi
                  version: "1.0"
                paths:
                  /proxy:
                    x-amazon-apigateway-any-method:
                      x-amazon-apigateway-integration:
                        type: http_proxy
                        httpMethod: ANY
                        uri: https://example.com
                        payloadFormatVersion: "1.0"
                """;

        String response = given()
                .contentType(ContentType.JSON)
                .body(envelope(yamlSpec))
                .when().put("/v2/apis")
                .then().statusCode(201)
                .extract().asString();
        String apiId = mapper.readTree(response).get("apiId").asText();

        JsonNode routes = get("/v2/apis/" + apiId + "/routes");
        assertEquals(1, routes.get("items").size());
        assertNotNull(findRoute(routes, "ANY /proxy"));

        JsonNode integrations = get("/v2/apis/" + apiId + "/integrations");
        assertEquals("HTTP_PROXY", integrations.get("items").get(0).get("integrationType").asText());
    }

    @Test
    @Order(5)
    void reimportApi_unknownApiReturns404() throws Exception {
        given().contentType(ContentType.JSON)
                .body(envelope(SPEC_WITH_AUTHORIZER))
                .when().put("/v2/apis/doesnotexist")
                .then().statusCode(404);
    }

    @Test
    @Order(6)
    void importApi_invalidSpecReturns400() {
        given().contentType(ContentType.JSON)
                .body("{\"body\": \"this is not an openapi document\"}")
                .when().put("/v2/apis")
                .then().statusCode(400);
    }
}
