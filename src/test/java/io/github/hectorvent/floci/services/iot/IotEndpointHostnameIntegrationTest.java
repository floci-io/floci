package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.testing.ConfiguredHostnameProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(ConfiguredHostnameProfile.class)
class IotEndpointHostnameIntegrationTest {

    @Test
    void configuredHostnameIsUsedInDescribeEndpoint() {
        given()
        .when()
            .get("/endpoint")
        .then()
            .statusCode(200)
            .body("endpointAddress", equalTo("floci:4566"));
    }
}
