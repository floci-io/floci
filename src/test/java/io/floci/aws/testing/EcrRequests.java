package io.floci.aws.testing;

import static io.restassured.RestAssured.given;

/** ECR JSON requests signed for an account in a region, which must succeed. */
public final class EcrRequests {

    private EcrRequests() {
    }

    public static void ecr(String account, String region, String action, String body) {
        given().header("X-Amz-Target", "AmazonEC2ContainerRegistry_V20150921." + action)
                .header("Authorization", EnforcementFixtures.auth(account, region, "ecr"))
                .contentType("application/x-amz-json-1.1")
                .body(body)
                .when().post("/")
                .then().statusCode(200);
    }
}
