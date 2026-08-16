package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ApiGatewayUpdateBasePathMappingIntegrationTest {

    @Test
    void testCreateAndUpdateBasePathMapping() {
        String apiId = given()
                .contentType("application/json")
                .body("{\"name\":\"mapping-api\"}")
                .when().post("/restapis").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body("{\"domainName\":\"mapping.example.test\"}").when().post("/domainnames").then().statusCode(201);
        String basePath = "v1";
        String stageBefore = "before";
        String createBody = "{\"basePath\":\"" + basePath + "\",\"restApiId\":\"" + apiId + "\",\"stage\":\"" + stageBefore + "\"}";
        given().contentType("application/json").body(createBody).when().post("/domainnames/mapping.example.test/basepathmappings").then().statusCode(201);
        String patchBody = "{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/stage\",\"value\":\"after\"}]}";
        given().contentType("application/json").body(patchBody).when().patch("/domainnames/mapping.example.test/basepathmappings/v1").then().statusCode(200).body("basePath", equalTo(basePath)).body("restApiId", equalTo(apiId)).body("stage", equalTo("after"));
        given().when().get("/domainnames/mapping.example.test/basepathmappings/v1").then().statusCode(200).body("stage", equalTo("after"));
    }
}
