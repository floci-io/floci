package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestHTTPEndpoint(ApiGatewayController.class)
class ApiGatewayDeleteRequestValidatorIntegrationTest {

    @Test
    void testDeleteRequestValidator() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"validator-delete-api\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        String validatorId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"DeleteValidator\",\"validateRequestBody\":true,\"validateRequestParameters\":false}")
                .when()
                .post("/restapis/" + apiId + "/requestvalidators")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .when()
                .delete("/restapis/" + apiId + "/requestvalidators/" + validatorId)
                .then()
                .statusCode(202);

        given()
                .when()
                .get("/restapis/" + apiId + "/requestvalidators/" + validatorId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }
}
