package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * {@code CreateDeployment} with a {@code stageName} must create the stage as a side effect,
 * leaving the deployed API immediately invokable.
 *
 * <p>Terraform's {@code aws_api_gateway_deployment} takes a {@code stage_name} and relies on
 * this; without it a configuration that has no separate {@code aws_api_gateway_stage} applies
 * cleanly and then serves {@code {"message":"Stage not found"}} on every route.
 *
 * @see <a href="https://github.com/floci-io/floci/issues/2120">Issue #2120</a>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayDeploymentStageNameIntegrationTest {

    private static final String STAGE = "local";

    private static String apiId;
    private static String resourceId;
    private static String firstDeploymentId;

    @Test @Order(1)
    void createApiWithAMockRoute() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"stage-repro\"}")
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .extract().path("id");

        String rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .extract().path("item[0].id");

        resourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"health\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/responses/200")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{\\\"status\\\":\\\"ok\\\"}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration/responses/200")
                .then()
                .statusCode(201);
    }

    // ── the reported bug ──────────────────────────────────────────────────────

    @Test @Order(2)
    void createDeploymentWithStageName_createsTheStage() {
        firstDeploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"" + STAGE + "\",\"stageDescription\":\"from deploy\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .extract().path("id");

        // Previously GetStages was empty here — no stage was ever created.
        given()
                .when().get("/restapis/" + apiId + "/stages")
                .then()
                .statusCode(200)
                .body("item.stageName", hasItem(STAGE))
                .body("item.find { it.stageName == '" + STAGE + "' }.deploymentId",
                        equalTo(firstDeploymentId));
    }

    @Test @Order(3)
    void theDeployedApiIsImmediatelyInvokable() {
        // Previously: {"message":"Stage not found"}
        given()
                .when().get("/restapis/" + apiId + "/" + STAGE + "/_user_request_/health")
                .then()
                .statusCode(200)
                .body("status", equalTo("ok"));
    }

    @Test @Order(4)
    void getStageReturnsTheStageDirectly() {
        given()
                .when().get("/restapis/" + apiId + "/stages/" + STAGE)
                .then()
                .statusCode(200)
                .body("stageName", equalTo(STAGE))
                .body("deploymentId", equalTo(firstDeploymentId))
                .body("description", equalTo("from deploy"));
    }

    // ── redeploy re-points the existing stage (repeated terraform apply) ──────

    @Test @Order(5)
    void redeployingRepointsTheSameStageAtTheNewDeployment() {
        String secondDeploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"" + STAGE + "\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .when().get("/restapis/" + apiId + "/stages/" + STAGE)
                .then()
                .statusCode(200)
                .body("deploymentId", equalTo(secondDeploymentId))
                // the original stage settings survive the redeploy
                .body("description", equalTo("from deploy"));

        // still exactly one stage — a redeploy must not fan out duplicates
        given()
                .when().get("/restapis/" + apiId + "/stages")
                .then()
                .statusCode(200)
                .body("item.findAll { it.stageName == '" + STAGE + "' }", hasSize(1));

        given()
                .when().get("/restapis/" + apiId + "/" + STAGE + "/_user_request_/health")
                .then()
                .statusCode(200)
                .body("status", equalTo("ok"));
    }

    @Test @Order(6)
    void stageVariablesFromTheDeploymentRequestAreApplied() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"" + STAGE + "\",\"variables\":{\"lambdaAlias\":\"LIVE\"}}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then()
                .statusCode(201);

        given()
                .when().get("/restapis/" + apiId + "/stages/" + STAGE)
                .then()
                .statusCode(200)
                .body("variables.lambdaAlias", equalTo("LIVE"));
    }

    // ── without stageName nothing is deployed (unchanged behaviour) ───────────

    @Test @Order(7)
    void createDeploymentWithoutStageName_createsNoStage() {
        String otherApiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"no-stage-repro\"}")
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"no stage\"}")
                .when().post("/restapis/" + otherApiId + "/deployments")
                .then()
                .statusCode(201);

        given()
                .when().get("/restapis/" + otherApiId + "/stages")
                .then()
                .statusCode(200)
                .body("item", hasSize(0));
    }
}
