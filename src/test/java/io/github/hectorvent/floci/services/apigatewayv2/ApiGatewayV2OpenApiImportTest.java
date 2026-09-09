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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ──────────────────────────── basepath ────────────────────────────

    /** A base path declared the OpenAPI 3 way: a "basePath" server variable. */
    private static final String SPEC_WITH_BASE_PATH = """
            {
              "openapi": "3.0.1",
              "info": {"title": "BasePathApi", "version": "1.0"},
              "servers": [{
                "url": "https://example.com/{basePath}",
                "variables": {"basePath": {"default": "/a/b/c"}}
              }],
              "paths": {
                "/e": {"get": {"x-amazon-apigateway-integration": {
                  "type": "http_proxy", "httpMethod": "GET", "uri": "https://example.com/e",
                  "payloadFormatVersion": "1.0"}}}
              }
            }
            """;

    private static String importWithBasePath(String basepath) throws Exception {
        String response = given()
                .contentType(ContentType.JSON)
                .queryParam("basepath", basepath)
                .body(envelope(SPEC_WITH_BASE_PATH))
                .when().put("/v2/apis")
                .then().statusCode(201)
                .extract().asString();
        String apiId = mapper.readTree(response).get("apiId").asText();
        return get("/v2/apis/" + apiId + "/routes").get("items").get(0).get("routeKey").asText();
    }

    @Test
    @Order(7)
    void basePathIgnoreIsTheDefault() throws Exception {
        assertEquals("GET /e", importWithBasePath("ignore"));

        // Omitting the parameter entirely must behave the same as ignore.
        String response = given().contentType(ContentType.JSON)
                .body(envelope(SPEC_WITH_BASE_PATH))
                .when().put("/v2/apis")
                .then().statusCode(201).extract().asString();
        String apiId = mapper.readTree(response).get("apiId").asText();
        assertEquals("GET /e", get("/v2/apis/" + apiId + "/routes").get("items").get(0).get("routeKey").asText());
    }

    @Test
    @Order(8)
    void basePathPrependAndSplit() throws Exception {
        assertEquals("GET /a/b/c/e", importWithBasePath("prepend"));
        // split drops the first segment of the base path.
        assertEquals("GET /b/c/e", importWithBasePath("split"));
    }

    @Test
    @Order(9)
    void basePathFallsBackToTheServerUrlPath() throws Exception {
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "ServerUrlApi", "version": "1.0"},
                  "servers": [{"url": "https://example.com/v1/api"}],
                  "paths": {"/e": {"get": {}}}
                }
                """;
        String response = given().contentType(ContentType.JSON)
                .queryParam("basepath", "prepend")
                .body(envelope(spec))
                .when().put("/v2/apis")
                .then().statusCode(201).extract().asString();
        String apiId = mapper.readTree(response).get("apiId").asText();
        assertEquals("GET /v1/api/e",
                get("/v2/apis/" + apiId + "/routes").get("items").get(0).get("routeKey").asText());
    }

    @Test
    @Order(10)
    void invalidBasePathIsRejected() throws Exception {
        given().contentType(ContentType.JSON)
                .queryParam("basepath", "sideways")
                .body(envelope(SPEC_WITH_AUTHORIZER))
                .when().put("/v2/apis")
                .then().statusCode(400);
    }

    // ──────────────────────────── failOnWarnings / importInfo ────────────────────────────

    @Test
    @Order(11)
    void importInfoReportsOperationsWithoutAnIntegration() throws Exception {
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "NoIntegrationApi", "version": "1.0"},
                  "paths": {"/bare": {"get": {}}}
                }
                """;
        String response = given().contentType(ContentType.JSON)
                .body(envelope(spec))
                .when().put("/v2/apis")
                .then().statusCode(201)
                .extract().asString();

        JsonNode api = mapper.readTree(response);
        JsonNode importInfo = get("/v2/apis/" + api.get("apiId").asText()).get("importInfo");
        assertNotNull(importInfo);
        assertTrue(importInfo.toString().contains("GET /bare"));
    }

    @Test
    @Order(12)
    void failOnWarningsRollsBackWithoutMutatingAnExistingApi() throws Exception {
        // A $ref to a schema that does not exist makes swagger-parser emit a message.
        String warnSpec = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "WarnApi", "version": "1.0"},
                  "paths": {"/w": {"get": {"responses": {"200": {"description": "ok",
                    "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Missing"}}}}}}}}
                }
                """;

        String created = given().contentType(ContentType.JSON)
                .body("{\"name\": \"failOnWarningsTarget\", \"protocolType\": \"HTTP\"}")
                .when().post("/v2/apis")
                .then().statusCode(201).extract().asString();
        String apiId = mapper.readTree(created).get("apiId").asText();

        given().contentType(ContentType.JSON)
                .body(envelope(SPEC_WITH_AUTHORIZER))
                .when().put("/v2/apis/" + apiId)
                .then().statusCode(201);
        assertEquals(2, get("/v2/apis/" + apiId + "/routes").get("items").size());

        given().contentType(ContentType.JSON)
                .queryParam("failOnWarnings", "true")
                .body(envelope(warnSpec))
                .when().put("/v2/apis/" + apiId)
                .then().statusCode(400);

        // The rejected reimport must not have deleted the routes it was going to replace.
        JsonNode routes = get("/v2/apis/" + apiId + "/routes");
        assertEquals(2, routes.get("items").size());
        assertNotNull(findRoute(routes, "GET /api/items"));

        // Without the flag the same document imports, recording the warning instead.
        given().contentType(ContentType.JSON)
                .body(envelope(warnSpec))
                .when().put("/v2/apis/" + apiId)
                .then().statusCode(201);
        assertNotNull(get("/v2/apis/" + apiId).get("warnings"));
    }

    @Test
    @Order(13)
    void rejectedAuthorizerLeavesThePreviousDefinitionIntact() throws Exception {
        // Syntactically valid, but HTTP APIs have no such authorizer type. The rejection has to
        // happen before the existing routes are deleted.
        String badAuthorizerSpec = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "BadAuthApi", "version": "1.0"},
                  "security": [{"Tokenish": []}],
                  "components": {"securitySchemes": {"Tokenish": {
                    "type": "apiKey", "name": "Authorization", "in": "header",
                    "x-amazon-apigateway-authorizer": {"type": "token", "authorizerUri": "arn:aws:lambda:x"}
                  }}},
                  "paths": {"/x": {"get": {}}}
                }
                """;

        String created = given().contentType(ContentType.JSON)
                .body("{\"name\": \"badAuthorizerTarget\", \"protocolType\": \"HTTP\"}")
                .when().post("/v2/apis")
                .then().statusCode(201).extract().asString();
        String apiId = mapper.readTree(created).get("apiId").asText();

        given().contentType(ContentType.JSON)
                .body(envelope(SPEC_WITH_AUTHORIZER))
                .when().put("/v2/apis/" + apiId)
                .then().statusCode(201);
        assertEquals(2, get("/v2/apis/" + apiId + "/routes").get("items").size());
        assertEquals(1, get("/v2/apis/" + apiId + "/authorizers").get("items").size());

        given().contentType(ContentType.JSON)
                .body(envelope(badAuthorizerSpec))
                .when().put("/v2/apis/" + apiId)
                .then().statusCode(400);

        JsonNode routes = get("/v2/apis/" + apiId + "/routes");
        assertEquals(2, routes.get("items").size());
        assertNotNull(findRoute(routes, "GET /api/items"));
        assertEquals(2, get("/v2/apis/" + apiId + "/integrations").get("items").size());
        assertEquals(1, get("/v2/apis/" + apiId + "/authorizers").get("items").size());
    }

    @Test
    @Order(14)
    void securityThatResolvesToNoAuthorizerIsWarnedAbout() throws Exception {
        // A plain bearer scheme carries no AWS binding, so the route can only be imported as NONE.
        // Silently doing so would make a document that declares security produce a public route.
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "UnboundSecurityApi", "version": "1.0"},
                  "components": {"securitySchemes": {"BearerAuth": {"type": "http", "scheme": "bearer"}}},
                  "paths": {"/secret": {"get": {"security": [{"BearerAuth": []}]}}}
                }
                """;

        String response = given().contentType(ContentType.JSON)
                .body(envelope(spec))
                .when().put("/v2/apis")
                .then().statusCode(201)
                .extract().asString();
        String apiId = mapper.readTree(response).get("apiId").asText();

        JsonNode warnings = get("/v2/apis/" + apiId).get("warnings");
        assertNotNull(warnings);
        assertTrue(warnings.toString().contains("GET /secret"));
        assertTrue(warnings.toString().contains("BearerAuth"));

        JsonNode route = findRoute(get("/v2/apis/" + apiId + "/routes"), "GET /secret");
        assertNotNull(route);
        assertEquals("NONE", route.get("authorizationType").asText());

        // failOnWarnings turns that same document into a rejection.
        given().contentType(ContentType.JSON)
                .queryParam("failOnWarnings", "true")
                .body(envelope(spec))
                .when().put("/v2/apis")
                .then().statusCode(400);
    }

    @Test
    @Order(15)
    void anyMethodSecurityIsCheckedToo() throws Exception {
        // x-amazon-apigateway-any-method is not part of readOperationsMap(), so a diagnostic pass
        // that walks only that map leaves ANY routes unexamined.
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "AnyMethodSecurityApi", "version": "1.0"},
                  "components": {"securitySchemes": {"BearerAuth": {"type": "http", "scheme": "bearer"}}},
                  "paths": {"/proxy": {"x-amazon-apigateway-any-method": {
                    "security": [{"BearerAuth": []}],
                    "x-amazon-apigateway-integration": {
                      "type": "http_proxy", "httpMethod": "ANY", "uri": "https://example.com",
                      "payloadFormatVersion": "1.0"}
                  }}}
                }
                """;

        String response = given().contentType(ContentType.JSON)
                .body(envelope(spec))
                .when().put("/v2/apis")
                .then().statusCode(201)
                .extract().asString();
        String apiId = mapper.readTree(response).get("apiId").asText();

        JsonNode warnings = get("/v2/apis/" + apiId).get("warnings");
        assertNotNull(warnings, "ANY-method security must be examined like any other operation");
        assertTrue(warnings.toString().contains("ANY /proxy"));

        assertNotNull(findRoute(get("/v2/apis/" + apiId + "/routes"), "ANY /proxy"));

        given().contentType(ContentType.JSON)
                .queryParam("failOnWarnings", "true")
                .body(envelope(spec))
                .when().put("/v2/apis")
                .then().statusCode(400);
    }

    @Test
    @Order(16)
    void compoundSecurityRequirementIsWarnedAbout() throws Exception {
        // Two schemes inside one requirement is AND. A route holds one authorizer, so the second
        // cannot be enforced — that has to be visible rather than silently dropped.
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "CompoundSecurityApi", "version": "1.0"},
                  "components": {"securitySchemes": {
                    "LambdaAuth": {
                      "type": "apiKey", "name": "Authorization", "in": "header",
                      "x-amazon-apigateway-authorizer": {
                        "type": "request",
                        "authorizerUri": "arn:aws:lambda:us-east-1:000000000000:function:auth",
                        "authorizerPayloadFormatVersion": "2.0",
                        "identitySource": "$request.header.Authorization"
                      }
                    },
                    "ExtraKey": {"type": "apiKey", "name": "X-Extra", "in": "header"}
                  }},
                  "paths": {"/both": {"get": {"security": [{"LambdaAuth": [], "ExtraKey": []}]}}}
                }
                """;

        String response = given().contentType(ContentType.JSON)
                .body(envelope(spec))
                .when().put("/v2/apis")
                .then().statusCode(201)
                .extract().asString();
        String apiId = mapper.readTree(response).get("apiId").asText();

        String warnings = get("/v2/apis/" + apiId).get("warnings").toString();
        assertTrue(warnings.contains("GET /both"), warnings);
        assertTrue(warnings.contains("ExtraKey"), warnings);

        // The resolvable half is still enforced.
        JsonNode route = findRoute(get("/v2/apis/" + apiId + "/routes"), "GET /both");
        assertEquals("CUSTOM", route.get("authorizationType").asText());
    }

    @Test
    @Order(17)
    void sigv4ImportsAsAwsIamWithoutAnUnenforcedWarning() throws Exception {
        // The route records AWS_IAM, and dispatch enforces it: an unsigned request to GET /signed
        // is rejected before the integration runs, so the import no longer warns that the
        // document's guarantee fails to hold locally.
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "SigV4Api", "version": "1.0"},
                  "components": {"securitySchemes": {"sigv4": {
                    "type": "http", "scheme": "aws.v4"
                  }}},
                  "paths": {"/signed": {"get": {"security": [{"sigv4": []}]}}}
                }
                """;

        String response = given().contentType(ContentType.JSON)
                .body(envelope(spec))
                .when().put("/v2/apis")
                .then().statusCode(201)
                .extract().asString();
        String apiId = mapper.readTree(response).get("apiId").asText();

        JsonNode route = findRoute(get("/v2/apis/" + apiId + "/routes"), "GET /signed");
        assertNotNull(route);
        assertEquals("AWS_IAM", route.get("authorizationType").asText());

        JsonNode warnings = get("/v2/apis/" + apiId).get("warnings");
        assertFalse(warnings != null && warnings.toString().contains("does not enforce"),
                String.valueOf(warnings));
    }

    @Test
    @Order(18)
    void scalarJwtAudienceIsAcceptedAndDoesNotDestroyThePreviousDefinition() throws Exception {
        // audience is stored as a List<String> and cast as one, so a scalar has to be coerced
        // during planning — otherwise the cast throws after ReimportApi has already deleted.
        String scalarAudienceSpec = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "ScalarAudienceApi", "version": "1.0"},
                  "security": [{"Jwt": []}],
                  "components": {"securitySchemes": {"Jwt": {
                    "type": "oauth2",
                    "x-amazon-apigateway-authorizer": {
                      "type": "jwt",
                      "jwtConfiguration": {"issuer": "https://issuer.example.com", "audience": "single-aud"},
                      "identitySource": "$request.header.Authorization"
                    }
                  }}},
                  "paths": {"/j": {"get": {}}}
                }
                """;

        String created = given().contentType(ContentType.JSON)
                .body("{\"name\": \"scalarAudienceTarget\", \"protocolType\": \"HTTP\"}")
                .when().post("/v2/apis")
                .then().statusCode(201).extract().asString();
        String apiId = mapper.readTree(created).get("apiId").asText();

        given().contentType(ContentType.JSON)
                .body(envelope(SPEC_WITH_AUTHORIZER))
                .when().put("/v2/apis/" + apiId)
                .then().statusCode(201);
        assertEquals(2, get("/v2/apis/" + apiId + "/routes").get("items").size());

        given().contentType(ContentType.JSON)
                .body(envelope(scalarAudienceSpec))
                .when().put("/v2/apis/" + apiId)
                .then().statusCode(201);

        JsonNode authorizer = get("/v2/apis/" + apiId + "/authorizers").get("items").get(0);
        assertEquals("JWT", authorizer.get("authorizerType").asText());
        assertEquals("single-aud", authorizer.get("jwtConfiguration").get("audience").get(0).asText());

        // A shape neither string nor array is refused, with the previous definition kept.
        String badAudience = scalarAudienceSpec.replace("\"single-aud\"", "{\"nope\": 1}");
        given().contentType(ContentType.JSON)
                .body(envelope(badAudience))
                .when().put("/v2/apis/" + apiId)
                .then().statusCode(400);
        assertNotNull(findRoute(get("/v2/apis/" + apiId + "/routes"), "GET /j"));
    }
}
