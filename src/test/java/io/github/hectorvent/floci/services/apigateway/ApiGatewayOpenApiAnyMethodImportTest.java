package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that importing an OpenAPI spec using the AWS vendor extension
 * "x-amazon-apigateway-any-method" (a pseudo-operation matching every HTTP verb, distinct
 * from the standard OpenAPI "get"/"post"/... operation keywords) actually creates an ANY
 * method + integration, instead of being silently dropped.
 *
 * <p>Unlike {@link ApiGatewayAnyMethodIntegrationTest} (which covers ANY methods created
 * directly via PutMethod, see issue #710), this covers the OpenAPI *import* path: the
 * swagger parser never surfaces "x-amazon-apigateway-any-method" via
 * {@code PathItem.readOperationsMap()} since it isn't a real OpenAPI operation keyword, so
 * without special handling it lands only in the PathItem's raw extensions map and is ignored.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayOpenApiAnyMethodImportTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static String apiId;
    private static String anyResourceId;

    @Test
    @Order(1)
    void importRestApi_withAnyMethodExtension() throws Exception {
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": {
                    "title": "AnyMethodImportAPI",
                    "version": "1.0"
                  },
                  "paths": {
                    "/echo": {
                      "x-amazon-apigateway-any-method": {
                        "responses": {
                          "200": {
                            "description": "200 response"
                          }
                        },
                        "x-amazon-apigateway-integration": {
                          "type": "MOCK",
                          "requestTemplates": {
                            "application/json": "{\\"statusCode\\": 200}"
                          },
                          "responses": {
                            "default": {
                              "statusCode": "200",
                              "responseTemplates": {
                                "application/json": "{\\"matched\\": \\"any\\"}"
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
                .body("id", notNullValue())
                .extract().body().asString();

        JsonNode node = mapper.readTree(body);
        apiId = node.get("id").asText();
    }

    @Test
    @Order(2)
    void importedAnyMethod_isRegisteredOnResource() throws Exception {
        String body = given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .extract().body().asString();

        JsonNode resources = mapper.readTree(body).get("item");
        for (JsonNode r : resources) {
            if ("/echo".equals(r.get("path").asText())) {
                anyResourceId = r.get("id").asText();
            }
        }
        assertNotNull(anyResourceId, "Should have /echo resource");

        // The method must exist as "ANY" (not silently dropped, and not e.g. "GET").
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/resources/" + anyResourceId + "/methods/ANY")
                .then()
                .statusCode(200)
                .body("httpMethod", equalTo("ANY"));
    }

    @Test
    @Order(3)
    void importedAnyMethod_hasMockIntegration() {
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/restapis/" + apiId + "/resources/" + anyResourceId + "/methods/ANY/integration")
                .then()
                .statusCode(200)
                .body("type", equalTo("MOCK"));
    }

    @Test
    @Order(4)
    void deployAndInvoke_concreteMethodsMatchImportedAnyMethod() throws Exception {
        String deploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"v1\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then()
                .statusCode(201);

        // Concrete HTTP verbs must all be routed through the imported ANY method's mock integration.
        for (String verb : new String[] { "GET", "POST", "PUT", "DELETE" }) {
            String responseBody = given()
                    .contentType(ContentType.JSON)
                    .when()
                    .request(verb, "/execute-api/" + apiId + "/test/echo")
                    .then()
                    .statusCode(200)
                    .extract().body().asString();
            JsonNode node = mapper.readTree(responseBody);
            assertEquals("any", node.get("matched").asText(), verb + " should hit the imported ANY method");
        }
    }

    @Test
    @Order(5)
    void cleanup() {
        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }

    @Test
    @Order(6)
    void import_withMalformedAnyMethodExtension_returnsCleanBadRequest() {
        // "x-amazon-apigateway-any-method" set to a plain string instead of an operation object.
        // It is not an Operation object and must be rejected as a BadRequestException, not escape as
        // an uncaught exception (which would surface as a raw 500).
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": {
                    "title": "MalformedAnyMethodAPI",
                    "version": "1.0"
                  },
                  "paths": {
                    "/broken": {
                      "x-amazon-apigateway-any-method": "not-an-operation-object"
                    }
                  }
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when()
                .post("/restapis")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(7)
    void import_withMalformedIntegrationExtension_returnsCleanBadRequest() {
        // "x-amazon-apigateway-integration" set to an array instead of an integration object. The
        // cast to Map<String, Object> must be guarded so this can't escape as an uncaught
        // ClassCastException (which would surface as a raw 500).
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": {
                    "title": "MalformedIntegrationAPI",
                    "version": "1.0"
                  },
                  "paths": {
                    "/broken": {
                      "get": {
                        "responses": {
                          "200": {
                            "description": "200 response"
                          }
                        },
                        "x-amazon-apigateway-integration": ["not", "an", "object"]
                      }
                    }
                  }
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when()
                .post("/restapis")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(8)
    void importedAnyMethod_keepsPathParametersAndIntegrationRequestParameters() throws Exception {
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "AnyMethodProxyAPI", "version": "1.0"},
                  "paths": {
                    "/p/{proxy+}": {
                      "x-amazon-apigateway-any-method": {
                        "parameters": [
                          {"name": "proxy", "in": "path", "required": true, "schema": {"type": "string"}}
                        ],
                        "security": [],
                        "x-amazon-apigateway-integration": {
                          "type": "http_proxy",
                          "httpMethod": "ANY",
                          "uri": "http://example.com/{proxy}",
                          "requestParameters": {
                            "integration.request.path.proxy": "method.request.path.proxy"
                          }
                        }
                      }
                    }
                  }
                }
                """;

        String proxyApiId = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .extract().path("id");

        try {
            String resourceId = resourceIdForPath(proxyApiId, "/p/{proxy+}");
            given()
                    .when().get("/restapis/" + proxyApiId + "/resources/" + resourceId + "/methods/ANY")
                    .then()
                    .statusCode(200)
                    .body("httpMethod", equalTo("ANY"))
                    .body("requestParameters.'method.request.path.proxy'", equalTo(true));
            given()
                    .when().get("/restapis/" + proxyApiId + "/resources/" + resourceId + "/methods/ANY/integration")
                    .then()
                    .statusCode(200)
                    .body("uri", equalTo("http://example.com/{proxy}"))
                    .body("requestParameters.'integration.request.path.proxy'",
                            equalTo("method.request.path.proxy"));
        } finally {
            given().when().delete("/restapis/" + proxyApiId).then().statusCode(202);
        }
    }

    @Test
    @Order(9)
    void failedImport_leavesNoRestApiBehind() {
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "FailedImportLeavesNothingAPI", "version": "1.0"},
                  "paths": {
                    "/p/{proxy+}": {
                      "x-amazon-apigateway-any-method": "not-an-operation-object"
                    }
                  }
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when().post("/restapis")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));

        given()
                .when().get("/restapis")
                .then()
                .statusCode(200)
                .body("item.name", not(hasItem("FailedImportLeavesNothingAPI")));
    }

    @Test
    @Order(10)
    void failedPutRestApi_leavesExistingApiUnchanged() throws Exception {
        String original = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "PutRollbackAPI", "version": "1.0"},
                  "paths": {
                    "/kept": {
                      "get": {
                        "x-amazon-apigateway-integration": {
                          "type": "http_proxy", "httpMethod": "GET", "uri": "http://example.com/kept"
                        }
                      }
                    }
                  }
                }
                """;
        String broken = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "PutRollbackAPIRenamed", "version": "1.0"},
                  "paths": {
                    "/p/{proxy+}": {
                      "x-amazon-apigateway-any-method": "not-an-operation-object"
                    }
                  }
                }
                """;

        String putApiId = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(original)
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .extract().path("id");

        try {
            String keptResourceId = resourceIdForPath(putApiId, "/kept");

            given()
                    .contentType(ContentType.JSON)
                    .queryParam("mode", "overwrite")
                    .body(broken)
                    .when().put("/restapis/" + putApiId)
                    .then()
                    .statusCode(400)
                    .body("__type", equalTo("BadRequestException"));

            given()
                    .when().get("/restapis/" + putApiId)
                    .then()
                    .statusCode(200)
                    .body("name", equalTo("PutRollbackAPI"));
            given()
                    .when().get("/restapis/" + putApiId + "/resources")
                    .then()
                    .statusCode(200)
                    .body("item.path", not(hasItem("/p")))
                    .body("item.path", not(hasItem("/p/{proxy+}")));
            assertEquals(keptResourceId, resourceIdForPath(putApiId, "/kept"));
            given()
                    .when().get("/restapis/" + putApiId + "/resources/" + keptResourceId + "/methods/GET/integration")
                    .then()
                    .statusCode(200)
                    .body("uri", equalTo("http://example.com/kept"));
        } finally {
            given().when().delete("/restapis/" + putApiId).then().statusCode(202);
        }
    }

    private static String resourceIdForPath(String restApiId, String path) throws Exception {
        String body = given()
                .when().get("/restapis/" + restApiId + "/resources")
                .then()
                .statusCode(200)
                .extract().body().asString();
        for (JsonNode resource : mapper.readTree(body).get("item")) {
            if (path.equals(resource.get("path").asText())) {
                return resource.get("id").asText();
            }
        }
        throw new AssertionError("No resource with path " + path + " in " + body);
    }
}
