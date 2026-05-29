package io.github.hectorvent.floci.services.apigatewayv2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for API Gateway v2 fixes:
 * - createDeployment stageName auto-deploy
 * - GetDeployment, DeleteDeployment, DeleteIntegration
 * - JSON 1.1 handler PascalCase normalization and missing switch cases
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2JsonHandlerTest {

    private static final String AMZ_JSON = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AmazonApiGatewayV2.";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260413/us-east-1/apigatewayv2/aws4_request";

    private static String apiId;
    private static String integrationId;
    private static String deploymentId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // ──────────────────────────── JSON 1.1 handler path ────────────────────────────

    @Test
    @Order(1)
    void json11CreateApiWithPascalCaseKeys() {
        apiId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"Name":"json11-test","ProtocolType":"HTTP"}
                        """)
                .when().post("/")
                .then()
                .statusCode(201)
                .body("ApiId", notNullValue())
                .body("Name", equalTo("json11-test"))
                .body("ProtocolType", equalTo("HTTP"))
                // AWS defaults must be populated
                .body("RouteSelectionExpression", equalTo("${request.method} ${request.path}"))
                .body("ApiKeySelectionExpression", equalTo("$request.header.x-api-key"))
                .extract().path("ApiId");
    }

    @Test
    @Order(2)
    void json11CreateIntegrationWithPascalCaseKeys() {
        integrationId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateIntegration")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","IntegrationType":"AWS_PROXY","IntegrationUri":"arn:aws:lambda:us-east-1:000000000000:function:test","PayloadFormatVersion":"2.0"}
                        """.formatted(apiId))
                .when().post("/")
                .then()
                .statusCode(201)
                .body("IntegrationId", notNullValue())
                .extract().path("IntegrationId");
    }

    @Test
    @Order(3)
    void json11CreateDeploymentAndStage() {
        deploymentId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateDeployment")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","Description":"initial"}
                        """.formatted(apiId))
                .when().post("/")
                .then()
                .statusCode(201)
                .body("DeploymentId", notNullValue())
                .extract().path("DeploymentId");

        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateStage")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","StageName":"prod","DeploymentId":"%s"}
                        """.formatted(apiId, deploymentId))
                .when().post("/")
                .then()
                .statusCode(201)
                .body("StageName", equalTo("prod"))
                .body("DeploymentId", equalTo(deploymentId));
    }

    @Test
    @Order(4)
    void json11GetDeployment() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetDeployment")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","DeploymentId":"%s"}
                        """.formatted(apiId, deploymentId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("DeploymentId", equalTo(deploymentId))
                .body("Description", equalTo("initial"));
    }

    // ──────────────────────────── stageName auto-deploy ────────────────────────────

    @Test
    @Order(5)
    void createDeploymentWithStageNameAutoDeploysToStage() {
        String newDeploymentId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"description":"auto-deploy","stageName":"prod"}
                        """)
                .when().post("/v2/apis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .body("deploymentId", notNullValue())
                .extract().path("deploymentId");

        given()
                .when().get("/v2/apis/" + apiId + "/stages/prod")
                .then()
                .statusCode(200)
                .body("deploymentId", equalTo(newDeploymentId))
                .body("deploymentId", not(equalTo(deploymentId)));
    }

    @Test
    @Order(6)
    void createDeploymentWithMissingStageName404sWithoutOrphan() {
        // Count deployments before
        int beforeCount = given()
                .when().get("/v2/apis/" + apiId + "/deployments")
                .then().statusCode(200)
                .extract().jsonPath().getList("items").size();

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"description":"bad stage","stageName":"nonexistent"}
                        """)
                .when().post("/v2/apis/" + apiId + "/deployments")
                .then()
                .statusCode(404);

        // Verify no orphan deployment was created
        int afterCount = given()
                .when().get("/v2/apis/" + apiId + "/deployments")
                .then().statusCode(200)
                .extract().jsonPath().getList("items").size();

        org.junit.jupiter.api.Assertions.assertEquals(beforeCount, afterCount,
                "No orphan deployment should be created when stageName is invalid");
    }

    // ──────────────────────────── JSON 1.1 delete operations ────────────────────────────

    @Test
    @Order(7)
    void json11DeleteIntegration() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteIntegration")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","IntegrationId":"%s"}
                        """.formatted(apiId, integrationId))
                .when().post("/")
                .then()
                .statusCode(204);
    }

    @Test
    @Order(8)
    void json11DeleteDeployment() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteDeployment")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","DeploymentId":"%s"}
                        """.formatted(apiId, deploymentId))
                .when().post("/")
                .then()
                .statusCode(204);
    }

    @Test
    @Order(9)
    void cleanup() {
        given().when().delete("/v2/apis/" + apiId + "/stages/prod").then().statusCode(204);
        given().when().delete("/v2/apis/" + apiId).then().statusCode(204);
    }

    @Test
    @Order(20)
    void json11CreateIntegrationWithRequestParametersRoundTrips() throws Exception {
        // Create a fresh API for this test (the cleanup test removed the previous one)
        String localApiId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"Name":"rp-test","ProtocolType":"HTTP"}
                        """)
                .when().post("/")
                .then().statusCode(201).extract().path("ApiId");

        // Create HTTP_PROXY integration with RequestParameters
        String createBody = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateIntegration")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {
                          "ApiId": "%s",
                          "IntegrationType": "HTTP_PROXY",
                          "IntegrationUri": "http://example.com/{proxy}",
                          "PayloadFormatVersion": "1.0",
                          "RequestParameters": {
                            "overwrite:path": "/public/$request.path.proxy",
                            "overwrite:header.Host": "wallet.internal",
                            "append:header.x-user-id": "$context.authorizer.claims.userId"
                          }
                        }
                        """.formatted(localApiId))
                .when().post("/")
                .then().statusCode(201).extract().asString();

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode created = mapper.readTree(createBody);
        String localIntegrationId = created.get("IntegrationId").asText();
        org.junit.jupiter.api.Assertions.assertEquals("HTTP_PROXY", created.get("IntegrationType").asText());
        com.fasterxml.jackson.databind.JsonNode createdRP = created.get("RequestParameters");
        org.junit.jupiter.api.Assertions.assertNotNull(createdRP, "CreateIntegration response should include RequestParameters");
        org.junit.jupiter.api.Assertions.assertEquals("/public/$request.path.proxy", createdRP.get("overwrite:path").asText());
        org.junit.jupiter.api.Assertions.assertEquals("wallet.internal", createdRP.get("overwrite:header.Host").asText());
        org.junit.jupiter.api.Assertions.assertEquals("$context.authorizer.claims.userId", createdRP.get("append:header.x-user-id").asText());

        // Get the integration and assert RequestParameters survived persistence
        String getBody = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetIntegration")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","IntegrationId":"%s"}
                        """.formatted(localApiId, localIntegrationId))
                .when().post("/")
                .then().statusCode(200).extract().asString();

        com.fasterxml.jackson.databind.JsonNode getRP = mapper.readTree(getBody).get("RequestParameters");
        org.junit.jupiter.api.Assertions.assertNotNull(getRP, "GetIntegration response should include RequestParameters");
        org.junit.jupiter.api.Assertions.assertEquals("/public/$request.path.proxy", getRP.get("overwrite:path").asText());
        org.junit.jupiter.api.Assertions.assertEquals("$context.authorizer.claims.userId", getRP.get("append:header.x-user-id").asText());

        // Cleanup
        given().when().delete("/v2/apis/" + localApiId).then().statusCode(204);
    }
}
