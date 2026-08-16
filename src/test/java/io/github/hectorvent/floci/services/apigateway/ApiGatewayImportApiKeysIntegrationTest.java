package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class ApiGatewayImportApiKeysIntegrationTest {

    @Test
    void testImportApiKeys() {
        String importedId = given()
                .contentType("text/csv")
                .body("name,value,enabled\nimported-key,secret-value,true\n")
                .when()
                .post("/apikeys?mode=import&format=csv&failonwarnings=false")
                .then()
                .statusCode(201)
                .body("ids", hasSize(1))
                .extract().path("ids[0]");

        given()
                .pathParam("importedId", importedId)
                .when()
                .get("/apikeys/{importedId}?includeValue=true")
                .then()
                .statusCode(200)
                .body("name", org.hamcrest.Matchers.equalTo("imported-key"))
                .body("value", org.hamcrest.Matchers.equalTo("secret-value"))
                .body("enabled", org.hamcrest.Matchers.equalTo(true));
    }
}
