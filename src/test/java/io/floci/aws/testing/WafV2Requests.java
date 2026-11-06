package io.floci.aws.testing;

import io.restassured.response.ValidatableResponse;

import static io.restassured.RestAssured.given;

public final class WafV2Requests {
    private WafV2Requests() {}

    public static ValidatableResponse waf(String action, String body) {
        return given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSWAF_20190729." + action)
            .body(body)
        .when()
            .post("/")
        .then();
    }
}
