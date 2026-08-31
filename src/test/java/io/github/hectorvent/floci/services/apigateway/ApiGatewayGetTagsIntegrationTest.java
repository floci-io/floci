package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ApiGatewayGetTagsIntegrationTest {

    @Test
    void testGetTags() {
        String apiId = given()
                .contentType("application/json")
                .body("{\"name\":\"get-tags-api\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        String resourceArn = "arn:aws:apigateway:us-east-1::/restapis/" + apiId;

        given()
                .pathParam("resourceArn", resourceArn)
                .contentType("application/json")
                .body("{\"tags\":{\"owner\":\"network\"}}")
                .when()
                .put("/tags/{resourceArn}")
                .then()
                .statusCode(204);

        given()
                .pathParam("resourceArn", resourceArn)
                .when()
                .get("/tags/{resourceArn}")
                .then()
                .statusCode(200)
                .body("tags.owner", equalTo("network"));
    }
}
