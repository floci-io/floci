package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ApiGatewayDeleteResourceIntegrationTest {

    @Test
    void testDeleteResource() {
        String apiId = given()
                .contentType("application/json")
                .body("{\"name\":\"delete-resource-api\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        String rootId = given()
                .when()
                .get("/restapis/{apiId}/resources", apiId)
                .then()
                .statusCode(200)
                .extract()
                .path("item[0].id");

        String resourceId = given()
                .contentType("application/json")
                .pathParam("apiId", apiId)
                .pathParam("parentId", rootId)
                .body("{\"pathPart\":\"gone\"}")
                .when()
                .post("/restapis/{apiId}/resources/{parentId}")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .pathParam("apiId", apiId)
                .pathParam("resourceId", resourceId)
                .when()
                .delete("/restapis/{apiId}/resources/{resourceId}")
                .then()
                .statusCode(202);

        given()
                .pathParam("apiId", apiId)
                .pathParam("resourceId", resourceId)
                .when()
                .get("/restapis/{apiId}/resources/{resourceId}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }
}
