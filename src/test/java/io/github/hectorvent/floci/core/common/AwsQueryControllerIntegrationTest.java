package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

@QuarkusTest
class AwsQueryControllerIntegrationTest {

    @Test
    void missingActionParameterReturns400MissingAction() {
        given()
            .contentType("application/x-www-form-urlencoded")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .contentType("application/xml")
            .body("ErrorResponse.Error.Code", equalTo("MissingAction"))
            .body("ErrorResponse.Error.Message", equalTo("The request must contain the parameter Action"));
    }

    @Test
    void ec2ActionFallbackWithoutAuthorizationHeader() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeRegions")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeRegionsResponse.regionInfo.item.size()", greaterThan(0));
    }
}
