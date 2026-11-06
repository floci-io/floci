package io.floci.aws.services.apigatewayv2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * REST path integration tests for the four missing Update operations:
 * UpdateIntegration, UpdateAuthorizer, UpdateStage, UpdateDeployment.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2UpdateOperationsRestTest {

    private static String apiId;
    private static String integrationId;
    private static String authorizerId;
    private static String deploymentId;

    // ──────────────────────────── Setup: create shared API ────────────────────────────

    @Test
    @Order(1)
    void createApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"update-ops-test-api","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");
    }

    // ──────────────────────────── UpdateIntegration ────────────────────────────

    /**
     * Create API + integration, PATCH with new integrationUri, verify HTTP 200 and updated field.
     */
    @Test
    @Order(10)
    void createIntegrationForUpdate() {
        integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"HTTP_PROXY","integrationUri":"https://original.example.com","payloadFormatVersion":"2.0"}
                        """)
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(201)
                .body("integrationId", notNullValue())
                .body("integrationUri", equalTo("https://original.example.com"))
                .extract().path("integrationId");
    }

    @Test
    @Order(11)
    void updateIntegrationUri() {
        // PATCH with new integrationUri, verify HTTP 200 and updated field
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationUri":"https://updated.example.com"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then()
                .statusCode(200)
                .body("integrationId", equalTo(integrationId))
                .body("integrationUri", equalTo("https://updated.example.com"));
    }

    @Test
    @Order(12)
    void updateIntegrationMergePatch() {
        // Verify fields not in PATCH body are preserved
        // PATCH only integrationUri; integrationType and payloadFormatVersion must be preserved
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationUri":"https://patched.example.com"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then()
                .statusCode(200)
                .body("integrationUri", equalTo("https://patched.example.com"))
                // Fields not in PATCH body must be preserved
                .body("integrationType", equalTo("HTTP_PROXY"))
                .body("payloadFormatVersion", equalTo("2.0"));
    }

    @Test
    @Order(13)
    void updateIntegrationNotFound() {
        // PATCH non-existent integrationId, verify HTTP 404
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationUri":"https://ghost.example.com"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/integrations/nonexistent-integration-id")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── UpdateAuthorizer ────────────────────────────

    /**
     * Create API + authorizer, PATCH with new name, verify HTTP 200 and updated field.
     */
    @Test
    @Order(20)
    void createAuthorizerForUpdate() {
        authorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizerType":"JWT","name":"original-authorizer","identitySource":["$request.header.Authorization"],"jwtConfiguration":{"issuer":"https://original-issuer.example.com","audience":["original-audience"]}}
                        """)
                .when().post("/v2/apis/" + apiId + "/authorizers")
                .then()
                .statusCode(201)
                .body("authorizerId", notNullValue())
                .body("name", equalTo("original-authorizer"))
                .extract().path("authorizerId");
    }

    @Test
    @Order(21)
    void updateAuthorizerName() {
        // PATCH with new name, verify HTTP 200 and updated field
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"updated-authorizer"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(200)
                .body("authorizerId", equalTo(authorizerId))
                .body("name", equalTo("updated-authorizer"));
    }

    @Test
    @Order(22)
    void updateAuthorizerJwtConfiguration() {
        // PATCH with new audience and issuer, verify nested fields updated
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"jwtConfiguration":{"audience":["new-client-id"],"issuer":"https://new-issuer.example.com"}}
                        """)
                .when().patch("/v2/apis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(200)
                .body("authorizerId", equalTo(authorizerId))
                .body("jwtConfiguration.audience", hasItem("new-client-id"))
                .body("jwtConfiguration.issuer", equalTo("https://new-issuer.example.com"));
    }

    @Test
    @Order(23)
    void updateAuthorizerMergePatch() {
        // Verify fields not in PATCH body are preserved
        // PATCH only name; authorizerType and identitySource must be preserved
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"merge-patch-authorizer"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(200)
                .body("name", equalTo("merge-patch-authorizer"))
                // Fields not in PATCH body must be preserved
                .body("authorizerType", equalTo("JWT"))
                .body("identitySource", hasItem("$request.header.Authorization"));
    }

    @Test
    @Order(24)
    void updateAuthorizerNotFound() {
        // PATCH non-existent authorizerId, verify HTTP 404
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ghost-authorizer"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/authorizers/nonexistent-authorizer-id")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── UpdateStage ────────────────────────────

    /**
     * Create API + stage, PATCH with new deploymentId, verify HTTP 200 and updated field.
     */
    @Test
    @Order(30)
    void createDeploymentAndStageForUpdate() {
        // Create a deployment first (needed for stage)
        deploymentId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"description":"initial-deployment"}
                        """)
                .when().post("/v2/apis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .body("deploymentId", notNullValue())
                .extract().path("deploymentId");

        // Create stage with autoDeploy=false and initial deploymentId
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test-stage","deploymentId":"%s","autoDeploy":false}
                        """.formatted(deploymentId))
                .when().post("/v2/apis/" + apiId + "/stages")
                .then()
                .statusCode(201)
                .body("stageName", equalTo("test-stage"))
                .body("deploymentId", equalTo(deploymentId));
    }

    @Test
    @Order(31)
    void updateStageDeploymentId() {
        // PATCH with new deploymentId, verify HTTP 200 and updated field
        // Create a second deployment to use as the new deploymentId
        String newDeploymentId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"description":"second-deployment"}
                        """)
                .when().post("/v2/apis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .extract().path("deploymentId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"deploymentId":"%s"}
                        """.formatted(newDeploymentId))
                .when().patch("/v2/apis/" + apiId + "/stages/test-stage")
                .then()
                .statusCode(200)
                .body("stageName", equalTo("test-stage"))
                .body("deploymentId", equalTo(newDeploymentId));
    }

    @Test
    @Order(32)
    void updateStageLastUpdatedDate() {
        // Verify lastUpdatedDate is present and non-null after PATCH
        // First GET to capture current state
        String lastUpdatedBefore = given()
                .when().get("/v2/apis/" + apiId + "/stages/test-stage")
                .then()
                .statusCode(200)
                .body("lastUpdatedDate", notNullValue())
                .extract().path("lastUpdatedDate").toString();

        // PATCH and verify lastUpdatedDate is present (and changes)
        String lastUpdatedAfter = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"autoDeploy":true}
                        """)
                .when().patch("/v2/apis/" + apiId + "/stages/test-stage")
                .then()
                .statusCode(200)
                .body("lastUpdatedDate", notNullValue())
                .extract().path("lastUpdatedDate").toString();

        // lastUpdatedDate should have changed after the PATCH
        Assertions.assertNotEquals(lastUpdatedBefore, lastUpdatedAfter,
                "lastUpdatedDate should change after PATCH");
    }

    @Test
    @Order(33)
    void updateStageMergePatch() {
        // Verify fields not in PATCH body are preserved
        // PATCH only autoDeploy; stageName must be preserved (it's the key, always present)
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"autoDeploy":false}
                        """)
                .when().patch("/v2/apis/" + apiId + "/stages/test-stage")
                .then()
                .statusCode(200)
                .body("stageName", equalTo("test-stage"))
                .body("autoDeploy", equalTo(false))
                // deploymentId set in order 31 must still be present
                .body("deploymentId", notNullValue());
    }

    @Test
    @Order(34)
    void updateStageNotFound() {
        // PATCH non-existent stageName, verify HTTP 404
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"autoDeploy":true}
                        """)
                .when().patch("/v2/apis/" + apiId + "/stages/nonexistent-stage-name")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── UpdateDeployment ────────────────────────────

    /**
     * Create API + deployment, PATCH with new description, verify HTTP 200 and updated field.
     */
    @Test
    @Order(40)
    void updateDeploymentDescription() {
        // PATCH with new description, verify HTTP 200 and updated field
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"description":"updated-description"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/deployments/" + deploymentId)
                .then()
                .statusCode(200)
                .body("deploymentId", equalTo(deploymentId))
                .body("description", equalTo("updated-description"));
    }

    @Test
    @Order(41)
    void updateDeploymentMergePatch() {
        // Verify fields not in PATCH body are preserved
        // PATCH only description; deploymentStatus must be preserved
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"description":"merge-patch-description"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/deployments/" + deploymentId)
                .then()
                .statusCode(200)
                .body("description", equalTo("merge-patch-description"))
                // deploymentStatus should be preserved
                .body("deploymentStatus", equalTo("DEPLOYED"));
    }

    @Test
    @Order(42)
    void updateDeploymentNotFound() {
        // PATCH non-existent deploymentId, verify HTTP 404
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"description":"ghost-description"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/deployments/nonexistent-deployment-id")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(99)
    void cleanup() {
        // Delete stage
        given()
                .when().delete("/v2/apis/" + apiId + "/stages/test-stage")
                .then()
                .statusCode(anyOf(equalTo(204), equalTo(404)));

        // Delete authorizer
        if (authorizerId != null) {
            given()
                    .when().delete("/v2/apis/" + apiId + "/authorizers/" + authorizerId)
                    .then()
                    .statusCode(anyOf(equalTo(204), equalTo(404)));
        }

        // Delete integration
        if (integrationId != null) {
            given()
                    .when().delete("/v2/apis/" + apiId + "/integrations/" + integrationId)
                    .then()
                    .statusCode(anyOf(equalTo(204), equalTo(404)));
        }

        // Delete API (cascades deployments)
        if (apiId != null) {
            given()
                    .when().delete("/v2/apis/" + apiId)
                    .then()
                    .statusCode(anyOf(equalTo(204), equalTo(404)));
        }
    }
}
