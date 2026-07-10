package io.github.hectorvent.floci.services.rum;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies the minimal CloudWatch RUM service: an app monitor can be created (returns an Id),
 * fetched, and deleted over the restJson1 REST routes — so the CDK RUM AppMonitor custom resource
 * gets JSON instead of the S3 catch-all's XML error. The literal {@code /appmonitor} routes must win
 * over S3's {@code /{bucket}} template.
 */
@QuarkusTest
class RumControllerTest {

    private static final String RUM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/rum/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void appMonitorCreateGetDeleteLifecycle() {
        String name = "floci-rum-" + Long.toString(System.nanoTime(), 36);

        // CreateAppMonitor (POST /appmonitor) — extra SDK fields must be ignored — returns an Id.
        given()
            .contentType("application/json")
            .header("Authorization", RUM_AUTH)
            .body("{\"Name\":\"" + name + "\",\"Domain\":\"localhost\","
                    + "\"AppMonitorConfiguration\":{\"AllowCookies\":true},\"CwLogEnabled\":true}")
        .when()
            .post("/appmonitor")
        .then()
            .statusCode(200)
            .body("Id", notNullValue());

        // GetAppMonitor (GET /appmonitor/{Name}) — must beat the S3 object route and return JSON.
        given()
            .contentType("application/json")
            .header("Authorization", RUM_AUTH)
        .when()
            .get("/appmonitor/" + name)
        .then()
            .statusCode(200)
            .body("AppMonitor.Name", equalTo(name))
            .body("AppMonitor.State", equalTo("CREATED"));

        // ListAppMonitors (POST /appmonitors) — single-segment literal must win over S3's /{bucket}.
        given()
            .contentType("application/json")
            .header("Authorization", RUM_AUTH)
        .when()
            .post("/appmonitors")
        .then()
            .statusCode(200)
            .body("AppMonitorSummaries.Name", hasItem(name));

        // DeleteAppMonitor (DELETE /appmonitor/{Name}).
        given()
            .contentType("application/json")
            .header("Authorization", RUM_AUTH)
        .when()
            .delete("/appmonitor/" + name)
        .then()
            .statusCode(200);

        // Gone afterwards.
        given()
            .contentType("application/json")
            .header("Authorization", RUM_AUTH)
        .when()
            .get("/appmonitor/" + name)
        .then()
            .statusCode(404);
    }
}
