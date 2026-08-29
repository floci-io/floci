package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestHTTPEndpoint(ApiGatewayController.class)
class ApiGatewayDeleteModelIntegrationTest {

    @Test
    void testDeleteModelFlow() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"delete-model-api\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"DeleteMe\",\"schema\":\"{}\"}")
                .when()
                .post("/restapis/" + apiId + "/models")
                .then()
                .statusCode(201);

        given()
                .when()
                .delete("/restapis/" + apiId + "/models/DeleteMe")
                .then()
                .statusCode(202);

        given()
                .when()
                .get("/restapis/" + apiId + "/models/DeleteMe")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }
}
