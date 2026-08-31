package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ApiGatewayUpdateMethodIntegrationTest {

    @Test
    void testUpdateMethodAuthorizationType() {
        String apiId = given()
                .contentType("application/json")
                .body("{\"name\":\"method-update\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        String rootId = given()
                .when()
                .get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .extract()
                .path("item[0].id");

        String childId = given()
                .contentType("application/json")
                .body("{\"pathPart\":\"items\"}")
                .when()
                .post("/restapis/" + apiId + "/resources/" + rootId)
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .contentType("application/json")
                .body("{\"httpMethod\":\"GET\",\"authorizationType\":\"NONE\"}")
                .when()
                .put("/restapis/" + apiId + "/resources/" + childId + "/methods/GET")
                .then()
                .statusCode(201);

        String patchBody = "{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/authorizationType\",\"value\":\"AWS_IAM\"}]}";
        given()
                .contentType("application/json")
                .body(patchBody)
                .when()
                .patch("/restapis/" + apiId + "/resources/" + childId + "/methods/GET")
                .then()
                .statusCode(200).body("httpMethod", equalTo("GET")).body("authorizationType", equalTo("AWS_IAM"));

        given()
                .when()
                .get("/restapis/" + apiId + "/resources/" + childId + "/methods/GET")
                .then()
                .statusCode(200)
                .body("authorizationType", equalTo("AWS_IAM"));
    }
}
