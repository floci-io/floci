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
 * falsifiability isolates per operation (CS-001).</p>
 *
 * <p>The identifier is validated even though Floci models no code-signing
 * configs: botocore models both {@code InvalidParameterValueException} and
 * {@code ResourceNotFoundException} for this operation, and the only resource
 * the ARN can name is a code-signing config. Since there is no
 * CreateCodeSigningConfig / PutFunctionCodeSigningConfig in this codebase, every
 * well-formed ARN necessarily names a config that does not exist, so the modeled
 * 404 — not a silent empty list — is the honest answer.</p>
 */
@QuarkusTest
class LambdaListFunctionsByCodeSigningConfigConsumerTest {

    /** Matches botocore's CodeSigningConfigArn pattern: csc- plus 17 lowercase alphanumerics. */
    private static final String WELL_FORMED_ARN =
            "arn:aws:lambda:us-east-1:000000000000:code-signing-config:csc-0f6c334ab1234567c";

    private static String path(String arn) {
        return "/2020-04-22/code-signing-configs/" + arn + "/functions";
    }

    @Test
    void wellFormedButUnknownConfig_returnsResourceNotFound() {
        given()
        .when()
            .get(path(WELL_FORMED_ARN))
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    /**
     * The model's region alternation is {@code (eusc-)?[a-z]{2}((-gov)|(-iso([a-z]?)))?-[a-z]+-\d},
     * which admits the newer isolated partitions (iso-e/iso-f) and the European sovereign
     * cloud. A well-formed ARN in one of those must reach the modeled 404, not be rejected
     * as malformed.
     */
    @Test
    void wellFormedIsolatedPartitionArn_returnsResourceNotFound() {
        given()
        .when()
            .get(path("arn:aws-iso-e:lambda:us-isoe-east-1:000000000000:"
                    + "code-signing-config:csc-0f6c334ab1234567c"))
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void malformedArn_returnsInvalidParameterValue() {
        given()
        .when()
            .get(path("not-an-arn"))
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    void shortSuffixArn_returnsInvalidParameterValue() {
        given()
        .when()
            .get(path("arn:aws:lambda:us-east-1:000000000000:code-signing-config:csc-0f6c334ab"))
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    void maxItemsBelowRange_returnsInvalidParameterValue() {
        given()
            .queryParam("MaxItems", 0)
        .when()
            .get(path(WELL_FORMED_ARN))
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    void maxItemsAboveRange_returnsInvalidParameterValue() {
        given()
            .queryParam("MaxItems", 10001)
        .when()
            .get(path(WELL_FORMED_ARN))
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    void nonNumericMaxItems_returnsInvalidParameterValue() {
        given()
            .queryParam("MaxItems", "many")
        .when()
            .get(path(WELL_FORMED_ARN))
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"));
    }
}
