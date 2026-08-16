package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Wire-level test for {@code ListFunctionsByCodeSigningConfig}.
 *
 * <p>New class, not appended to {@code LambdaCodeSigningIntegrationTest} (which
 * uses ordered setUp/tearDown fixtures this operation doesn't need), so
 * falsifiability isolates per operation (CS-001). No code signing config
 * management exists in this codebase (no Create/PutFunctionCodeSigningConfig), so
 * no function can ever actually have one attached — an always-empty result is the
 * honest answer, not a stub to fill in later.
 */
@QuarkusTest
class LambdaListFunctionsByCodeSigningConfigConsumerTest {

    @Test
    void listFunctionsByCodeSigningConfig_returnsEmptyList() {
        given()
        .when()
            .get("/2020-04-22/code-signing-configs/"
                    + "arn:aws:lambda:us-east-1:000000000000:code-signing-config:csc-0f6c334ab/functions")
        .then()
            .statusCode(200)
            .body("FunctionArns.size()", equalTo(0));
    }
}
