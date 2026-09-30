package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.nullValue;

/**
 * With the configured defaults, no extra origin is allowed, so the global filter adds no CORS
 * headers for a browser origin, on a preflight or on the actual request.
 */
@QuarkusTest
class GlobalCorsFilterDefaultIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void preflightReturnsForbiddenWithoutCorsHeaders() {
        given()
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "PUT")
        .when()
            .options("/my-bucket/some-key")
        .then()
            .statusCode(403)
            .header("Access-Control-Allow-Origin", nullValue());
    }

    @Test
    void actualRequestGetsNoCorsHeaders() {
        given()
            .header("Origin", "http://localhost:3000")
            .header("X-Amz-Target", "DynamoDB_20120810.ListTables")
            .contentType("application/x-amz-json-1.0")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", nullValue());
    }
}
