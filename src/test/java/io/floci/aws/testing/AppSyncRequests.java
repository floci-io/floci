package io.floci.aws.testing;

import io.restassured.specification.RequestSpecification;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;

/** AppSync control-plane requests signed for an account and a Region, over the test's RestAssured port. */
public final class AppSyncRequests {

    private AppSyncRequests() {}

    /** A JSON request signed for {@code account} in {@code region}. */
    public static RequestSpecification signed(String account, String region) {
        return given()
                .header("Authorization", EnforcementFixtures.auth(account, region, "appsync"))
                .contentType("application/json");
    }

    /** Waits until the schema creation of {@code apiId} has succeeded, its status read with {@code authorization}. */
    public static void awaitSchemaSuccess(String apiId, String authorization) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25)).until(() ->
                "SUCCESS".equals(given().header("Authorization", authorization)
                        .when().get("/v1/apis/" + apiId + "/schemacreation")
                        .then().statusCode(200).extract().path("status")));
    }
}
