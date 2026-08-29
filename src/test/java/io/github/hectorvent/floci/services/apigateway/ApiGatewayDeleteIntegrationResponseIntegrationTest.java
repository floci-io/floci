package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestHTTPEndpoint(ApiGatewayController.class)
class ApiGatewayDeleteIntegrationResponseIntegrationTest {

    @Test
    void testDeleteIntegrationResponse() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"delete-integration-response-api\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        String resourceId = given()
                .when()
                .get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .extract()
                .path("item[0].id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when()
                .put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\"}")
                .when()
                .put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"before\"}")
                .when()
                .put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration/responses/200")
                .then()
                .statusCode(201);

        given()
                .when()
                .delete("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration/responses/200")
                .then()
                .statusCode(204);

        given()
                .when()
                .get("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration/responses/200")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }
}
