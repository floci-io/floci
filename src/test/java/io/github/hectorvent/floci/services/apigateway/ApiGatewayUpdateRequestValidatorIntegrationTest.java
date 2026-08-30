package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ApiGatewayUpdateRequestValidatorIntegrationTest {

    @Test
    void testUpdateRequestValidator() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"validator-update\"}")
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        String validatorId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"before\",\"validateRequestBody\":false,\"validateRequestParameters\":true}")
                .post("/restapis/" + apiId + "/requestvalidators")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        String patchBody = "{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"updated-validator\"},{\"op\":\"replace\",\"path\":\"/validateRequestBody\",\"value\":\"true\"},{\"op\":\"replace\",\"path\":\"/validateRequestParameters\",\"value\":\"false\"}]}";

        given()
                .contentType(ContentType.JSON)
                .body(patchBody)
                .patch("/restapis/" + apiId + "/requestvalidators/" + validatorId)
                .then()
                .statusCode(200)
                .body("name", equalTo("updated-validator"))
                .body("validateRequestBody", equalTo(true))
                .body("validateRequestParameters", equalTo(false));

        given()
                .get("/restapis/" + apiId + "/requestvalidators/" + validatorId)
                .then()
                .statusCode(200)
                .body("name", equalTo("updated-validator"))
                .body("validateRequestBody", equalTo(true))
                .body("validateRequestParameters", equalTo(false));
    }
}
