package io.github.hectorvent.floci.services.ram;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies the RAM restJson1 organization-sharing opt-in:
 * {@code POST /enablesharingwithawsorganization} succeeds with or without a request body.
 */
@QuarkusTest
class RamIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ram/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void enableSharingWithAwsOrganization_returnsTrue() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/enablesharingwithawsorganization")
        .then()
            .statusCode(200)
            .body("returnValue", equalTo(true));
    }

    @Test
    void enableSharingWithAwsOrganization_withoutBody_returnsTrue() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/enablesharingwithawsorganization")
        .then()
            .statusCode(200)
            .body("returnValue", equalTo(true));
    }
}
