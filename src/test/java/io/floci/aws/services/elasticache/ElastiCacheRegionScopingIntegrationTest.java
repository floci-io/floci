package io.floci.aws.services.elasticache;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * ElastiCache users (like replication groups and cache clusters) are Regional on AWS: one created
 * in a Region is not listed or reachable from another Region of the same account.
 */
@QuarkusTest
class ElastiCacheRegionScopingIntegrationTest {

    private static final String ACCOUNT = "975318642097";
    private static final String USER = "region-scoped-user";
    // Each Region also holds the built-in "default" user, so look for this user's element.
    private static final String USER_ID = "<UserId>" + USER + "</UserId>";

    @Test
    void userIsOnlyVisibleInItsRegion() {
        given().header("Authorization", auth("eu-west-1"))
                .formParam("Action", "CreateUser").formParam("UserId", USER).formParam("UserName", USER)
                .formParam("Engine", "redis").formParam("AccessString", "on ~* +@all")
                .formParam("AuthenticationMode.Type", "no-password-required")
                .when().post("/").then().statusCode(200);

        try {
            given().header("Authorization", auth("eu-west-1")).formParam("Action", "DescribeUsers")
                    .when().post("/").then().statusCode(200).body(containsString(USER_ID));

            given().header("Authorization", auth("eu-central-1")).formParam("Action", "DescribeUsers")
                    .when().post("/").then().statusCode(200).body(not(containsString(USER_ID)));
            given().header("Authorization", auth("eu-central-1")).formParam("Action", "DescribeUsers")
                    .formParam("UserId", USER)
                    .when().post("/").then().statusCode(404);
        } finally {
            given().header("Authorization", auth("eu-west-1"))
                    .formParam("Action", "DeleteUser").formParam("UserId", USER)
                    .when().post("/").then().statusCode(200);
        }
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261110/" + region
                + "/elasticache/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
