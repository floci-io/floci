package io.floci.aws.core.common;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * A request no resource method consumes is answered with routing's own status. The response filters
 * also run for it, and reading the routed resource there used to fail with a 500.
 */
@QuarkusTest
class UnroutableRequestIntegrationTest {

    @Test
    void aRootPostNoEndpointConsumesGetsUnsupportedMediaType() {
        given()
                .contentType("text/plain")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(415);
    }

    @Test
    void aTargetedCallInAContentTypeNoEndpointConsumesGetsUnsupportedMediaType() {
        // No protocol claims it either: an X-Amz-Target post with a content type no protocol uses.
        given()
                .contentType("text/plain")
                .header("X-Amz-Target", "AmazonSQS.ListQueues")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(415);
    }
}
