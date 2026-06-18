package io.github.hectorvent.floci.services.floci.ui;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Verifies the browser landing page content-negotiation on {@code /} and the
 * {@code /_floci/ui/status} contract. These do not spawn the sidecar (only
 * {@code GET /_floci/ui} does), so they need no Docker.
 */
@QuarkusTest
class UiLandingIntegrationTest {

    @Test
    void browserAcceptHtmlGetsLandingPage() {
        given()
            .accept("text/html")
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .contentType(containsString("text/html"))
            .body(containsString("Floci"))
            .body(containsString("Open Floci UI"));
    }

    @Test
    void sdkClientStillGetsListBucketsXml() {
        // No Accept: text/html — must fall through to S3 ListBuckets, unchanged.
        given()
            .accept("application/xml")
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .body(containsString("ListAllMyBucketsResult"));
    }

    @Test
    void wildcardAcceptIsTreatedAsSdkNotBrowser() {
        // RestAssured default Accept is */* — the strict text/html check must not match.
        given()
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .body(containsString("ListAllMyBucketsResult"));
    }

    @Test
    void statusEndpointReportsNotReadyBeforeStart() {
        given()
            .accept("application/json")
        .when()
            .get("/_floci/ui/status")
        .then()
            .statusCode(200)
            .body("ready", is(false));
    }
}
