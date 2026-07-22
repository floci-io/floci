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
 * Verifies the minimal CloudWatch RUM service: an app monitor can be created, updated, fetched,
 * listed, and deleted over the restJson1 REST routes. The literal routes must win over S3's
 * catch-all templates so SDK clients always receive JSON responses.
 */
@QuarkusTest
class RumControllerIntegrationTest {

    private static final String RUM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/rum/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void appMonitorCreateUpdateGetListDeleteLifecycle() {
        String name = "floci-rum-" + Long.toString(System.nanoTime(), 36);

        String id = given()
                .contentType("application/json")
                .header("Authorization", RUM_AUTH)
                .body("{\"Name\":\"" + name + "\",\"Domain\":\"localhost\","
                        + "\"AppMonitorConfiguration\":{\"AllowCookies\":true},\"CwLogEnabled\":true}")
                .when()
                .post("/appmonitor")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .extract().path("Id");

        given()
                .contentType("application/json")
                .header("Authorization", RUM_AUTH)
                .when()
                .get("/appmonitor/" + name)
                .then()
                .statusCode(200)
                .body("AppMonitor.Id", equalTo(id))
                .body("AppMonitor.Name", equalTo(name))
                .body("AppMonitor.Domain", equalTo("localhost"))
                .body("AppMonitor.State", equalTo("CREATED"));

        given()
                .contentType("application/json")
                .header("Authorization", RUM_AUTH)
                .body("{\"Domain\":\"updated.example.com\"}")
                .when()
                .patch("/appmonitor/" + name)
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", RUM_AUTH)
                .when()
                .get("/appmonitor/" + name)
                .then()
                .statusCode(200)
                .body("AppMonitor.Id", equalTo(id))
                .body("AppMonitor.Domain", equalTo("updated.example.com"));

        given()
                .contentType("application/json")
                .header("Authorization", RUM_AUTH)
                .when()
                .post("/appmonitors")
                .then()
                .statusCode(200)
                .body("AppMonitorSummaries.Name", hasItem(name));

        given()
                .contentType("application/json")
                .header("Authorization", RUM_AUTH)
                .when()
                .delete("/appmonitor/" + name)
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", RUM_AUTH)
                .when()
                .get("/appmonitor/" + name)
                .then()
                .statusCode(404);
    }
}
