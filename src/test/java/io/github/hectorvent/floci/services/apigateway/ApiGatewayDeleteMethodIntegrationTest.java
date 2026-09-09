package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestHTTPEndpoint(ApiGatewayController.class)
class ApiGatewayDeleteMethodIntegrationTest {

    @Test
    void testDeleteMethodWorkflow() {
        String apiId = given()
                .contentType(JSON)
                .body("{\"name\":\"delete-method-api\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        String resourceId = given()
                .pathParam("apiId", apiId)
                .when()
                .get("/restapis/{apiId}/resources")
                .then()
                .statusCode(200)
                .extract()
                .path("item[0].id");

        given()
                .contentType(JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when()
                .put("/restapis/{apiId}/resources/{resourceId}/methods/GET", apiId, resourceId)
                .then()
                .statusCode(201);

        given()
                .when()
                .delete("/restapis/{apiId}/resources/{resourceId}/methods/GET", apiId, resourceId)
                .then()
                .statusCode(202);

        given()
                .when()
                .get("/restapis/{apiId}/resources/{resourceId}/methods/GET", apiId, resourceId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }
}
