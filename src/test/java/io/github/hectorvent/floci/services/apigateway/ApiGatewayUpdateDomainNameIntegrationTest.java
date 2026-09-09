package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ApiGatewayUpdateDomainNameIntegrationTest {

    @Test
    void testUpdateDomainName() {
        // POST /domainnames
        given()
                .contentType("application/json")
                .body("{\"domainName\":\"update.example.test\",\"certificateName\":\"before\"}")
                .when()
                .post("/domainnames")
                .then()
                .statusCode(201);

        // PATCH /domainnames/update.example.test
        given()
                .contentType("application/json")
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/certificateName\",\"value\":\"after\"},{\"op\":\"replace\",\"path\":\"/securityPolicy\",\"value\":\"TLS_1_0\"}]}")
                .when()
                .patch("/domainnames/update.example.test")
                .then()
                .statusCode(200)
                .body("domainName", equalTo("update.example.test"))
                .body("certificateName", equalTo("after"))
                .body("securityPolicy", equalTo("TLS_1_0"));

        // GET /domainnames/update.example.test
        given()
                .when()
                .get("/domainnames/update.example.test")
                .then()
                .statusCode(200)
                .body("domainName", equalTo("update.example.test"))
                .body("certificateName", equalTo("after"))
                .body("securityPolicy", equalTo("TLS_1_0"));
    }
}
