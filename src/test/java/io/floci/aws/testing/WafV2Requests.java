package io.floci.aws.testing;

import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

public final class WafV2Requests {
    private WafV2Requests() {}

    public static ValidatableResponse waf(String action, String body) {
        return send(request(action, body));
    }

    /** As above, signed for {@code region}, so the call runs there. */
    public static ValidatableResponse waf(String action, String region, String body) {
        return send(request(action, body).header("Authorization", EnforcementFixtures.auth("AKID", region, "wafv2")));
    }

    private static RequestSpecification request(String action, String body) {
        return given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSWAF_20190729." + action)
            .body(body);
    }

    private static ValidatableResponse send(RequestSpecification request) {
        return request
        .when()
            .post("/")
        .then();
    }
}
