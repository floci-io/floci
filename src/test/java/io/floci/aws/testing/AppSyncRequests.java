package io.floci.aws.testing;

import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

/** AppSync control-plane requests signed for an account and a Region, over the test's RestAssured port. */
public final class AppSyncRequests {

    private AppSyncRequests() {}

    /** A JSON request signed for {@code account} in {@code region}. */
    public static RequestSpecification signed(String account, String region) {
        return given()
                .header("Authorization", EnforcementFixtures.auth(account, region, "appsync"))
                .contentType("application/json");
    }
}
