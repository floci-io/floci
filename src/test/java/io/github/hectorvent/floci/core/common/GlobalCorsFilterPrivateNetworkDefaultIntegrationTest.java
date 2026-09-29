package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.ExtraCorsOriginProfile;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * An extra allowed origin on its own does not grant private network access:
 * {@code cors-allow-private-network} stays at its off default.
 */
@QuarkusTest
@TestProfile(ExtraCorsOriginProfile.class)
class GlobalCorsFilterPrivateNetworkDefaultIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void privateNetworkAccessNotGrantedByDefault() {
        given()
            .header("Origin", ExtraCorsOriginProfile.ORIGIN)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Private-Network", "true")
        .when()
            .options("/")
        .then()
            .statusCode(204)
            .header("Access-Control-Allow-Origin", equalTo(ExtraCorsOriginProfile.ORIGIN))
            .header("Access-Control-Allow-Private-Network", nullValue());
    }
}
