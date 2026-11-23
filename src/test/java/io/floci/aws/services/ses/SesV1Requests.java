package io.floci.aws.services.ses;

import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

/**
 * Builds SES v1 Query requests for the integration tests: a form-encoded body carrying the
 * {@code Action} parameter, with an Authorization header scoped to {@code email} in a region. The
 * overload without an authorization uses {@link #AUTH} (us-east-1); a test that keeps state in a
 * region of its own passes {@link #authFor} of that region to every request.
 */
final class SesV1Requests {

    static final String AUTH = authFor("us-east-1");

    private SesV1Requests() {
    }

    static String authFor(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + region + "/email/aws4_request";
    }

    static RequestSpecification query(String action) {
        return query(AUTH, action);
    }

    static RequestSpecification query(String authorization, String action) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", authorization)
                .formParam("Action", action);
    }
}
