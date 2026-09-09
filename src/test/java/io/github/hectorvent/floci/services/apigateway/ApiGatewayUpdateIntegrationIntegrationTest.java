package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ApiGatewayUpdateIntegrationIntegrationTest {

    @Test
    void testApiGatewayIntegrationUpdate() {
        String apiId = given()
                .contentType("application/json")
                .body("{\"name\": \"integration-update\"}")
                .when().post("/restapis").then().statusCode(201).extract().path("id");
        String rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        String resourceId = given()
                .contentType("application/json")
                .body("{\"pathPart\": \"items\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");
        given().contentType("application/json").body("{\"authorizationType\": \"NONE\"}").when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET").then().statusCode(201);
        given().contentType("application/json").body("{\"type\": \"MOCK\", \"passthroughBehavior\": \"WHEN_NO_MATCH\"}").when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration").then().statusCode(201);
        given().contentType("application/json").body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/passthroughBehavior\",\"value\":\"NEVER\"}]} ").when().patch("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration").then().statusCode(200).body("type", equalTo("MOCK")).body("passthroughBehavior", equalTo("NEVER"));
        given().when().get("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration").then().statusCode(200).body("passthroughBehavior", equalTo("NEVER"));
    }

    @Test
    void testRejectedPatchDoesNotPartiallyApply() {
        String apiId = given()
                .contentType("application/json")
                .body("{\"name\": \"integration-update-reject\"}")
                .when().post("/restapis").then().statusCode(201).extract().path("id");
        String rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        String resourceId = given()
                .contentType("application/json")
                .body("{\"pathPart\": \"items\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");
        given().contentType("application/json").body("{\"authorizationType\": \"NONE\"}").when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET").then().statusCode(201);
        given().contentType("application/json").body("{\"type\": \"MOCK\", \"passthroughBehavior\": \"WHEN_NO_MATCH\"}").when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration").then().statusCode(201);

        given().contentType("application/json")
                .body("{\"patchOperations\":["
                        + "{\"op\":\"replace\",\"path\":\"/passthroughBehavior\",\"value\":\"NEVER\"},"
                        + "{\"op\":\"replace\",\"path\":\"/notASupportedPath\",\"value\":\"x\"}"
                        + "]}")
                .when().patch("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration")
                .then().statusCode(400);

        given().when().get("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration")
                .then().statusCode(200).body("passthroughBehavior", equalTo("WHEN_NO_MATCH"));
    }
}
