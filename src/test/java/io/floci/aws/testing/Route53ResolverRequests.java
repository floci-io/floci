package io.floci.aws.testing;

import io.restassured.response.ValidatableResponse;

import static io.restassured.RestAssured.given;

/** Shared Route 53 Resolver JSON 1.1 requests for integration tests. */
public final class Route53ResolverRequests {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    private Route53ResolverRequests() {}

    public static ValidatableResponse call(String action, String body, String authorization) {
        return given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "Route53Resolver." + action)
                .header("Authorization", authorization)
                .body(body)
        .when().post("/")
        .then();
    }
}
