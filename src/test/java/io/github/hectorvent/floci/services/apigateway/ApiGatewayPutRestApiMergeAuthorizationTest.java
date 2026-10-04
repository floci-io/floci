package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@QuarkusTest
class ApiGatewayPutRestApiMergeAuthorizationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUTHORIZER_FUNCTION = "deniedAuth";
    private static final String AUTHORIZER_URI = "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
            + "arn:aws:lambda:us-east-1:000000000000:function:" + AUTHORIZER_FUNCTION + "/invocations";
    private static final String REPLACEMENT_FUNCTION = "replacementAuth";
    private static final String REPLACEMENT_URI = "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
            + "arn:aws:lambda:us-east-1:000000000000:function:" + REPLACEMENT_FUNCTION + "/invocations";
    private static final String ORIGINAL_SPEC = """
            {
              "openapi": "3.0.1",
              "info": {"title": "Protected", "version": "1"},
              "components": {"securitySchemes": {
                "Guard": {
                  "type": "apiKey", "name": "Authorization", "in": "header",
                  "x-amazon-apigateway-authorizer": {
                    "type": "token", "authorizerUri": "%s"
                  }
                }
              }},
              "paths": {"/protected": {"get": {
                "security": [{"Guard": []}],
                "x-amazon-apigateway-integration": {"type": "MOCK"}
              }}}
            }
            """.formatted(AUTHORIZER_URI);
    private static final String MISSING_URI_SPEC = """
            {
              "openapi": "3.0.1",
              "info": {"title": "MissingUri", "version": "1"},
              "components": {"securitySchemes": {
                "Guard": {
                  "type": "apiKey", "name": "Authorization", "in": "header",
                  "x-amazon-apigateway-authorizer": {"type": "token"}
                }
              }},
              "paths": {"/protected": {"get": {
                "security": [{"Guard": []}],
                "x-amazon-apigateway-integration": {"type": "MOCK"}
              }}}
            }
            """;
    private static final String PARTIAL_AUTHORIZER_SPEC = """
            {
              "openapi": "3.0.1",
              "info": {"title": "PartialAuthorizer", "version": "2"},
              "components": {"securitySchemes": {
                "Guard": {
                  "type": "apiKey", "name": "Authorization", "in": "header",
                  "x-amazon-apigateway-authorizer": {"type": "token"%s}
                }
              }},
              "paths": {"/added": {"get": {
                "x-amazon-apigateway-integration": {"type": "MOCK"}
              }}}
            }
            """;
    private static final String REPLACEMENT_URI_SPEC = """
            {
              "openapi": "3.0.1",
              "info": {"title": "ReplacementUri", "version": "2"},
              "components": {"securitySchemes": {
                "Guard": {
                  "type": "apiKey", "name": "Authorization", "in": "header",
                  "x-amazon-apigateway-authorizer": {"type": "token", "authorizerUri": "%s"}
                }
              }},
              "paths": {"/added": {"get": {
                "x-amazon-apigateway-integration": {"type": "MOCK"}
              }}}
            }
            """.formatted(REPLACEMENT_URI);
    private static final String RETAINED_SCHEME_SPEC = """
            {
              "openapi": "3.0.1",
              "info": {"title": "RetainedScheme", "version": "2"},
              "paths": {"/protected": {"get": {
                "security": [{"Guard": []}],
                "x-amazon-apigateway-integration": {"type": "MOCK"}
              }}}
            }
            """;
    private static final String ROOT_SECURITY_SPEC = """
            {
              "openapi": "3.0.1",
              "info": {"title": "RootSecurity", "version": "2"},
              "security": [{"Guard": []}],
              "paths": {"/protected": {"get": {
                "x-amazon-apigateway-integration": {"type": "MOCK"}
              }}}
            }
            """;
    private static final String NO_SECURITY_SPEC = """
            {
              "openapi": "3.0.1",
              "info": {"title": "NoSecurity", "version": "2"},
              "paths": {"/protected": {"get": {
                "x-amazon-apigateway-integration": {"type": "MOCK"}
              }}}
            }
            """;
    private static final String EMPTY_SECURITY_SPEC = """
            {
              "openapi": "3.0.1",
              "info": {"title": "EmptySecurity", "version": "3"},
              "security": [{"Guard": []}],
              "paths": {"/protected": {"get": {
                "security": [],
                "x-amazon-apigateway-integration": {"type": "MOCK"}
              }}}
            }
            """;
    private static final String COGNITO_SPEC = """
            {
              "openapi": "3.0.1",
              "info": {"title": "CognitoProtected", "version": "1"},
              "components": {"securitySchemes": {
                "Guard": {
                  "type": "apiKey", "name": "Authorization", "in": "header",
                  "x-amazon-apigateway-authorizer": {
                    "type": "cognito_user_pools",
                    "providerARNs": ["arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_ABC123"]
                  }
                }
              }},
              "paths": {"/protected": {"get": {
                "security": [{"Guard": []}],
                "x-amazon-apigateway-integration": {"type": "MOCK"}
              }}}
            }
            """;
    private static final String COGNITO_TYPE_REPLACEMENT_SPEC = """
            {
              "openapi": "3.0.1",
              "info": {"title": "CognitoReplacement", "version": "2"},
              "components": {"securitySchemes": {
                "Guard": {
                  "type": "apiKey", "name": "Authorization", "in": "header",
                  "x-amazon-apigateway-authorizer": {
                    "type": "cognito_user_pools",
                    "providerARNs": ["arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_ABC123"]
                  }
                }
              }},
              "paths": {"/added": {"get": {
                "x-amazon-apigateway-integration": {"type": "MOCK"}
              }}}
            }
            """;
    private static final String LAMBDA_TYPE_REPLACEMENT_SPEC = """
            {
              "openapi": "3.0.1",
              "info": {"title": "LambdaReplacement", "version": "2"},
              "components": {"securitySchemes": {
                "Guard": {
                  "type": "apiKey", "name": "Authorization", "in": "header",
                  "x-amazon-apigateway-authorizer": {
                    "type": "token", "authorizerUri": "%s"
                  }
                }
              }},
              "paths": {"/added": {"get": {
                "x-amazon-apigateway-integration": {"type": "MOCK"}
              }}}
            }
            """.formatted(AUTHORIZER_URI);
    private static final String EXPLICIT_SCHEME_SPEC = """
            {
              "openapi": "3.0.1",
              "info": {"title": "ExplicitScheme", "version": "2"},
              "components": {"securitySchemes": {
                "Guard": {
                  "type": "apiKey", "name": "Authorization", "in": "header"%s
                }
              }},
              "paths": {"/protected": {"get": {
                "security": [{"Guard": []}],
                "x-amazon-apigateway-integration": {"type": "MOCK"}
              }}}
            }
            """;
    private static final byte[] DENY_POLICY = """
            {"principalId":"blocked","policyDocument":{"Version":"2012-10-17","Statement":[
              {"Effect":"Deny","Action":"execute-api:Invoke","Resource":"*"}
            ]}}
            """.getBytes(StandardCharsets.UTF_8);
    private static final byte[] ALLOW_POLICY = """
            {"principalId":"allowed","policyDocument":{"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"execute-api:Invoke","Resource":"*"}
            ]}}
            """.getBytes(StandardCharsets.UTF_8);

    @InjectMock
    LambdaService lambdaService;

    @ParameterizedTest
    @ValueSource(strings = {"", ", \"authorizerUri\": null"})
    void retainedMethodStaysDeniedWhenSameNameAuthorizerHasNoNewUri(String uriFragment) throws Exception {
        String apiId = importApi();
        try {
            denyAuthorizerRequests();
            String methodPath = methodPath(apiId);
            String authorizerId = getJson(methodPath).path("authorizerId").asText();
            deploy(apiId, "before");
            assertEquals(403, invoke(apiId, "before"));
            verifyDenyInvocations(1);

            merge(apiId, PARTIAL_AUTHORIZER_SPEC.formatted(uriFragment));
            deploy(apiId, "after");
            int afterStatus = invoke(apiId, "after");
            JsonNode method = getJson(methodPath(apiId));
            JsonNode authorizer = getJson("/restapis/" + apiId + "/authorizers/" + authorizerId);
            assertAll(
                    () -> assertEquals(403, afterStatus),
                    () -> assertEquals("CUSTOM", method.path("authorizationType").asText()),
                    () -> assertEquals(authorizerId, method.path("authorizerId").asText()),
                    () -> assertEquals(AUTHORIZER_URI, authorizer.path("authorizerUri").asText(null)),
                    () -> verifyDenyInvocations(2));
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void boundCustomAuthorizerWithoutUriDoesNotExecuteIntegration() throws Exception {
        String apiId = importApi(MISSING_URI_SPEC);
        try {
            JsonNode method = getJson(methodPath(apiId));
            JsonNode authorizer = getJson("/restapis/" + apiId + "/authorizers/"
                    + method.path("authorizerId").asText());
            assertAll(
                    () -> assertEquals("CUSTOM", method.path("authorizationType").asText()),
                    () -> assertTrue(!method.path("authorizerId").asText().isBlank()),
                    () -> assertTrue(authorizer.path("authorizerUri").isMissingNode()
                            || authorizer.path("authorizerUri").isNull()));
            deploy(apiId, "missing-uri");
            assertEquals(500, invoke(apiId, "missing-uri"));
            verifyNoInteractions(lambdaService);
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void boundCustomMethodDoesNotInvokeCognitoAuthorizerWithLambdaUri() throws Exception {
        String apiId = importApi(COGNITO_SPEC);
        try {
            String methodPath = methodPath(apiId);
            String authorizerId = getJson(methodPath).path("authorizerId").asText();
            given().contentType(ContentType.JSON)
                    .body(Map.of("patchOperations", List.of(Map.of(
                            "op", "replace", "path", "/authorizerUri", "value", AUTHORIZER_URI))))
                    .patch("/restapis/" + apiId + "/authorizers/" + authorizerId)
                    .then().statusCode(200);
            given().contentType(ContentType.JSON)
                    .body(Map.of("patchOperations", List.of(Map.of(
                            "op", "replace", "path", "/authorizationType", "value", "CUSTOM"))))
                    .patch(methodPath).then().statusCode(200);
            JsonNode method = getJson(methodPath);
            JsonNode authorizer = getJson("/restapis/" + apiId + "/authorizers/" + authorizerId);
            assertAll(
                    () -> assertEquals("CUSTOM", method.path("authorizationType").asText()),
                    () -> assertEquals(authorizerId, method.path("authorizerId").asText()),
                    () -> assertEquals("COGNITO_USER_POOLS", authorizer.path("type").asText()),
                    () -> assertEquals(AUTHORIZER_URI, authorizer.path("authorizerUri").asText()));

            when(lambdaService.invoke(eq("us-east-1"), eq(AUTHORIZER_FUNCTION), any(byte[].class),
                    eq(InvocationType.RequestResponse)))
                    .thenReturn(new InvokeResult(200, null, ALLOW_POLICY, null, "authorizer-request"));
            deploy(apiId, "mismatched-type");
            int status = invoke(apiId, "mismatched-type");
            assertAll(
                    () -> assertEquals(500, status),
                    () -> verifyNoInteractions(lambdaService));
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void replacedMethodStaysDeniedWhenSecurityReferencesRetainedScheme() throws Exception {
        String apiId = importApi();
        try {
            denyAuthorizerRequests();
            String authorizerId = getJson(methodPath(apiId)).path("authorizerId").asText();
            deploy(apiId, "before");
            assertEquals(403, invoke(apiId, "before"));
            verifyDenyInvocations(1);

            merge(apiId, RETAINED_SCHEME_SPEC);
            deploy(apiId, "after");
            int afterStatus = invoke(apiId, "after");
            JsonNode method = getJson(methodPath(apiId));
            assertAll(
                    () -> assertEquals(403, afterStatus),
                    () -> assertEquals("CUSTOM", method.path("authorizationType").asText()),
                    () -> assertEquals(authorizerId, method.path("authorizerId").asText()),
                    () -> verifyDenyInvocations(2));
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void defaultMergeResolvesRetainedSchemeFromRootSecurity() throws Exception {
        String apiId = importApi();
        try {
            denyAuthorizerRequests();
            String authorizerId = getJson(methodPath(apiId)).path("authorizerId").asText();
            putSpec(apiId, null, ROOT_SECURITY_SPEC);
            deploy(apiId, "after");
            int afterStatus = invoke(apiId, "after");
            JsonNode method = getJson(methodPath(apiId));
            assertAll(
                    () -> assertEquals(403, afterStatus),
                    () -> assertEquals("CUSTOM", method.path("authorizationType").asText()),
                    () -> assertEquals(authorizerId, method.path("authorizerId").asText()),
                    () -> verifyDenyInvocations(1));
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void incomingAuthorizerUriReplacesRetainedUri() throws Exception {
        String apiId = importApi();
        try {
            denyAuthorizerRequests();
            denyAuthorizerRequests(REPLACEMENT_FUNCTION);
            String authorizerId = getJson(methodPath(apiId)).path("authorizerId").asText();
            deploy(apiId, "before");
            assertEquals(403, invoke(apiId, "before"));
            verifyDenyInvocations(1);

            merge(apiId, REPLACEMENT_URI_SPEC);
            deploy(apiId, "after");
            int afterStatus = invoke(apiId, "after");
            JsonNode authorizer = getJson("/restapis/" + apiId + "/authorizers/" + authorizerId);
            assertAll(
                    () -> assertEquals(403, afterStatus),
                    () -> assertEquals(REPLACEMENT_URI, authorizer.path("authorizerUri").asText(null)),
                    () -> verifyDenyInvocations(1),
                    () -> verifyDenyInvocations(REPLACEMENT_FUNCTION, 1));
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void replacementWithoutSecurityAndExplicitEmptySecurityRemainPublic() throws Exception {
        String apiId = importApi();
        try {
            denyAuthorizerRequests();
            merge(apiId, NO_SECURITY_SPEC);
            JsonNode noSecurityMethod = getJson(methodPath(apiId));
            deploy(apiId, "no-security");
            assertAll(
                    () -> assertEquals("NONE", noSecurityMethod.path("authorizationType").asText()),
                    () -> assertEquals(200, invoke(apiId, "no-security")),
                    () -> verifyDenyInvocations(0));

            merge(apiId, EMPTY_SECURITY_SPEC);
            JsonNode emptySecurityMethod = getJson(methodPath(apiId));
            deploy(apiId, "empty-security");
            assertAll(
                    () -> assertEquals("NONE", emptySecurityMethod.path("authorizationType").asText()),
                    () -> assertEquals(200, invoke(apiId, "empty-security")),
                    () -> verifyDenyInvocations(0));
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void retainedCognitoSchemeKeepsItsAuthorizationType() throws Exception {
        String apiId = importApi(COGNITO_SPEC);
        try {
            String authorizerId = getJson(methodPath(apiId)).path("authorizerId").asText();
            merge(apiId, RETAINED_SCHEME_SPEC);
            JsonNode method = getJson(methodPath(apiId));
            assertAll(
                    () -> assertEquals("COGNITO_USER_POOLS", method.path("authorizationType").asText()),
                    () -> assertEquals(authorizerId, method.path("authorizerId").asText()));
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void retainedLambdaMethodUsesCognitoAuthorizationAfterSameNameTypeReplacement() throws Exception {
        String apiId = importApi();
        try {
            denyAuthorizerRequests();
            String authorizerId = getJson(methodPath(apiId)).path("authorizerId").asText();
            deploy(apiId, "before");
            assertEquals(403, invoke(apiId, "before"));

            merge(apiId, COGNITO_TYPE_REPLACEMENT_SPEC);
            deploy(apiId, "after");
            JsonNode method = getJson(methodPath(apiId));
            JsonNode authorizer = getJson("/restapis/" + apiId + "/authorizers/" + authorizerId);
            int afterStatus = invokeWithoutToken(apiId, "after");
            assertAll(
                    () -> assertEquals(401, afterStatus),
                    () -> assertEquals("COGNITO_USER_POOLS", authorizer.path("type").asText()),
                    () -> assertEquals("COGNITO_USER_POOLS", method.path("authorizationType").asText()),
                    () -> assertEquals(authorizerId, method.path("authorizerId").asText()),
                    () -> verifyDenyInvocations(1));
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void retainedCognitoMethodUsesLambdaAuthorizationAfterSameNameTypeReplacement() throws Exception {
        String apiId = importApi(COGNITO_SPEC);
        try {
            String authorizerId = getJson(methodPath(apiId)).path("authorizerId").asText();
            deploy(apiId, "before");
            assertEquals(401, invokeWithoutToken(apiId, "before"));

            denyAuthorizerRequests();
            merge(apiId, LAMBDA_TYPE_REPLACEMENT_SPEC);
            deploy(apiId, "after");
            JsonNode method = getJson(methodPath(apiId));
            JsonNode authorizer = getJson("/restapis/" + apiId + "/authorizers/" + authorizerId);
            int afterStatus = invoke(apiId, "after");
            assertAll(
                    () -> assertEquals(403, afterStatus),
                    () -> assertEquals("TOKEN", authorizer.path("type").asText()),
                    () -> assertEquals("CUSTOM", method.path("authorizationType").asText()),
                    () -> assertEquals(authorizerId, method.path("authorizerId").asText()),
                    () -> verifyDenyInvocations(1));
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ", \"authorizerUri\": null", ", \"authorizerUri\": \"\""})
    void cognitoToLambdaTypeChangeRequiresNewUri(String uriFragment) throws Exception {
        String apiId = importApi(COGNITO_SPEC);
        try {
            String authorizerId = getJson(methodPath(apiId)).path("authorizerId").asText();
            deploy(apiId, "before");
            assertEquals(401, invokeWithoutToken(apiId, "before"));

            int putStatus = given().contentType(ContentType.JSON).queryParam("mode", "merge")
                    .body(PARTIAL_AUTHORIZER_SPEC.formatted(uriFragment))
                    .put("/restapis/" + apiId).statusCode();
            JsonNode method = getJson(methodPath(apiId));
            JsonNode authorizer = getJson("/restapis/" + apiId + "/authorizers/" + authorizerId);
            deploy(apiId, "after-attempt");
            int afterStatus = invokeWithoutToken(apiId, "after-attempt");
            assertAll(
                    () -> assertEquals(400, putStatus),
                    () -> assertEquals(401, afterStatus),
                    () -> assertEquals("COGNITO_USER_POOLS", method.path("authorizationType").asText()),
                    () -> assertEquals(authorizerId, method.path("authorizerId").asText()),
                    () -> assertEquals("COGNITO_USER_POOLS", authorizer.path("type").asText()));
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void failedCrossTypeMergeRestoresMethodAndAuthorizer() throws Exception {
        String apiId = importApi();
        try {
            denyAuthorizerRequests();
            String authorizerId = getJson(methodPath(apiId)).path("authorizerId").asText();
            deploy(apiId, "before");
            assertEquals(403, invoke(apiId, "before"));

            String spec = """
                    {
                      "openapi": "3.0.1",
                      "info": {"title": "RejectedCrossType", "version": "2"},
                      "components": {"securitySchemes": {
                        "Guard": {
                          "type": "apiKey", "name": "Authorization", "in": "header",
                          "x-amazon-apigateway-authorizer": {
                            "type": "cognito_user_pools",
                            "providerARNs": ["arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_ABC123"]
                          }
                        }
                      }},
                      "paths": {
                        "/attempted": {"get": {
                          "x-amazon-apigateway-integration": {"type": "MOCK"}
                        }},
                        "/broken": {"get": {
                          "parameters": [{"name": "filter[a]", "in": "query", "required": true,
                                          "schema": {"type": "string"}}],
                          "responses": {"200": {"description": "ok"}}
                        }}
                      }
                    }
                    """;
            String errorBody = given().contentType(ContentType.JSON).queryParam("mode", "merge")
                    .body(spec).put("/restapis/" + apiId).then().statusCode(400)
                    .extract().asString();
            assertTrue(MAPPER.readTree(errorBody).path("message").asText()
                    .contains("Unable to put method 'GET' on resource at path '/broken'"));

            JsonNode method = getJson(methodPath(apiId));
            JsonNode authorizer = getJson("/restapis/" + apiId + "/authorizers/" + authorizerId);
            deploy(apiId, "after-failed");
            int afterStatus = invoke(apiId, "after-failed");
            assertAll(
                    () -> assertEquals(403, afterStatus),
                    () -> assertEquals("CUSTOM", method.path("authorizationType").asText()),
                    () -> assertEquals(authorizerId, method.path("authorizerId").asText()),
                    () -> assertEquals("TOKEN", authorizer.path("type").asText()),
                    () -> assertEquals(AUTHORIZER_URI, authorizer.path("authorizerUri").asText(null)),
                    () -> verifyDenyInvocations(2));
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void overwriteDoesNotResolveSchemeFromRemovedAuthorizer() throws Exception {
        String apiId = importApi();
        try {
            putSpec(apiId, "overwrite", RETAINED_SCHEME_SPEC);
            JsonNode method = getJson(methodPath(apiId));
            assertAll(
                    () -> assertEquals("NONE", method.path("authorizationType").asText()),
                    () -> assertEquals("", method.path("authorizerId").asText()),
                    () -> assertEquals(0, getJson("/restapis/" + apiId + "/authorizers")
                            .path("item").size()));
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void explicitNonAuthorizerSchemesDoNotInheritRetainedAuthorizerId() throws Exception {
        for (String expectedType : List.of("NONE", "AWS_IAM")) {
            String apiId = importApi();
            try {
                String extension = "AWS_IAM".equals(expectedType)
                        ? ", \"x-amazon-apigateway-authtype\": \"awsSigv4\"" : "";
                merge(apiId, EXPLICIT_SCHEME_SPEC.formatted(extension));
                JsonNode method = getJson(methodPath(apiId));
                assertAll(
                        () -> assertEquals(expectedType, method.path("authorizationType").asText()),
                        () -> assertEquals("", method.path("authorizerId").asText()));
            } finally {
                given().delete("/restapis/" + apiId).then().statusCode(202);
            }
        }
    }

    private String importApi() {
        return importApi(ORIGINAL_SPEC);
    }

    private String importApi(String spec) {
        return given().contentType(ContentType.JSON).queryParam("mode", "import")
                .body(spec).post("/restapis").then().statusCode(201).extract().path("id");
    }

    private static void merge(String apiId, String spec) {
        putSpec(apiId, "merge", spec);
    }

    private static void putSpec(String apiId, String mode, String spec) {
        if (mode == null) {
            given().contentType(ContentType.JSON).body(spec)
                    .put("/restapis/" + apiId).then().statusCode(200);
        } else {
            given().contentType(ContentType.JSON).queryParam("mode", mode).body(spec)
                    .put("/restapis/" + apiId).then().statusCode(200);
        }
    }

    private static void deploy(String apiId, String stageName) {
        given().contentType(ContentType.JSON).body(Map.of("stageName", stageName))
                .post("/restapis/" + apiId + "/deployments").then().statusCode(201);
    }

    private static int invoke(String apiId, String stageName) {
        return given().header("Authorization", "Bearer denied")
                .get("/execute-api/" + apiId + "/" + stageName + "/protected").statusCode();
    }

    private static int invokeWithoutToken(String apiId, String stageName) {
        return given().get("/execute-api/" + apiId + "/" + stageName + "/protected").statusCode();
    }

    private static JsonNode getJson(String path) throws Exception {
        String body = given().get(path).then().statusCode(200).extract().asString();
        return MAPPER.readTree(body);
    }

    private static String methodPath(String apiId) throws Exception {
        for (JsonNode resource : getJson("/restapis/" + apiId + "/resources").path("item")) {
            if ("/protected".equals(resource.path("path").asText())) {
                return "/restapis/" + apiId + "/resources/" + resource.path("id").asText()
                        + "/methods/GET";
            }
        }
        throw new AssertionError("Missing /protected resource");
    }

    private void denyAuthorizerRequests() {
        denyAuthorizerRequests(AUTHORIZER_FUNCTION);
    }

    private void denyAuthorizerRequests(String function) {
        when(lambdaService.invoke(eq("us-east-1"), eq(function), any(byte[].class),
                eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, null, DENY_POLICY, null, "authorizer-request"));
    }

    private void verifyDenyInvocations(int count) {
        verifyDenyInvocations(AUTHORIZER_FUNCTION, count);
    }

    private void verifyDenyInvocations(String function, int count) {
        verify(lambdaService, times(count)).invoke(eq("us-east-1"), eq(function),
                any(byte[].class), eq(InvocationType.RequestResponse));
    }
}
