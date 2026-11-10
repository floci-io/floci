package io.floci.aws.services.apigatewayv2;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * JSON 1.1 path integration tests for the four missing Update operations:
 * UpdateIntegration, UpdateAuthorizer, UpdateStage, UpdateDeployment.
 *
 * Verifies PascalCase key normalization and merge-patch semantics through
 * the AmazonApiGatewayV2.* X-Amz-Target header.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2UpdateOperationsJson11Test {

    private static final String AMZ_JSON = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AmazonApiGatewayV2.";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260413/us-east-1/apigatewayv2/aws4_request";

    private static String apiId;
    private static String integrationId;
    private static String authorizerId;
    private static String stageName;
    private static String deploymentId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // ──────────────────────────── Setup: create shared resources ────────────────────────────

    @Test
    @Order(1)
    void setupCreateApi() {
        apiId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"Name":"update-ops-json11-api","ProtocolType":"HTTP"}
                        """)
                .when().post("/")
                .then()
                .statusCode(201)
                .body("ApiId", notNullValue())
                .extract().path("ApiId");
    }

    @Test
    @Order(2)
    void setupCreateIntegration() {
        integrationId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateIntegration")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","IntegrationType":"HTTP_PROXY","IntegrationUri":"https://original.example.com","PayloadFormatVersion":"2.0"}
                        """.formatted(apiId))
                .when().post("/")
                .then()
                .statusCode(201)
                .body("IntegrationId", notNullValue())
                .body("IntegrationUri", equalTo("https://original.example.com"))
                .extract().path("IntegrationId");
    }

    @Test
    @Order(3)
    void setupCreateAuthorizer() {
        authorizerId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateAuthorizer")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","AuthorizerType":"JWT","Name":"original-authorizer","IdentitySource":["$request.header.Authorization"],"JwtConfiguration":{"Issuer":"https://original-issuer.example.com","Audience":["original-audience"]}}
                        """.formatted(apiId))
                .when().post("/")
                .then()
                .statusCode(201)
                .body("AuthorizerId", notNullValue())
                .body("Name", equalTo("original-authorizer"))
                .extract().path("AuthorizerId");
    }

    @Test
    @Order(4)
    void setupCreateDeployment() {
        deploymentId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateDeployment")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","Description":"initial-deployment"}
                        """.formatted(apiId))
                .when().post("/")
                .then()
                .statusCode(201)
                .body("DeploymentId", notNullValue())
                .extract().path("DeploymentId");
    }

    @Test
    @Order(5)
    void setupCreateStage() {
        stageName = "test-stage-json11";
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateStage")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","StageName":"%s","DeploymentId":"%s","AutoDeploy":false}
                        """.formatted(apiId, stageName, deploymentId))
                .when().post("/")
                .then()
                .statusCode(201)
                .body("StageName", equalTo(stageName))
                .body("DeploymentId", equalTo(deploymentId));
    }

    // ──────────────────────────── UpdateIntegration: PascalCase request/response ────────────────────────────

    /**
     * Test AmazonApiGatewayV2.UpdateIntegration: send PascalCase request,
     * verify HTTP 200 and PascalCase response fields.
     */
    @Test
    @Order(10)
    void updateIntegrationViaPascalCaseKeys() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateIntegration")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","IntegrationId":"%s","IntegrationUri":"https://updated.example.com"}
                        """.formatted(apiId, integrationId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("IntegrationId", equalTo(integrationId))
                .body("IntegrationUri", equalTo("https://updated.example.com"));
    }

    // ──────────────────────────── UpdateIntegration: merge-patch semantics ────────────────────────────

    /**
     * Test UpdateIntegration merge-patch via JSON 1.1:
     * verify only provided fields are updated, others preserved.
     */
    @Test
    @Order(11)
    void updateIntegrationMergePatchPreservesUnprovidedFields() {
        // PATCH only IntegrationUri; IntegrationType and PayloadFormatVersion must be preserved
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateIntegration")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","IntegrationId":"%s","IntegrationUri":"https://patched.example.com"}
                        """.formatted(apiId, integrationId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("IntegrationUri", equalTo("https://patched.example.com"))
                // Fields not in request must be preserved
                .body("IntegrationType", equalTo("HTTP_PROXY"))
                .body("PayloadFormatVersion", equalTo("2.0"));
    }

    // ──────────────────────────── UpdateAuthorizer: PascalCase request/response ────────────────────────────

    /**
     * Test AmazonApiGatewayV2.UpdateAuthorizer: send PascalCase request,
     * verify HTTP 200 and PascalCase response fields.
     */
    @Test
    @Order(20)
    void updateAuthorizerViaPascalCaseKeys() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateAuthorizer")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","AuthorizerId":"%s","Name":"updated-authorizer"}
                        """.formatted(apiId, authorizerId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("AuthorizerId", equalTo(authorizerId))
                .body("Name", equalTo("updated-authorizer"));
    }

    // ──────────────────────────── UpdateAuthorizer: merge-patch semantics ────────────────────────────

    /**
     * Test UpdateAuthorizer merge-patch via JSON 1.1:
     * verify only provided fields are updated, others preserved.
     */
    @Test
    @Order(21)
    void updateAuthorizerMergePatchPreservesUnprovidedFields() {
        // PATCH only Name; AuthorizerType and IdentitySource must be preserved
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateAuthorizer")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","AuthorizerId":"%s","Name":"merge-patch-authorizer"}
                        """.formatted(apiId, authorizerId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Name", equalTo("merge-patch-authorizer"))
                // Fields not in request must be preserved
                .body("AuthorizerType", equalTo("JWT"))
                .body("IdentitySource", hasItem("$request.header.Authorization"));
    }

    // ──────────────────────────── UpdateStage: PascalCase request/response ────────────────────────────

    /**
     * Test AmazonApiGatewayV2.UpdateStage: send PascalCase request,
     * verify HTTP 200 and PascalCase response fields.
     */
    @Test
    @Order(30)
    void updateStageViaPascalCaseKeys() {
        // Create a second deployment to use as the new DeploymentId
        String newDeploymentId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateDeployment")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","Description":"second-deployment"}
                        """.formatted(apiId))
                .when().post("/")
                .then()
                .statusCode(201)
                .extract().path("DeploymentId");

        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateStage")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","StageName":"%s","DeploymentId":"%s"}
                        """.formatted(apiId, stageName, newDeploymentId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("StageName", equalTo(stageName))
                .body("DeploymentId", equalTo(newDeploymentId));
    }

    // ──────────────────────────── UpdateStage: merge-patch semantics ────────────────────────────

    /**
     * Test UpdateStage merge-patch via JSON 1.1:
     * verify only provided fields are updated, others preserved.
     */
    @Test
    @Order(31)
    void updateStageMergePatchPreservesUnprovidedFields() {
        // PATCH only AutoDeploy; StageName and DeploymentId must be preserved
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateStage")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","StageName":"%s","AutoDeploy":true}
                        """.formatted(apiId, stageName))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("StageName", equalTo(stageName))
                .body("AutoDeploy", equalTo(true))
                // DeploymentId set in order 30 must still be present
                .body("DeploymentId", notNullValue())
                // LastUpdatedDate must be present (updated on every PATCH)
                .body("LastUpdatedDate", notNullValue());
    }

    // ──────────────────────────── UpdateDeployment: PascalCase request/response ────────────────────────────

    /**
     * Test AmazonApiGatewayV2.UpdateDeployment: send PascalCase request,
     * verify HTTP 200 and PascalCase response fields.
     */
    @Test
    @Order(40)
    void updateDeploymentViaPascalCaseKeys() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateDeployment")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","DeploymentId":"%s","Description":"updated-description"}
                        """.formatted(apiId, deploymentId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("DeploymentId", equalTo(deploymentId))
                .body("Description", equalTo("updated-description"));
    }

    // ──────────────────────────── UpdateDeployment: merge-patch semantics ────────────────────────────

    /**
     * Test UpdateDeployment merge-patch via JSON 1.1:
     * verify only provided fields are updated, others preserved.
     */
    @Test
    @Order(41)
    void updateDeploymentMergePatchPreservesUnprovidedFields() {
        // PATCH only Description; DeploymentStatus must be preserved
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateDeployment")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","DeploymentId":"%s","Description":"merge-patch-description"}
                        """.formatted(apiId, deploymentId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Description", equalTo("merge-patch-description"))
                // DeploymentStatus should be preserved
                .body("DeploymentStatus", equalTo("DEPLOYED"));
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(99)
    void cleanup() {
        if (apiId == null) {
            return;
        }

        // Delete stage
        if (stageName != null) {
            given()
                    .contentType(AMZ_JSON)
                    .header("X-Amz-Target", TARGET_PREFIX + "DeleteStage")
                    .header("Authorization", AUTH_HEADER)
                    .body("""
                            {"ApiId":"%s","StageName":"%s"}
                            """.formatted(apiId, stageName))
                    .when().post("/")
                    .then()
                    .statusCode(anyOf(equalTo(204), equalTo(404)));
        }

        // Delete authorizer
        if (authorizerId != null) {
            given()
                    .contentType(AMZ_JSON)
                    .header("X-Amz-Target", TARGET_PREFIX + "DeleteAuthorizer")
                    .header("Authorization", AUTH_HEADER)
                    .body("""
                            {"ApiId":"%s","AuthorizerId":"%s"}
                            """.formatted(apiId, authorizerId))
                    .when().post("/")
                    .then()
                    .statusCode(anyOf(equalTo(204), equalTo(404)));
        }

        // Delete integration
        if (integrationId != null) {
            given()
                    .contentType(AMZ_JSON)
                    .header("X-Amz-Target", TARGET_PREFIX + "DeleteIntegration")
                    .header("Authorization", AUTH_HEADER)
                    .body("""
                            {"ApiId":"%s","IntegrationId":"%s"}
                            """.formatted(apiId, integrationId))
                    .when().post("/")
                    .then()
                    .statusCode(anyOf(equalTo(204), equalTo(404)));
        }

        // Delete API (cascades deployments)
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s"}
                        """.formatted(apiId))
                .when().post("/")
                .then()
                .statusCode(anyOf(equalTo(204), equalTo(404)));
    }
}
