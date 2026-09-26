package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class ApiGatewayCognitoAuthorizerIntegrationTest {

    @Inject CognitoService cognitoService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void enforcesPoolHeaderAndScopesAndExposesClaims() throws Exception {
        UserPool allowedPool = cognitoService.createUserPool(Map.of("PoolName", "allowed-pool"), "us-east-1");
        UserPool otherPool = cognitoService.createUserPool(Map.of("PoolName", "other-pool"), "us-east-1");
        Map<String, Object> allowed = issueTokens(allowedPool);
        Map<String, Object> other = issueTokens(otherPool);
        String accessToken = (String) allowed.get("AccessToken");
        String idToken = (String) allowed.get("IdToken");
        String otherToken = (String) other.get("AccessToken");
        String apiId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"cognito-authorizer-test\"}")
                .when().post("/restapis").then().statusCode(201).extract().path("id");
        try {
            String rootId = given().when().get("/restapis/" + apiId + "/resources")
                    .then().statusCode(200).extract().path("item[0].id");
            String securedId = createResource(apiId, rootId, "secured");
            String unscopedId = createResource(apiId, rootId, "unscoped");
            String missingScopeId = createResource(apiId, rootId, "missing-scope");
            String noPoolId = createResource(apiId, rootId, "no-pool");
            String openId = createResource(apiId, rootId, "open");

            String authorizerId = createAuthorizer(apiId, "pool-auth", allowedPool.getArn());
            String emptyAuthorizerId = createAuthorizer(apiId, "empty-auth", null);
            configureMethod(apiId, securedId, authorizerId, "COGNITO_USER_POOLS",
                    List.of("aws.cognito.signin.user.admin"));
            configureMethod(apiId, unscopedId, authorizerId, "COGNITO_USER_POOLS", List.of());
            configureMethod(apiId, missingScopeId, authorizerId, "COGNITO_USER_POOLS",
                    List.of("orders/read"));
            configureMethod(apiId, noPoolId, emptyAuthorizerId, "COGNITO_USER_POOLS", List.of());
            configureMethod(apiId, openId, null, "NONE", List.of());

            String deploymentId = given().contentType(ContentType.JSON).body("{}")
                    .when().post("/restapis/" + apiId + "/deployments")
                    .then().statusCode(201).extract().path("id");
            given().contentType(ContentType.JSON)
                    .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"}")
                    .when().post("/restapis/" + apiId + "/stages").then().statusCode(201);

            String base = "/execute-api/" + apiId + "/test/";
            given().when().get(base + "secured").then().statusCode(401);
            given().header("X-Id-Token", "Bearer " + otherToken)
                    .when().get(base + "secured").then().statusCode(401);
            given().header("Authorization", "Bearer " + accessToken)
                    .when().get(base + "secured").then().statusCode(401);
            given().header("X-Id-Token", "Bearer " + idToken)
                    .when().get(base + "secured").then().statusCode(403);
            given().header("X-Id-Token", "Bearer " + accessToken)
                    .when().get(base + "missing-scope").then().statusCode(403);
            given().header("X-Id-Token", "not-a-jwt")
                    .when().get(base + "secured").then().statusCode(401);
            given().header("X-Id-Token", "Bearer " + accessToken)
                    .when().get(base + "no-pool").then().statusCode(401);
            given().header("X-Id-Token", "Bearer " + accessToken)
                    .when().get(base + "open").then().statusCode(200);

            String securedResponse = given().header("X-Id-Token", "Bearer " + accessToken)
                    .when().get(base + "secured").then().statusCode(200).extract().asString();
            String unscopedResponse = given().header("X-Id-Token", "Bearer " + idToken)
                    .when().get(base + "unscoped").then().statusCode(200).extract().asString();
            JsonNode accessClaims = MAPPER.readTree(securedResponse);
            JsonNode idClaims = MAPPER.readTree(unscopedResponse);
            assertEquals("access", accessClaims.path("tokenUse").asText());
            assertEquals("id", idClaims.path("tokenUse").asText());
            assertEquals(accessClaims.path("sub").asText(), idClaims.path("sub").asText());

            cognitoService.globalSignOut(accessToken);
            given().header("X-Id-Token", "Bearer " + accessToken)
                    .when().get(base + "secured").then().statusCode(401);
            given().header("X-Id-Token", "Bearer " + idToken)
                    .when().get(base + "unscoped").then().statusCode(401);
        } finally {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void rejectsTokensOfDeletedUser() throws Exception {
        UserPool pool = cognitoService.createUserPool(Map.of("PoolName", "deleted-user-pool"), "us-east-1");
        Map<String, Object> tokens = issueTokens(pool);
        String accessToken = (String) tokens.get("AccessToken");
        String idToken = (String) tokens.get("IdToken");
        String apiId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "cognito-deleted-user-test"))
                .post("/restapis").then().statusCode(201).extract().path("id");
        try {
            String rootId = given().get("/restapis/" + apiId + "/resources")
                    .then().statusCode(200).extract().path("item[0].id");
            String securedId = createResource(apiId, rootId, "secured");
            String authorizerId = createAuthorizer(apiId, "Guard", pool.getArn());
            configureMethod(apiId, securedId, authorizerId, "COGNITO_USER_POOLS", List.of());
            given().contentType(ContentType.JSON).body(Map.of("stageName", "test"))
                    .post("/restapis/" + apiId + "/deployments").then().statusCode(201);
            String securedPath = "/execute-api/" + apiId + "/test/secured";
            given().header("X-Id-Token", "Bearer " + accessToken)
                    .get(securedPath).then().statusCode(200);

            cognitoService.deleteUser(accessToken);

            given().header("X-Id-Token", "Bearer " + accessToken)
                    .get(securedPath).then().statusCode(401);
            given().header("X-Id-Token", "Bearer " + idToken)
                    .get(securedPath).then().statusCode(401);
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ", \"providerARNs\": null"})
    void mergingPartialCognitoAuthorizerKeepsRetainedMethodAuthorized(String providerArnsField)
            throws Exception {
        UserPool pool = cognitoService.createUserPool(Map.of("PoolName", "merge-pool"), "us-east-1");
        String accessToken = (String) issueTokens(pool).get("AccessToken");
        String apiId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "cognito-merge-test"))
                .post("/restapis").then().statusCode(201).extract().path("id");
        try {
            String rootId = given().get("/restapis/" + apiId + "/resources")
                    .then().statusCode(200).extract().path("item[0].id");
            String securedId = createResource(apiId, rootId, "secured");
            String authorizerId = createAuthorizer(apiId, "Guard", pool.getArn());
            configureMethod(apiId, securedId, authorizerId, "COGNITO_USER_POOLS", List.of());
            given().contentType(ContentType.JSON).body(Map.of("stageName", "before"))
                    .post("/restapis/" + apiId + "/deployments").then().statusCode(201);
            String beforePath = "/execute-api/" + apiId + "/before/secured";
            given().header("X-Id-Token", "Bearer " + accessToken)
                    .get(beforePath).then().statusCode(200);

            given().contentType(ContentType.JSON).queryParam("mode", "merge")
                    .body(partialCognitoSpec(providerArnsField))
                    .put("/restapis/" + apiId).then().statusCode(200);
            given().contentType(ContentType.JSON).body(Map.of("stageName", "after"))
                    .post("/restapis/" + apiId + "/deployments").then().statusCode(201);

            String actualAuthorizerId = given().get("/restapis/" + apiId + "/authorizers/" + authorizerId)
                    .then().statusCode(200).extract().path("id");
            assertEquals(authorizerId, actualAuthorizerId);
            given().header("X-Id-Token", "Bearer " + accessToken)
                    .get("/execute-api/" + apiId + "/after/secured").then().statusCode(200);
            List<String> retainedArns = given().get("/restapis/" + apiId + "/authorizers/" + authorizerId)
                    .then().statusCode(200).extract().path("providerARNs");
            assertEquals(List.of(pool.getArn()), retainedArns);
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void explicitCognitoPoolListsReplacePriorPools() throws Exception {
        UserPool oldPool = cognitoService.createUserPool(Map.of("PoolName", "old-merge-pool"), "us-east-1");
        UserPool newPool = cognitoService.createUserPool(Map.of("PoolName", "new-merge-pool"), "us-east-1");
        String oldToken = (String) issueTokens(oldPool).get("AccessToken");
        String newToken = (String) issueTokens(newPool).get("AccessToken");
        String apiId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "cognito-pool-replacement-test"))
                .post("/restapis").then().statusCode(201).extract().path("id");
        try {
            String rootId = given().get("/restapis/" + apiId + "/resources")
                    .then().statusCode(200).extract().path("item[0].id");
            String securedId = createResource(apiId, rootId, "secured");
            String authorizerId = createAuthorizer(apiId, "Guard", oldPool.getArn());
            configureMethod(apiId, securedId, authorizerId, "COGNITO_USER_POOLS", List.of());
            given().contentType(ContentType.JSON).body(Map.of("stageName", "before"))
                    .post("/restapis/" + apiId + "/deployments").then().statusCode(201);
            given().header("X-Id-Token", "Bearer " + oldToken)
                    .get("/execute-api/" + apiId + "/before/secured").then().statusCode(200);

            String newArns = MAPPER.writeValueAsString(List.of(newPool.getArn()));
            given().contentType(ContentType.JSON).queryParam("mode", "merge")
                    .body(partialCognitoSpec(", \"providerARNs\": " + newArns))
                    .put("/restapis/" + apiId).then().statusCode(200);
            given().contentType(ContentType.JSON).body(Map.of("stageName", "new-pool"))
                    .post("/restapis/" + apiId + "/deployments").then().statusCode(201);
            given().header("X-Id-Token", "Bearer " + oldToken)
                    .get("/execute-api/" + apiId + "/new-pool/secured").then().statusCode(401);
            given().header("X-Id-Token", "Bearer " + newToken)
                    .get("/execute-api/" + apiId + "/new-pool/secured").then().statusCode(200);
            List<String> replacementArns = given().get("/restapis/" + apiId + "/authorizers/" + authorizerId)
                    .then().statusCode(200).extract().path("providerARNs");
            assertEquals(List.of(newPool.getArn()), replacementArns);

            given().contentType(ContentType.JSON).queryParam("mode", "merge")
                    .body(partialCognitoSpec(", \"providerARNs\": []"))
                    .put("/restapis/" + apiId).then().statusCode(200);
            given().contentType(ContentType.JSON).body(Map.of("stageName", "empty-pools"))
                    .post("/restapis/" + apiId + "/deployments").then().statusCode(201);
            given().header("X-Id-Token", "Bearer " + newToken)
                    .get("/execute-api/" + apiId + "/empty-pools/secured").then().statusCode(401);
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    private static String partialCognitoSpec(String providerArnsField) {
        return """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "Partial Cognito", "version": "2"},
                  "components": {"securitySchemes": {
                    "Guard": {
                      "type": "apiKey", "name": "X-Id-Token", "in": "header",
                      "x-amazon-apigateway-authorizer": {
                        "type": "cognito_user_pools"%s
                      }
                    }
                  }},
                  "paths": {"/added": {"get": {
                    "x-amazon-apigateway-integration": {"type": "MOCK"}
                  }}}
                }
                """.formatted(providerArnsField);
    }

    private Map<String, Object> issueTokens(UserPool pool) {
        String username = "user-" + UUID.randomUUID();
        cognitoService.adminCreateUser(pool.getId(), username,
                Map.of("email", username + "@example.com"), "TempPass1!");
        cognitoService.adminSetUserPassword(pool.getId(), username, "Perm1234!", true);
        UserPoolClient client = cognitoService.createUserPoolClient(pool.getId(), "test-client", false, false,
                List.of(), List.of(), null, List.of(), null,
                List.of("ALLOW_USER_PASSWORD_AUTH"), null, null, List.of(), null, List.of(), null,
                null, null, List.of(), null, null);
        Map<String, Object> response = cognitoService.initiateAuth(client.getClientId(),
                "USER_PASSWORD_AUTH", Map.of("USERNAME", username, "PASSWORD", "Perm1234!"));
        return (Map<String, Object>) response.get("AuthenticationResult");
    }

    private String createResource(String apiId, String rootId, String name) {
        return given().contentType(ContentType.JSON).body("{\"pathPart\":\"" + name + "\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");
    }

    private String createAuthorizer(String apiId, String name, String poolArn) {
        String body = poolArn == null
                ? "{\"name\":\"" + name + "\",\"type\":\"COGNITO_USER_POOLS\","
                    + "\"identitySource\":\"method.request.header.X-Id-Token\"}"
                : "{\"name\":\"" + name + "\",\"type\":\"COGNITO_USER_POOLS\","
                    + "\"identitySource\":\"method.request.header.X-Id-Token\","
                    + "\"providerARNs\":[\"" + poolArn + "\"]}";
        return given().contentType(ContentType.JSON).body(body)
                .when().post("/restapis/" + apiId + "/authorizers")
                .then().statusCode(201).extract().path("id");
    }

    private void configureMethod(String apiId, String resourceId, String authorizerId,
                                 String authType, List<String> scopes) throws Exception {
        String methodPath = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET";
        Map<String, Object> method = authorizerId == null
                ? Map.of("authorizationType", authType)
                : Map.of("authorizationType", authType, "authorizerId", authorizerId,
                        "authorizationScopes", scopes);
        given().contentType(ContentType.JSON).body(MAPPER.writeValueAsString(method))
                .when().put(methodPath).then().statusCode(201);
        given().contentType(ContentType.JSON).body("""
                {"type":"MOCK","requestTemplates":{"application/json":"{\\"statusCode\\":200}"}}
                """).when().put(methodPath + "/integration").then().statusCode(201);
        given().contentType(ContentType.JSON).body("""
                {"responseTemplates":{"application/json":"{\\"sub\\":\\"$context.authorizer.claims.sub\\",\\"tokenUse\\":\\"$context.authorizer.claims.token_use\\"}"}}
                """).when().put(methodPath + "/integration/responses/200").then().statusCode(201);
    }
}
