package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ApiGatewayUpdateStageIntegrationTest {
    @Test
    void testUpdateStageDescription() {
        String apiId = given()
                .contentType("application/json")
                .body("{\"name\":\"stage-update\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        String rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        String deploymentId = given().contentType("application/json")
                .body("{\"description\":\"v1\"}")
                .when()
                .post("/restapis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        given().contentType("application/json")
                .body("{\"stageName\":\"dev\",\"deploymentId\":\"" + deploymentId + "\",\"description\":\"before\"}")
                .when()
                .post("/restapis/" + apiId + "/stages")
                .then()
                .statusCode(201);
        String patchBody = "{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"updated\"}]}";
        given().contentType("application/json").body(patchBody)
                .when()
                .patch("/restapis/" + apiId + "/stages/dev")
                .then()
                .statusCode(200)
                .body("description", equalTo("updated"));
        given().when().get("/restapis/" + apiId + "/stages/dev")
                .then()
                .statusCode(200)
                .body("description", equalTo("updated"));
    }
}
