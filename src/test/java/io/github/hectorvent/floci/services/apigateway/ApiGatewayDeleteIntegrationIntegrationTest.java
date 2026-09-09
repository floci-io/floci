package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
public class ApiGatewayDeleteIntegrationIntegrationTest {

    @Test
    public void testDeleteIntegration() {
        String apiId = given()
                .contentType("application/json")
                .body("{\"name\":\"delete-integration-api\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        String rootId = given()
                .pathParam("apiId", apiId)
                .when()
                .get("/restapis/{apiId}/resources")
                .then()
                .statusCode(200)
                .extract()
                .path("item[0].id");

        String resourceId = given()
                .pathParam("apiId", apiId)
                .pathParam("parentId", rootId)
                .contentType("application/json")
                .body("{\"pathPart\":\"work\"}")
                .when()
                .post("/restapis/{apiId}/resources/{parentId}")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .pathParam("apiId", apiId)
                .pathParam("resourceId", resourceId)
                .contentType("application/json")
                .body("{\"authorizationType\":\"NONE\"}")
                .when()
                .put("/restapis/{apiId}/resources/{resourceId}/methods/GET")
                .then()
                .statusCode(201);

        given()
                .pathParam("apiId", apiId)
                .pathParam("resourceId", resourceId)
                .contentType("application/json")
                .body("{\"type\":\"MOCK\"}")
                .when()
                .put("/restapis/{apiId}/resources/{resourceId}/methods/GET/integration")
                .then()
                .statusCode(201);

        given()
                .pathParam("apiId", apiId)
                .pathParam("resourceId", resourceId)
                .when()
                .delete("/restapis/{apiId}/resources/{resourceId}/methods/GET/integration")
                .then()
                .statusCode(204);

        given()
                .pathParam("apiId", apiId)
                .pathParam("resourceId", resourceId)
                .when()
                .get("/restapis/{apiId}/resources/{resourceId}/methods/GET/integration")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }
}
