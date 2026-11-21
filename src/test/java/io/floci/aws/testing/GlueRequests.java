package io.floci.aws.testing;

import io.restassured.response.ValidatableResponse;

import static io.restassured.RestAssured.given;

/**
 * Glue JSON calls signed for an account and a Region, over the test's RestAssured port. The caller
 * registers the AWS JSON content types first ({@link RestAssuredJsonUtils#configureAwsContentTypes}).
 */
public final class GlueRequests {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    private GlueRequests() {}

    /** {@code AWSGlue.<action>} with {@code body}, signed for {@code account} in {@code region}. */
    public static ValidatableResponse call(String action, String account, String region, String body) {
        return given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue." + action)
                .header("Authorization", EnforcementFixtures.auth(account, "20260918", region, "glue"))
                .body(body)
        .when().post("/")
        .then();
    }
}
