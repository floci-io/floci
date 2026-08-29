package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestHTTPEndpoint(ApiGatewayController.class)
class ApiGatewayUsagePlanCrudIntegrationTest {

    @Test
    void testCreateAndRetrieveUsagePlan() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"before\",\"description\":\"old\"}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":["
                        + "{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"after\"},"
                        + "{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"new\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("name", equalTo("after"))
                .body("description", equalTo("new"));

        given()
                .get("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("id", equalTo(usagePlanId))
                .body("name", equalTo("after"))
                .body("description", equalTo("new"));
    }

    @Test
    void testUnsupportedPatchPathIsRejected() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"reject-path\",\"description\":\"old\"}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/nope\",\"value\":\"x\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(400);
    }
}
