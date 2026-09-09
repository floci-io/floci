package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
public class ApiGatewayUpdateRestApiIntegrationTest {

    @Test
    public void testUpdateRestApi() {
        String id = given()
                .contentType("application/json")
                .body("{\"name\":\"rest-api-update\",\"description\":\"before\"}")
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        String patchBody = "{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"updated description\"}]}";

        given()
                .contentType("application/json")
                .body(patchBody)
                .patch("/restapis/" + id)
                .then()
                .statusCode(200)
                .body("name", equalTo("rest-api-update"))
                .body("description", equalTo("updated description"));

        given()
                .get("/restapis/" + id)
                .then()
                .statusCode(200)
                .body("description", equalTo("updated description"));
    }
}
