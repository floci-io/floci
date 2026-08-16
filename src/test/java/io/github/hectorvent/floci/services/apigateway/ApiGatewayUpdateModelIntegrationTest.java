package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestHTTPEndpoint(ApiGatewayController.class)
class ApiGatewayUpdateModelIntegrationTest {

    @Test
    void testCreateApiAndModelWorkflow() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"model-test-api\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        String modelName = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"TestModel\",\"description\":\"old\",\"contentType\":\"application/json\",\"schema\":\"{}\"}")
                .pathParam("apiId", apiId)
                .when()
                .post("/restapis/{apiId}/models")
                .then()
                .statusCode(201)
                .extract()
                .path("name");

        given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"new\"}")
                .pathParam("apiId", apiId)
                .pathParam("modelName", modelName)
                .when()
                .patch("/restapis/{apiId}/models/{modelName}")
                .then()
                .statusCode(200)
                .body("description", equalTo("new"));

        given()
                .pathParam("apiId", apiId)
                .pathParam("modelName", modelName)
                .when()
                .get("/restapis/{apiId}/models/{modelName}")
                .then()
                .statusCode(200)
                .body("name", equalTo("TestModel"))
                .body("description", equalTo("new"));
    }
}
