package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ApiGatewayUpdateDeploymentIntegrationTest {

    @Test
    void testUpdateDeployment() {
        String apiId = given()
                .contentType("application/json")
                .body("{\"name\":\"update-deployment-api\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        String deploymentId = given()
                .contentType("application/json")
                .body("{\"description\":\"before-update\"}")
                .when()
                .post("/restapis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        String patchBody = "{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"after-update\"}]}";

        given()
                .contentType("application/json")
                .body(patchBody)
                .when()
                .patch("/restapis/" + apiId + "/deployments/" + deploymentId)
                .then()
                .statusCode(200)
                .body("id", equalTo(deploymentId))
                .body("description", equalTo("after-update"));

        given()
                .when()
                .get("/restapis/" + apiId + "/deployments/" + deploymentId)
                .then()
                .statusCode(200)
                .body("description", equalTo("after-update"));
    }
}
