package io.floci.aws.testing;

import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

/** One EKS REST JSON request, signed for {@code account} in {@code region}. */
public final class EksRequests {

    private EksRequests() {}

    public static RequestSpecification eks(String account, String region) {
        return given().header("Authorization", EnforcementFixtures.auth(account, region, "eks"))
                .contentType("application/json");
    }
}
