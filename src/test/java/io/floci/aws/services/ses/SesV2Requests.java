package io.floci.aws.services.ses;

import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

/**
 * Builds SES v2 REST JSON requests for the integration tests: a JSON body with an Authorization
 * header scoped to SES in a region. The overloads without an authorization use {@link #AUTH}
 * (us-east-1); a test that keeps state in a region of its own passes {@link #authFor} of that
 * region to every request.
 */
final class SesV2Requests {

    static final String AUTH = authFor("us-east-1");
    static final String TO = "success@simulator.amazonses.com";
    static final String DESTINATION = "\"Destination\": {\"ToAddresses\": [\"" + TO + "\"]}";
    static final String ENTRY = "{" + DESTINATION + "}";

    private SesV2Requests() {
    }

    static String authFor(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + region + "/ses/aws4_request";
    }

    static RequestSpecification v2() {
        return v2(AUTH);
    }

    static RequestSpecification v2(String authorization) {
        return given().contentType("application/json").header("Authorization", authorization);
    }

    static ValidatableResponse post(String path, String body) {
        return post(AUTH, path, body);
    }

    static ValidatableResponse post(String authorization, String path, String body) {
        return v2(authorization).body(body).when().post(path).then();
    }
}
