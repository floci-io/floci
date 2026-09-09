package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
class ApiGatewayDeleteAuthorizerIntegrationTest {

    @Test
    void testDeleteAuthorizerFlow() {
        String apiId = given()
                .contentType("application/json")
                .body("{\"name\":\"delete-authorizer-api\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        String authorizerId = given()
                .contentType("application/json")
                .body("{\"name\":\"to-delete\",\"type\":\"TOKEN\",\"authorizerUri\":\"arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:any/invocations\"}")
                .when()
                .post("/restapis/" + apiId + "/authorizers")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .when()
                .get("/restapis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(200)
                .body("id", equalTo(authorizerId))
                .body("name", equalTo("to-delete"));

        given()
                .when()
                .get("/restapis/" + apiId + "/authorizers")
                .then()
                .statusCode(200)
                .body("item.id", hasItem(authorizerId));

        given()
                .when()
                .delete("/restapis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(202);

        given()
                .when()
                .get("/restapis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }
}
