package io.github.hectorvent.floci.services.ecr;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for the ECR GC admin endpoint.
 * When run in isolation, the registry is not started so the endpoint returns 400.
 * When run as part of the full suite, EcrIntegrationTest may have already started
 * the registry, in which case GC actually runs and returns 200.
 */
@QuarkusTest
class EcrGcControllerTest {

    @Test
    void gcEndpoint_returnsValidResponseShape() {
        given()
            .contentType("application/json")
        .when()
            .post("/_floci/ecr/gc")
        .then()
            .statusCode(anyOf(is(200), is(400), is(500)))
            .body("status", anyOf(equalTo("ok"), equalTo("error")))
            .body("output", notNullValue())
            .body("durationMs", notNullValue());
    }

    @Test
    void gcEndpoint_getMethodNotAllowed() {
        given()
        .when()
            .get("/_floci/ecr/gc")
        .then()
            .statusCode(anyOf(is(404), is(405)));
    }
}
