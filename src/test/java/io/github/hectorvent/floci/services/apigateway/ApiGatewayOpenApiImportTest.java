package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for API Gateway OpenAPI/Swagger import.
 * Tests ImportRestApi (POST /restapis?mode=import) and PutRestApi (PUT /restapis/{apiId}?mode=overwrite).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayOpenApiImportTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static String importedApiId;
    private static String overwriteApiId;

    // ──────────────────────────── Import Basic ────────────────────────────

    @Test
    @Order(1)
    void importRestApi_basicMock() throws Exception {
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": {
                    "title": "MockAPI",
                    "description": "A simple mock API",
                    "version": "1.0"
                  },
                  "paths": {
                    "/health": {
                      "get": {
                        "x-amazon-apigateway-integration": {
                          "type": "MOCK",
                          "requestTemplates": {
                            "application/json": "{\\"statusCode\\": 200}"
                          },
                          "responses": {
                            "default": {
                              "statusCode": "200",
                              "responseTemplates": {
                                "application/json": "{\\"status\\": \\"ok\\"}"
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;

        String body = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .body("name", equalTo("MockAPI"))
                .body("description", equalTo("A simple mock API"))
                .body("id", notNullValue())
                .extract().body().asString();

        JsonNode node = mapper.readTree(body);
        importedApiId = node.get("id").asText();
    }

    @Test
    @Order(2)
    void importRestApi_resourcesCreated() {
        // Should have root "/" and "/health"
        String body = given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + importedApiId + "/resources")
                .then()
                .statusCode(200)
                .body("item", hasSize(2))
                .extract().body().asString();
    }

    @Test
    @Order(3)
    void importRestApi_methodAndIntegrationCreated() throws Exception {
        // Find the /health resource
        String body = given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + importedApiId + "/resources")
                .then()
                .statusCode(200)
                .extract().body().asString();

        JsonNode resources = mapper.readTree(body).get("item");
        String healthResourceId = null;
        for (JsonNode r : resources) {
            if ("/health".equals(r.get("path").asText())) {
                healthResourceId = r.get("id").asText();
            }
        }
        assertNotNull(healthResourceId, "Should have /health resource");

        // Verify the GET method has MOCK integration
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + importedApiId + "/resources/" + healthResourceId + "/methods/GET/integration")
                .then()
                .statusCode(200)
                .body("type", equalTo("MOCK"));
    }

    // ──────────────────────────── Import Nested Paths ────────────────────────────

    @Test
    @Order(10)
    void importRestApi_nestedPaths() throws Exception {
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "NestedAPI", "version": "1.0" },
                  "paths": {
                    "/orders": {
                      "get": {
                        "x-amazon-apigateway-integration": { "type": "MOCK" }
                      }
                    },
                    "/orders/{orderId}": {
                      "get": {
                        "x-amazon-apigateway-integration": { "type": "MOCK" }
                      }
                    },
                    "/orders/{orderId}/items": {
                      "get": {
                        "x-amazon-apigateway-integration": { "type": "MOCK" }
                      },
                      "post": {
                        "x-amazon-apigateway-integration": { "type": "MOCK" }
                      }
                    }
                  }
                }
                """;

        String body = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .body("name", equalTo("NestedAPI"))
                .extract().body().asString();

        String apiId = mapper.readTree(body).get("id").asText();

        // Should have: /, /orders, /orders/{orderId}, /orders/{orderId}/items
        String resourcesBody = given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .body("item", hasSize(4))
                .extract().body().asString();

        JsonNode items = mapper.readTree(resourcesBody).get("item");
        boolean hasRoot = false, hasOrders = false, hasOrderId = false, hasItems = false;
        for (JsonNode r : items) {
            String path = r.get("path").asText();
            switch (path) {
                case "/" -> hasRoot = true;
                case "/orders" -> hasOrders = true;
                case "/orders/{orderId}" -> hasOrderId = true;
                case "/orders/{orderId}/items" -> hasItems = true;
            }
        }
        assertTrue(hasRoot && hasOrders && hasOrderId && hasItems,
                "Should have all nested resources");

        // Cleanup
        given().delete("/restapis/" + apiId);
    }

    // ──────────────────────────── Import with AWS Integration ────────────────────────────

    @Test
    @Order(20)
    void importRestApi_awsIntegrationWithTemplates() throws Exception {
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "SfnAPI", "version": "1.0" },
                  "paths": {
                    "/start": {
                      "post": {
                        "x-amazon-apigateway-integration": {
                          "type": "AWS",
                          "httpMethod": "POST",
                          "uri": "arn:aws:apigateway:us-east-1:states:action/StartExecution",
                          "passthroughBehavior": "NEVER",
                          "requestTemplates": {
                            "application/json": "#set($body = $input.json('$'))\\n{\\"input\\": \\"$util.escapeJavaScript($body)\\", \\"stateMachineArn\\": \\"arn:aws:states:us-east-1:000000000000:stateMachine:test\\"}"
                          },
                          "requestParameters": {
                            "integration.request.header.X-Custom": "'static-value'"
                          },
                          "responses": {
                            "default": {
                              "statusCode": "200",
                              "responseTemplates": {
                                "application/json": "$input.json('$')"
                              }
                            },
                            "4\\\\d{2}": {
                              "statusCode": "400"
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;

        String body = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract().body().asString();

        String apiId = mapper.readTree(body).get("id").asText();

        // Find /start resource
        String resourcesBody = given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .extract().body().asString();

        String startResourceId = null;
        for (JsonNode r : mapper.readTree(resourcesBody).get("item")) {
            if ("/start".equals(r.get("path").asText())) {
                startResourceId = r.get("id").asText();
            }
        }
        assertNotNull(startResourceId);

        // Verify integration
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/resources/" + startResourceId + "/methods/POST/integration")
                .then()
                .statusCode(200)
                .body("type", equalTo("AWS"))
                .body("uri", equalTo("arn:aws:apigateway:us-east-1:states:action/StartExecution"))
                .body("passthroughBehavior", equalTo("NEVER"));

        // Verify integration responses exist
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/resources/" + startResourceId + "/methods/POST/integration/responses/200")
                .then()
                .statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/resources/" + startResourceId + "/methods/POST/integration/responses/400")
                .then()
                .statusCode(200);

        // Verify method responses exist
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/resources/" + startResourceId + "/methods/POST/responses/200")
                .then()
                .statusCode(200);

        // Cleanup
        given().delete("/restapis/" + apiId);
    }

    // ──────────────────────────── PutRestApi (overwrite) ────────────────────────────

    @Test
    @Order(30)
    void putRestApi_createApiForOverwrite() throws Exception {
        // Create API imperatively first
        String body = given()
                .contentType(ContentType.JSON)
                .body("{\"name\": \"OverwriteMe\", \"description\": \"Will be overwritten\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract().body().asString();

        overwriteApiId = mapper.readTree(body).get("id").asText();
    }

    @Test
    @Order(31)
    void putRestApi_overwriteWithNewSpec() throws Exception {
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "OverwrittenAPI", "description": "New description", "version": "2.0" },
                  "paths": {
                    "/users": {
                      "get": {
                        "x-amazon-apigateway-integration": { "type": "MOCK" }
                      }
                    },
                    "/users/{userId}": {
                      "get": {
                        "x-amazon-apigateway-integration": { "type": "MOCK" }
                      },
                      "delete": {
                        "x-amazon-apigateway-integration": { "type": "MOCK" }
                      }
                    }
                  }
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "overwrite")
                .body(spec)
                .when()
                .put("/restapis/" + overwriteApiId)
                .then()
                .statusCode(200)
                .body("name", equalTo("OverwrittenAPI"))
                .body("description", equalTo("New description"));
    }

    @Test
    @Order(32)
    void putRestApi_verifyNewResources() throws Exception {
        // Should have: /, /users, /users/{userId}
        String body = given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + overwriteApiId + "/resources")
                .then()
                .statusCode(200)
                .body("item", hasSize(3))
                .extract().body().asString();

        JsonNode items = mapper.readTree(body).get("item");
        boolean hasUsers = false, hasUserId = false;
        for (JsonNode r : items) {
            String path = r.get("path").asText();
            if ("/users".equals(path)) hasUsers = true;
            if ("/users/{userId}".equals(path)) hasUserId = true;
        }
        assertTrue(hasUsers && hasUserId, "Should have /users and /users/{userId}");
    }

    // ──────────────────────────── Swagger 2.0 ────────────────────────────

    @Test
    @Order(40)
    void importRestApi_swagger20() throws Exception {
        String spec = """
                {
                  "swagger": "2.0",
                  "info": { "title": "Swagger2API", "version": "1.0" },
                  "paths": {
                    "/ping": {
                      "get": {
                        "responses": { "200": { "description": "OK" } },
                        "x-amazon-apigateway-integration": {
                          "type": "MOCK",
                          "requestTemplates": {
                            "application/json": "{\\"statusCode\\": 200}"
                          },
                          "responses": {
                            "default": {
                              "statusCode": "200",
                              "responseTemplates": {
                                "application/json": "{\\"message\\": \\"pong\\"}"
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;

        String body = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .body("name", equalTo("Swagger2API"))
                .extract().body().asString();

        String apiId = mapper.readTree(body).get("id").asText();

        // Verify resources
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .body("item", hasSize(2));

        // Cleanup
        given().delete("/restapis/" + apiId);
    }

    // ──────────────────────────── Error Cases ────────────────────────────

    @Test
    @Order(50)
    void importRestApi_invalidSpec() {
        given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body("this is not valid json or yaml")
                .when()
                .post("/restapis")
                .then()
                .statusCode(400);
    }

    @Test
    @Order(51)
    void putRestApi_nonExistentApi() {
        String spec = """
                {"openapi": "3.0.1", "info": {"title": "Test", "version": "1.0"}, "paths": {}}
                """;
        given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "overwrite")
                .body(spec)
                .when()
                .put("/restapis/nonexistent123")
                .then()
                .statusCode(404);
    }

    @Test
    @Order(52)
    void putRestApi_modeMergeReturnsError() throws Exception {
        // Create an API first
        String apiBody = given()
                .contentType(ContentType.JSON)
                .body("{\"name\": \"MergeTest\"}")
                .post("/restapis")
                .then().statusCode(201).extract().body().asString();
        String apiId = mapper.readTree(apiBody).get("id").asText();

        String spec = """
                {"openapi": "3.0.1", "info": {"title": "Test", "version": "1.0"}, "paths": {}}
                """;
        given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "merge")
                .body(spec)
                .when()
                .put("/restapis/" + apiId)
                .then()
                .statusCode(400);

        given().delete("/restapis/" + apiId);
    }

    // ──────────────────────────── YAML Format ────────────────────────────

    @Test
    @Order(60)
    void importRestApi_yamlFormat() throws Exception {
        String spec = """
                openapi: "3.0.1"
                info:
                  title: YamlAPI
                  version: "1.0"
                paths:
                  /status:
                    get:
                      x-amazon-apigateway-integration:
                        type: MOCK
                        requestTemplates:
                          application/json: '{"statusCode": 200}'
                        responses:
                          default:
                            statusCode: "200"
                """;

        String body = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .body("name", equalTo("YamlAPI"))
                .extract().body().asString();

        String apiId = mapper.readTree(body).get("id").asText();

        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .body("item", hasSize(2));

        // Cleanup
        given().delete("/restapis/" + apiId);
    }

    // ──────────────────────────── Root Path Methods ────────────────────────────

    @Test
    @Order(70)
    void importRestApi_methodOnRootPath() throws Exception {
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "RootPathAPI", "version": "1.0" },
                  "paths": {
                    "/": {
                      "get": {
                        "x-amazon-apigateway-integration": {
                          "type": "MOCK",
                          "requestTemplates": { "application/json": "{\\"statusCode\\": 200}" },
                          "responses": { "default": { "statusCode": "200" } }
                        }
                      }
                    },
                    "/sub": {
                      "post": {
                        "x-amazon-apigateway-integration": { "type": "MOCK" }
                      }
                    }
                  }
                }
                """;

        String body = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract().body().asString();

        String apiId = mapper.readTree(body).get("id").asText();

        // Should have root "/" and "/sub"
        String resourcesBody = given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .body("item", hasSize(2))
                .extract().body().asString();

        // Find root resource and verify it has a GET method
        JsonNode items = mapper.readTree(resourcesBody).get("item");
        String rootId = null;
        for (JsonNode r : items) {
            if ("/".equals(r.get("path").asText())) {
                rootId = r.get("id").asText();
            }
        }
        assertNotNull(rootId);

        // Root should have GET method with MOCK integration
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/resources/" + rootId + "/methods/GET/integration")
                .then()
                .statusCode(200)
                .body("type", equalTo("MOCK"));

        given().delete("/restapis/" + apiId);
    }

    // ──────────────────────────── End-to-End Invoke ────────────────────────────

    @Test
    @Order(80)
    void importRestApi_deployAndInvokeMock() throws Exception {
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "InvokeTestAPI", "version": "1.0" },
                  "paths": {
                    "/echo": {
                      "get": {
                        "x-amazon-apigateway-integration": {
                          "type": "MOCK",
                          "requestTemplates": { "application/json": "{\\"statusCode\\": 200}" },
                          "responses": {
                            "default": {
                              "statusCode": "200",
                              "responseTemplates": {
                                "application/json": "{\\"message\\": \\"hello from imported api\\"}"
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;

        // Import
        String body = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract().body().asString();

        String apiId = mapper.readTree(body).get("id").asText();

        // Deploy
        String deployBody = given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/restapis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .extract().body().asString();

        String deployId = mapper.readTree(deployBody).get("id").asText();

        // Create stage
        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\": \"test\", \"deploymentId\": \"" + deployId + "\"}")
                .when()
                .post("/restapis/" + apiId + "/stages")
                .then()
                .statusCode(201);

        // Invoke
        given()
                .when()
                .get("/execute-api/" + apiId + "/test/echo")
                .then()
                .statusCode(200)
                .body(containsString("hello from imported api"));

        given().delete("/restapis/" + apiId);
    }

    // ──────────────────────────── Schemas → Models ────────────────────────────

    @Test
    @Order(85)
    void importRestApi_schemasCreateModels() throws Exception {
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "ModelsAPI", "version": "1.0" },
                  "paths": {
                    "/orders": {
                      "post": {
                        "requestBody": {
                          "content": {
                            "application/json": {
                              "schema": { "$ref": "#/components/schemas/OrderInput" }
                            }
                          }
                        },
                        "x-amazon-apigateway-integration": { "type": "MOCK",
                          "requestTemplates": { "application/json": "{\\"statusCode\\": 200}" },
                          "responses": { "default": { "statusCode": "200" } } }
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "OrderInput": {
                        "type": "object",
                        "required": ["itemId", "quantity"],
                        "properties": {
                          "itemId": { "type": "string" },
                          "quantity": { "type": "integer" }
                        }
                      },
                      "OrderOutput": {
                        "type": "object",
                        "properties": {
                          "orderId": { "type": "string" },
                          "status": { "type": "string" }
                        }
                      }
                    }
                  }
                }
                """;

        String body = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract().body().asString();

        String apiId = mapper.readTree(body).get("id").asText();

        // Verify models were created
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/models")
                .then()
                .statusCode(200)
                .body("item", hasSize(2));

        // Verify individual model
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/models/OrderInput")
                .then()
                .statusCode(200)
                .body("name", equalTo("OrderInput"))
                .body("contentType", equalTo("application/json"))
                .body("schema", containsString("itemId"));

        // Verify requestModels on method
        String resourcesBody = given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/resources")
                .then()
                .extract().body().asString();

        String ordersResourceId = null;
        for (JsonNode r : mapper.readTree(resourcesBody).get("item")) {
            if ("/orders".equals(r.get("path").asText())) {
                ordersResourceId = r.get("id").asText();
            }
        }
        assertNotNull(ordersResourceId);

        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/resources/" + ordersResourceId + "/methods/POST")
                .then()
                .statusCode(200)
                .body("requestModels.'application/json'", equalTo("OrderInput"));

        given().delete("/restapis/" + apiId);
    }

    // ──────────────────────────── Request Validators ────────────────────────────

    @Test
    @Order(90)
    void importRestApi_requestValidators() throws Exception {
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "ValidatorAPI", "version": "1.0" },
                  "x-amazon-apigateway-request-validators": {
                    "full": {
                      "validateRequestBody": true,
                      "validateRequestParameters": true
                    },
                    "params-only": {
                      "validateRequestBody": false,
                      "validateRequestParameters": true
                    }
                  },
                  "x-amazon-apigateway-request-validator": "full",
                  "paths": {
                    "/validated": {
                      "post": {
                        "x-amazon-apigateway-integration": { "type": "MOCK",
                          "requestTemplates": { "application/json": "{\\"statusCode\\": 200}" },
                          "responses": { "default": { "statusCode": "200" } } }
                      }
                    },
                    "/params-checked": {
                      "get": {
                        "x-amazon-apigateway-request-validator": "params-only",
                        "x-amazon-apigateway-integration": { "type": "MOCK",
                          "requestTemplates": { "application/json": "{\\"statusCode\\": 200}" },
                          "responses": { "default": { "statusCode": "200" } } }
                      }
                    }
                  }
                }
                """;

        String body = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract().body().asString();

        String apiId = mapper.readTree(body).get("id").asText();

        // Verify validators were created
        String validatorsBody = given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/requestvalidators")
                .then()
                .statusCode(200)
                .body("item", hasSize(2))
                .extract().body().asString();

        // Find the "full" validator
        JsonNode validators = mapper.readTree(validatorsBody).get("item");
        String fullValidatorId = null;
        String paramsOnlyValidatorId = null;
        for (JsonNode v : validators) {
            if ("full".equals(v.get("name").asText())) {
                fullValidatorId = v.get("id").asText();
                assertTrue(v.get("validateRequestBody").asBoolean());
                assertTrue(v.get("validateRequestParameters").asBoolean());
            }
            if ("params-only".equals(v.get("name").asText())) {
                paramsOnlyValidatorId = v.get("id").asText();
                assertFalse(v.get("validateRequestBody").asBoolean());
                assertTrue(v.get("validateRequestParameters").asBoolean());
            }
        }
        assertNotNull(fullValidatorId, "Should have 'full' validator");
        assertNotNull(paramsOnlyValidatorId, "Should have 'params-only' validator");

        // Verify /validated method uses the default "full" validator
        String resourcesBody = given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/resources")
                .then()
                .extract().body().asString();

        String validatedResourceId = null;
        String paramsCheckedResourceId = null;
        for (JsonNode r : mapper.readTree(resourcesBody).get("item")) {
            if ("/validated".equals(r.get("path").asText())) {
                validatedResourceId = r.get("id").asText();
            }
            if ("/params-checked".equals(r.get("path").asText())) {
                paramsCheckedResourceId = r.get("id").asText();
            }
        }

        // Default validator applied to /validated POST
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/resources/" + validatedResourceId + "/methods/POST")
                .then()
                .statusCode(200)
                .body("requestValidatorId", equalTo(fullValidatorId));

        // Operation-level override on /params-checked GET
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/resources/" + paramsCheckedResourceId + "/methods/GET")
                .then()
                .statusCode(200)
                .body("requestValidatorId", equalTo(paramsOnlyValidatorId));

        given().delete("/restapis/" + apiId);
    }

    @Test
    @Order(91)
    void importRestApi_pathLevelValidator() throws Exception {
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "PathValidatorAPI", "version": "1.0" },
                  "x-amazon-apigateway-request-validators": {
                    "full": { "validateRequestBody": true, "validateRequestParameters": true },
                    "body-only": { "validateRequestBody": true, "validateRequestParameters": false }
                  },
                  "x-amazon-apigateway-request-validator": "full",
                  "paths": {
                    "/default-validated": {
                      "get": {
                        "x-amazon-apigateway-integration": { "type": "MOCK",
                          "requestTemplates": {"application/json": "{\\"statusCode\\": 200}"},
                          "responses": {"default": {"statusCode": "200"}} }
                      }
                    },
                    "/path-validated": {
                      "x-amazon-apigateway-request-validator": "body-only",
                      "get": {
                        "x-amazon-apigateway-integration": { "type": "MOCK",
                          "requestTemplates": {"application/json": "{\\"statusCode\\": 200}"},
                          "responses": {"default": {"statusCode": "200"}} }
                      },
                      "post": {
                        "x-amazon-apigateway-request-validator": "full",
                        "x-amazon-apigateway-integration": { "type": "MOCK",
                          "requestTemplates": {"application/json": "{\\"statusCode\\": 200}"},
                          "responses": {"default": {"statusCode": "200"}} }
                      }
                    }
                  }
                }
                """;

        String body = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract().body().asString();

        String apiId = mapper.readTree(body).get("id").asText();

        // Find validators
        String validatorsBody = given()
                .contentType(ContentType.JSON)
                .get("/restapis/" + apiId + "/requestvalidators")
                .then().statusCode(200).extract().body().asString();

        String fullId = null, bodyOnlyId = null;
        for (JsonNode v : mapper.readTree(validatorsBody).get("item")) {
            if ("full".equals(v.get("name").asText())) fullId = v.get("id").asText();
            if ("body-only".equals(v.get("name").asText())) bodyOnlyId = v.get("id").asText();
        }
        assertNotNull(fullId);
        assertNotNull(bodyOnlyId);

        // Find resources
        String resourcesBody = given().get("/restapis/" + apiId + "/resources")
                .then().extract().body().asString();
        String defaultResourceId = null, pathResourceId = null;
        for (JsonNode r : mapper.readTree(resourcesBody).get("item")) {
            if ("/default-validated".equals(r.get("path").asText())) defaultResourceId = r.get("id").asText();
            if ("/path-validated".equals(r.get("path").asText())) pathResourceId = r.get("id").asText();
        }

        // /default-validated GET should use API-level default "full"
        given().contentType(ContentType.JSON)
                .get("/restapis/" + apiId + "/resources/" + defaultResourceId + "/methods/GET")
                .then().statusCode(200)
                .body("requestValidatorId", equalTo(fullId));

        // /path-validated GET should use path-level "body-only"
        given().contentType(ContentType.JSON)
                .get("/restapis/" + apiId + "/resources/" + pathResourceId + "/methods/GET")
                .then().statusCode(200)
                .body("requestValidatorId", equalTo(bodyOnlyId));

        // /path-validated POST should use operation-level "full" (overrides path-level)
        given().contentType(ContentType.JSON)
                .get("/restapis/" + apiId + "/resources/" + pathResourceId + "/methods/POST")
                .then().statusCode(200)
                .body("requestValidatorId", equalTo(fullId));

        given().delete("/restapis/" + apiId);
    }

    // ──────────────────────────── Model CRUD ────────────────────────────

    @Test
    @Order(95)
    void modelCrud_createGetListDelete() throws Exception {
        // Create an API
        String apiBody = given()
                .contentType(ContentType.JSON)
                .body("{\"name\": \"ModelCrudTest\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract().body().asString();
        String apiId = mapper.readTree(apiBody).get("id").asText();

        // Create a model
        String schema = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}";
        given()
                .contentType(ContentType.JSON)
                .body("{\"name\": \"Pet\", \"description\": \"A pet\", \"contentType\": \"application/json\", \"schema\": " + mapper.writeValueAsString(schema) + "}")
                .when()
                .post("/restapis/" + apiId + "/models")
                .then()
                .statusCode(201)
                .body("name", equalTo("Pet"))
                .body("description", equalTo("A pet"))
                .body("contentType", equalTo("application/json"));

        // Get model
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/models/Pet")
                .then()
                .statusCode(200)
                .body("name", equalTo("Pet"))
                .body("schema", containsString("id"));

        // List models
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/models")
                .then()
                .statusCode(200)
                .body("item", hasSize(1));

        // Delete model
        given()
                .when()
                .delete("/restapis/" + apiId + "/models/Pet")
                .then()
                .statusCode(202);

        // Verify deleted
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/models/Pet")
                .then()
                .statusCode(404);

        given().delete("/restapis/" + apiId);
    }

    // ──────────────────────────── Request Body Validation ────────────────────────────

    @Test
    @Order(96)
    void validation_rejectsInvalidBody() throws Exception {
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "BodyValidationAPI", "version": "1.0" },
                  "x-amazon-apigateway-request-validators": {
                    "body-only": {
                      "validateRequestBody": true,
                      "validateRequestParameters": false
                    }
                  },
                  "x-amazon-apigateway-request-validator": "body-only",
                  "paths": {
                    "/items": {
                      "post": {
                        "requestBody": {
                          "content": {
                            "application/json": {
                              "schema": { "$ref": "#/components/schemas/ItemInput" }
                            }
                          }
                        },
                        "x-amazon-apigateway-integration": {
                          "type": "MOCK",
                          "requestTemplates": { "application/json": "{\\"statusCode\\": 200}" },
                          "responses": { "default": { "statusCode": "200",
                            "responseTemplates": { "application/json": "{\\"ok\\": true}" } } }
                        }
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "ItemInput": {
                        "type": "object",
                        "required": ["name", "price"],
                        "properties": {
                          "name": { "type": "string" },
                          "price": { "type": "number" }
                        }
                      }
                    }
                  }
                }
                """;

        String apiBody = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract().body().asString();
        String apiId = mapper.readTree(apiBody).get("id").asText();

        // Deploy
        String deployBody = given()
                .contentType(ContentType.JSON)
                .body("{}")
                .post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().body().asString();
        String deployId = mapper.readTree(deployBody).get("id").asText();
        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deployId + "\"}")
                .post("/restapis/" + apiId + "/stages").then().statusCode(201);

        // Valid request — should pass
        given()
                .contentType(ContentType.JSON)
                .body("{\"name\": \"Widget\", \"price\": 9.99}")
                .when()
                .post("/execute-api/" + apiId + "/test/items")
                .then()
                .statusCode(200)
                .body(containsString("ok"));

        // Missing required field "price" — should fail
        given()
                .contentType(ContentType.JSON)
                .body("{\"name\": \"Widget\"}")
                .when()
                .post("/execute-api/" + apiId + "/test/items")
                .then()
                .statusCode(400)
                .body(containsString("price"));

        // Wrong type for "price" — should fail
        given()
                .contentType(ContentType.JSON)
                .body("{\"name\": \"Widget\", \"price\": \"not-a-number\"}")
                .when()
                .post("/execute-api/" + apiId + "/test/items")
                .then()
                .statusCode(400);

        // Empty body — should fail
        given()
                .contentType(ContentType.JSON)
                .body("")
                .when()
                .post("/execute-api/" + apiId + "/test/items")
                .then()
                .statusCode(400);

        given().delete("/restapis/" + apiId);
    }

    // ──────────────────────────── Request Parameter Validation ────────────────────────────

    @Test
    @Order(97)
    void validation_rejectsMissingRequiredParams() throws Exception {
        // Create API imperatively to test param validation
        String apiBody = given()
                .contentType(ContentType.JSON)
                .body("{\"name\": \"ParamValidationAPI\"}")
                .post("/restapis")
                .then().statusCode(201).extract().body().asString();
        String apiId = mapper.readTree(apiBody).get("id").asText();

        // Get root resource
        String resourcesBody = given().get("/restapis/" + apiId + "/resources")
                .then().extract().body().asString();
        String rootId = null;
        for (JsonNode r : mapper.readTree(resourcesBody).get("item")) {
            if ("/".equals(r.get("path").asText())) rootId = r.get("id").asText();
        }

        // Create /search resource
        String searchBody = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\": \"search\"}")
                .post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().body().asString();
        String searchId = mapper.readTree(searchBody).get("id").asText();

        // Create request validator (params-only)
        String valBody = given()
                .contentType(ContentType.JSON)
                .body("{\"name\": \"params-only\", \"validateRequestBody\": false, \"validateRequestParameters\": true}")
                .post("/restapis/" + apiId + "/requestvalidators")
                .then().statusCode(201).extract().body().asString();
        String validatorId = mapper.readTree(valBody).get("id").asText();

        // Create GET method with required query param and linked validator
        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\": \"NONE\", \"requestValidatorId\": \"" + validatorId + "\", \"requestParameters\": {\"method.request.querystring.q\": true, \"method.request.header.X-Api-Key\": true}}")
                .put("/restapis/" + apiId + "/resources/" + searchId + "/methods/GET")
                .then().statusCode(201);

        // Add MOCK integration
        given()
                .contentType(ContentType.JSON)
                .body("{\"type\": \"MOCK\", \"requestTemplates\": {\"application/json\": \"{\\\"statusCode\\\": 200}\"}, \"passthroughBehavior\": \"WHEN_NO_MATCH\"}")
                .put("/restapis/" + apiId + "/resources/" + searchId + "/methods/GET/integration")
                .then().statusCode(201);
        given()
                .contentType(ContentType.JSON)
                .body("{\"statusCode\": \"200\", \"responseTemplates\": {\"application/json\": \"{\\\"results\\\": []}\"}, \"selectionPattern\": \"\"}")
                .put("/restapis/" + apiId + "/resources/" + searchId + "/methods/GET/integration/responses/200")
                .then().statusCode(201);

        // Deploy
        String deployBody = given()
                .contentType(ContentType.JSON)
                .body("{}")
                .post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().body().asString();
        String deployId = mapper.readTree(deployBody).get("id").asText();
        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deployId + "\"}")
                .post("/restapis/" + apiId + "/stages").then().statusCode(201);

        // Missing query param "q" — should fail
        given()
                .header("X-Api-Key", "test-key")
                .when()
                .get("/execute-api/" + apiId + "/test/search")
                .then()
                .statusCode(400)
                .body(containsString("q"));

        // Missing header "X-Api-Key" — should fail
        given()
                .queryParam("q", "test")
                .when()
                .get("/execute-api/" + apiId + "/test/search")
                .then()
                .statusCode(400)
                .body(containsString("X-Api-Key"));

        // Both present — should pass
        given()
                .queryParam("q", "test")
                .header("X-Api-Key", "test-key")
                .when()
                .get("/execute-api/" + apiId + "/test/search")
                .then()
                .statusCode(200)
                .body(containsString("results"));

        given().delete("/restapis/" + apiId);
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(99)
    void cleanup() {
        if (importedApiId != null) {
            given().delete("/restapis/" + importedApiId);
        }
        if (overwriteApiId != null) {
            given().delete("/restapis/" + overwriteApiId);
        }
    }
}
