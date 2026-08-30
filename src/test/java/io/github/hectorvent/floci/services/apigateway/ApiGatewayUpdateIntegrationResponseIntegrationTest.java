package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ApiGatewayUpdateIntegrationResponseIntegrationTest {

    @Test
    void testUpdateIntegrationResponse() {
        String apiId = given().contentType("application/json").body("{\"name\":\"ir-update\"}").when().post("/restapis").then().statusCode(201).extract().path("id");
        String rootId = given().when().get("/restapis/" + apiId + "/resources").then().statusCode(200).extract().path("item[0].id");
        String resourceId = given().contentType("application/json").body("{\"pathPart\":\"items\"}").when().post("/restapis/" + apiId + "/resources/" + rootId).then().statusCode(201).extract().path("id");
        given().contentType("application/json").body("{\"authorizationType\":\"NONE\"}").when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET").then().statusCode(201);
        given().contentType("application/json").body("{\"type\":\"MOCK\"}").when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration").then().statusCode(201);
        given().contentType("application/json").body("{\"selectionPattern\":\"before\",\"responseTemplates\":{\"application/json\":\"before\"}}").when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration/responses/200").then().statusCode(201);
        given().contentType("application/json").body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/selectionPattern\",\"value\":\"after\"}]}").when().patch("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration/responses/200").then().statusCode(200).body("statusCode", equalTo("200")).body("selectionPattern", equalTo("after"));
        given().when().get("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration/responses/200").then().statusCode(200).body("selectionPattern", equalTo("after"));
    }
}
