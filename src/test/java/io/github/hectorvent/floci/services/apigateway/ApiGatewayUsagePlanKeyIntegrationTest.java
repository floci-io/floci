package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestHTTPEndpoint(ApiGatewayController.class)
public class ApiGatewayUsagePlanKeyIntegrationTest {

    @Test
    public void testUsagePlanKeyLifecycle() {
        String apiKeyId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"usage-key\",\"enabled\":true}")
                .when()
                .post("/apikeys")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .extract()
                .path("id");

        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"key-plan\"}")
                .when()
                .post("/usageplans")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .extract()
                .path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"keyId\":\"" + apiKeyId + "\",\"keyType\":\"API_KEY\"}")
                .when()
                .post("/usageplans/" + usagePlanId + "/keys")
                .then()
                .statusCode(201)
                .body("id", equalTo(apiKeyId));

        given()
                .when()
                .get("/usageplans/" + usagePlanId + "/keys")
                .then()
                .statusCode(200)
                .body("item[0].id", equalTo(apiKeyId));

        given()
                .when()
                .get("/usageplans/" + usagePlanId + "/keys/" + apiKeyId)
                .then()
                .statusCode(200)
                .body("id", equalTo(apiKeyId));

        given()
                .when()
                .delete("/usageplans/" + usagePlanId + "/keys/" + apiKeyId)
                .then()
                .statusCode(202);
    }
}
