package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ApiGatewayPutRestApiMergeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ORIGINAL_SPEC = """
            {
              "openapi": "3.0.1",
              "info": {"title": "BeforeMerge", "version": "1"},
              "x-amazon-apigateway-request-validators": {
                "KeptValidator": {"validateRequestBody": true, "validateRequestParameters": false},
                "ChangedValidator": {"validateRequestBody": false, "validateRequestParameters": true}
              },
              "x-amazon-apigateway-gateway-responses": {
                "DEFAULT_4XX": {"statusCode": "430"},
                "DEFAULT_5XX": {"statusCode": "530"}
              },
              "components": {
                "schemas": {
                  "KeptModel": {"type": "object", "properties": {"keptField": {"type": "string"}}},
                  "ChangedModel": {"type": "object", "properties": {"oldField": {"type": "string"}}}
                },
                "securitySchemes": {
                  "KeptAuth": {
                    "type": "apiKey", "name": "Authorization", "in": "header",
                    "x-amazon-apigateway-authorizer": {
                      "type": "token",
                      "authorizerUri": "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:keptAuth/invocations"
                    }
                  },
                  "ChangedAuth": {
                    "type": "apiKey", "name": "Authorization", "in": "header",
                    "x-amazon-apigateway-authorizer": {
                      "type": "token",
                      "authorizerUri": "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:oldAuth/invocations"
                    }
                  }
                }
              },
              "paths": {
                "/kept": {
                  "post": {
                    "security": [{"KeptAuth": []}],
                    "x-amazon-apigateway-request-validator": "KeptValidator",
                    "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/KeptModel"}}}},
                    "x-amazon-apigateway-integration": {"type": "MOCK"}
                  }
                },
                "/shared": {
                  "get": {
                    "security": [{"ChangedAuth": []}],
                    "x-amazon-apigateway-request-validator": "ChangedValidator",
                    "x-amazon-apigateway-integration": {
                      "type": "http_proxy", "httpMethod": "GET", "uri": "https://example.com/old",
                      "responses": {"default": {"statusCode": "200"}, "legacy": {"statusCode": "206"}}
                    }
                  },
                  "post": {
                    "security": [{"ChangedAuth": []}],
                    "x-amazon-apigateway-request-validator": "ChangedValidator",
                    "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/ChangedModel"}}}},
                    "x-amazon-apigateway-integration": {"type": "MOCK"}
                  }
                }
              }
            }
            """;

    private static final String CHANGED_SPEC = """
            {
              "openapi": "3.0.1",
              "info": {"title": "AfterMerge", "version": "2"},
              "x-amazon-apigateway-request-validators": {
                "ChangedValidator": {"validateRequestBody": true, "validateRequestParameters": true}
              },
              "x-amazon-apigateway-gateway-responses": {
                "DEFAULT_4XX": {"statusCode": "431"}
              },
              "components": {
                "schemas": {
                  "ChangedModel": {"type": "object", "properties": {"newField": {"type": "integer"}}}
                },
                "securitySchemes": {
                  "ChangedAuth": {
                    "type": "apiKey", "name": "Authorization", "in": "header",
                    "x-amazon-apigateway-authorizer": {
                      "type": "token",
                      "authorizerUri": "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:newAuth/invocations"
                    }
                  }
                }
              },
              "paths": {
                "/shared": {
                  "get": {
                    "security": [{"ChangedAuth": []}],
                    "x-amazon-apigateway-request-validator": "ChangedValidator",
                    "x-amazon-apigateway-integration": {
                      "type": "http_proxy", "httpMethod": "GET", "uri": "https://example.com/new",
                      "responses": {"default": {"statusCode": "200"}, "new": {"statusCode": "400"}}
                    }
                  }
                },
                "/new": {"get": {"x-amazon-apigateway-integration": {"type": "MOCK"}}}
              }
            }
            """;

    private static final String MERGE_SPEC = CHANGED_SPEC.replace(
            "\"/new\": {\"get\": {\"x-amazon-apigateway-integration\": {\"type\": \"MOCK\"}}}",
            "\"/new\": {\"get\": {\"x-amazon-apigateway-request-validator\": \"KeptValidator\", "
                    + "\"x-amazon-apigateway-integration\": {\"type\": \"MOCK\"}}}");

    @Test
    void explicitMergePreservesUnmentionedDefinitionsAndReplacesConflicts() throws Exception {
        String apiId = importApi(ORIGINAL_SPEC);
        try {
            JsonNode keptMethodBefore = getJson(methodPath(apiId, "/kept", "POST"));
            assertFalse(keptMethodBefore.path("authorizerId").asText().isBlank());
            assertFalse(keptMethodBefore.path("requestValidatorId").asText().isBlank());
            JsonNode sharedPostBefore = getJson(methodPath(apiId, "/shared", "POST"));
            assertEquals(getJson(methodPath(apiId, "/shared", "GET")).path("authorizerId").asText(),
                    sharedPostBefore.path("authorizerId").asText());

            putSpec(apiId, "merge", MERGE_SPEC);
            assertEquals(Set.of("/", "/kept", "/shared", "/new"), paths(apiId));
            assertEquals("AfterMerge", getJson("/restapis/" + apiId).path("name").asText());

            String keptAfter = methodPath(apiId, "/kept", "POST");
            JsonNode keptMethodAfter = getJson(keptAfter);
            String keptAuthorizerId = keptMethodAfter.path("authorizerId").asText();
            String keptValidatorId = keptMethodAfter.path("requestValidatorId").asText();
            assertFalse(keptAuthorizerId.isBlank());
            assertFalse(keptValidatorId.isBlank());
            assertEquals("KeptModel", keptMethodAfter.path("requestModels").path("application/json").asText());
            assertEquals("KeptAuth", getJson("/restapis/" + apiId + "/authorizers/" + keptAuthorizerId)
                    .path("name").asText());
            assertEquals("KeptValidator", getJson("/restapis/" + apiId + "/requestvalidators/" + keptValidatorId)
                    .path("name").asText());
            assertTrue(getJson("/restapis/" + apiId + "/models/KeptModel").path("schema").asText()
                    .contains("keptField"));
            assertEquals("530", getJson("/restapis/" + apiId + "/gatewayresponses/DEFAULT_5XX")
                    .path("statusCode").asText());

            String sharedGet = methodPath(apiId, "/shared", "GET");
            JsonNode changedMethodAfter = getJson(sharedGet);
            JsonNode sharedPostAfter = getJson(methodPath(apiId, "/shared", "POST"));
            String changedAuthorizerId = changedMethodAfter.path("authorizerId").asText();
            String changedValidatorId = changedMethodAfter.path("requestValidatorId").asText();
            assertEquals(changedAuthorizerId, sharedPostAfter.path("authorizerId").asText());
            assertEquals(changedValidatorId, sharedPostAfter.path("requestValidatorId").asText());
            assertEquals("ChangedModel", sharedPostAfter.path("requestModels").path("application/json").asText());
            assertEquals("https://example.com/new", getJson(sharedGet + "/integration").path("uri").asText());
            assertEquals(200, status(sharedGet + "/responses/200"));
            assertEquals(200, status(sharedGet + "/responses/400"));
            assertEquals(404, status(sharedGet + "/responses/206"));
            assertEquals(200, status(sharedGet + "/integration/responses/400"));
            assertEquals(404, status(sharedGet + "/integration/responses/206"));
            assertEquals(200, status(methodPath(apiId, "/shared", "POST")));
            assertEquals(200, status(methodPath(apiId, "/new", "GET")));
            String reusedValidatorId = getJson(methodPath(apiId, "/new", "GET"))
                    .path("requestValidatorId").asText();
            assertEquals("KeptValidator", getJson("/restapis/" + apiId + "/requestvalidators/" + reusedValidatorId)
                    .path("name").asText());

            assertEquals(2, getJson("/restapis/" + apiId + "/authorizers").path("item").size());
            assertEquals(2, getJson("/restapis/" + apiId + "/requestvalidators").path("item").size());
            assertTrue(getJson("/restapis/" + apiId + "/models/ChangedModel").path("schema").asText()
                    .contains("newField"));
            assertFalse(getJson("/restapis/" + apiId + "/models/ChangedModel").path("schema").asText()
                    .contains("oldField"));
            assertEquals("431", getJson("/restapis/" + apiId + "/gatewayresponses/DEFAULT_4XX")
                    .path("statusCode").asText());
            assertTrue(getJson("/restapis/" + apiId + "/authorizers/" + changedAuthorizerId)
                    .path("authorizerUri").asText().contains("newAuth"));
            assertTrue(getJson("/restapis/" + apiId + "/requestvalidators/" + changedValidatorId)
                    .path("validateRequestBody").asBoolean());

            putSpec(apiId, "merge", MERGE_SPEC);
            assertEquals(Set.of("/", "/kept", "/shared", "/new"), paths(apiId));
            assertEquals(2, getJson("/restapis/" + apiId + "/authorizers").path("item").size());
            assertEquals(2, getJson("/restapis/" + apiId + "/requestvalidators").path("item").size());
            String keptAuthorizerAfterRepeat = getJson(methodPath(apiId, "/kept", "POST"))
                    .path("authorizerId").asText();
            assertEquals("KeptAuth", getJson("/restapis/" + apiId + "/authorizers/" + keptAuthorizerAfterRepeat)
                    .path("name").asText());
        } finally {
            given().delete("/restapis/" + apiId);
        }
    }

    @Test
    void omittedModeMergesByDefault() throws Exception {
        String apiId = importApi(ORIGINAL_SPEC);
        try {
            putSpec(apiId, null, MERGE_SPEC);
            assertEquals(Set.of("/", "/kept", "/shared", "/new"), paths(apiId));
            assertEquals(200, status(methodPath(apiId, "/shared", "POST")));
            assertEquals("530", getJson("/restapis/" + apiId + "/gatewayresponses/DEFAULT_5XX")
                    .path("statusCode").asText());
        } finally {
            given().delete("/restapis/" + apiId);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"merg", "Merge", "MERGE", " merge"})
    void invalidModeIsRejectedBeforeAnyMutation(String mode) throws Exception {
        String apiId = importApi(ORIGINAL_SPEC);
        try {
            given().contentType(ContentType.JSON).queryParam("mode", mode).body(MERGE_SPEC)
                    .when().put("/restapis/" + apiId).then().statusCode(400);
            assertOriginalApi(apiId);
        } finally {
            given().delete("/restapis/" + apiId);
        }
    }

    @Test
    void overwriteStillRemovesUnmentionedDefinitions() throws Exception {
        String apiId = importApi(ORIGINAL_SPEC);
        try {
            putSpec(apiId, "overwrite", CHANGED_SPEC);
            assertEquals(Set.of("/", "/shared", "/new"), paths(apiId));
            assertEquals(404, status(methodPath(apiId, "/shared", "POST")));
            assertEquals(404, status("/restapis/" + apiId + "/models/KeptModel"));
            assertEquals(1, getJson("/restapis/" + apiId + "/authorizers").path("item").size());
            assertEquals(1, getJson("/restapis/" + apiId + "/requestvalidators").path("item").size());
            assertTrue(getJson("/restapis/" + apiId + "/gatewayresponses/DEFAULT_5XX")
                    .path("defaultResponse").asBoolean());
        } finally {
            given().delete("/restapis/" + apiId);
        }
    }

    @Test
    void failedMergeLeavesOriginalApiAndDefinitionsIntact() throws Exception {
        String apiId = importApi(ORIGINAL_SPEC);
        try {
            String warningSpec = """
                    {"openapi":"3.0.1","info":{"title":"RejectedByWarnings","version":"2"},
                     "paths":{"/attempted":{"get":{"security":"not-an-array",
                       "responses":{"200":{"description":"ok"}}}}}}
                    """;
            given().contentType(ContentType.JSON).queryParam("mode", "merge")
                    .queryParam("failonwarnings", true).body(warningSpec)
                    .when().put("/restapis/" + apiId).then().statusCode(400);
            assertOriginalApi(apiId);

            String lateFailureSpec = """
                    {
                      "openapi": "3.0.1",
                      "info": {"title": "RejectedLate", "version": "2"},
                      "x-amazon-apigateway-request-validators": {
                        "ChangedValidator": {"validateRequestBody": true, "validateRequestParameters": true}
                      },
                      "paths": {
                        "/attempted": {"get": {"x-amazon-apigateway-integration": {"type": "MOCK"}}},
                        "/broken": {"get": {
                          "parameters": [
                            {"name": "filter[a]", "in": "query", "required": true,
                             "schema": {"type": "string"}}
                          ],
                          "responses": {"200": {"description": "ok"}}
                        }}
                      }
                    }
                    """;
            String failureBody = given().contentType(ContentType.JSON)
                    .queryParam("mode", "merge").body(lateFailureSpec)
                    .when().put("/restapis/" + apiId).then().statusCode(400)
                    .extract().body().asString();
            assertTrue(MAPPER.readTree(failureBody).path("message").asText()
                    .contains("Unable to put method 'GET' on resource at path '/broken'"));
            assertOriginalApi(apiId);
        } finally {
            given().delete("/restapis/" + apiId);
        }
    }

    private static void assertOriginalApi(String apiId) throws Exception {
        assertEquals("BeforeMerge", getJson("/restapis/" + apiId).path("name").asText());
        assertEquals(Set.of("/", "/kept", "/shared"), paths(apiId));
        assertEquals("https://example.com/old",
                getJson(methodPath(apiId, "/shared", "GET") + "/integration").path("uri").asText());
        assertEquals(2, getJson("/restapis/" + apiId + "/authorizers").path("item").size());
        assertEquals(2, getJson("/restapis/" + apiId + "/requestvalidators").path("item").size());
        assertEquals("430", getJson("/restapis/" + apiId + "/gatewayresponses/DEFAULT_4XX")
                .path("statusCode").asText());
        JsonNode keptMethod = getJson(methodPath(apiId, "/kept", "POST"));
        assertEquals("KeptAuth", getJson("/restapis/" + apiId + "/authorizers/"
                + keptMethod.path("authorizerId").asText()).path("name").asText());
        String changedValidatorId = getJson(methodPath(apiId, "/shared", "GET"))
                .path("requestValidatorId").asText();
        JsonNode changedValidator = getJson("/restapis/" + apiId + "/requestvalidators/"
                + changedValidatorId);
        assertEquals("ChangedValidator", changedValidator.path("name").asText());
        assertFalse(changedValidator.path("validateRequestBody").asBoolean());
        assertTrue(changedValidator.path("validateRequestParameters").asBoolean());
    }

    private static String importApi(String spec) throws Exception {
        String body = given().contentType(ContentType.JSON).queryParam("mode", "import").body(spec)
                .when().post("/restapis").then().statusCode(201).extract().body().asString();
        return MAPPER.readTree(body).path("id").asText();
    }

    private static void putSpec(String apiId, String mode, String spec) {
        if (mode == null) {
            given().contentType(ContentType.JSON).body(spec)
                    .when().put("/restapis/" + apiId).then().statusCode(200);
        } else {
            given().contentType(ContentType.JSON).queryParam("mode", mode).body(spec)
                    .when().put("/restapis/" + apiId).then().statusCode(200);
        }
    }

    private static JsonNode getJson(String path) throws Exception {
        String body = given().when().get(path).then().statusCode(200).extract().body().asString();
        return MAPPER.readTree(body);
    }

    private static int status(String path) {
        return given().when().get(path).getStatusCode();
    }

    private static Set<String> paths(String apiId) throws Exception {
        Set<String> paths = new HashSet<>();
        for (JsonNode resource : getJson("/restapis/" + apiId + "/resources").path("item")) {
            paths.add(resource.path("path").asText());
        }
        return paths;
    }

    private static String methodPath(String apiId, String path, String method) throws Exception {
        JsonNode resource = null;
        for (JsonNode candidate : getJson("/restapis/" + apiId + "/resources").path("item")) {
            if (path.equals(candidate.path("path").asText())) {
                resource = candidate;
                break;
            }
        }
        assertNotNull(resource, "Missing resource " + path);
        return "/restapis/" + apiId + "/resources/" + resource.path("id").asText()
                + "/methods/" + method;
    }
}
