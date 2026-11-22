package io.floci.aws.testing;

import io.restassured.response.ValidatableResponse;

import static io.restassured.RestAssured.given;

/**
 * Service Catalog JSON calls signed for an account and a Region, over the test's RestAssured port. The caller
 * registers the AWS JSON content types first ({@link RestAssuredJsonUtils#configureAwsContentTypes}).
 */
public final class ServiceCatalogRequests {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    private ServiceCatalogRequests() {}

    /** {@code AWS242ServiceCatalogService.<action>} with {@code body}, signed for {@code account} in {@code region}. */
    public static ValidatableResponse call(String action, String account, String region, String body) {
        return given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWS242ServiceCatalogService." + action)
                .header("Authorization", EnforcementFixtures.auth(account, "20260918", region, "servicecatalog"))
                .body(body)
        .when().post("/")
        .then();
    }
}
