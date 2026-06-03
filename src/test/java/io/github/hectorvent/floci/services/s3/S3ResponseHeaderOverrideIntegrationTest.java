package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies response-* header override behaviour on GetObject / HeadObject.
 *
 * Empirically confirmed against real AWS S3:
 *   HTTP 400, Code=InvalidRequest,
 *   Message="Request specific response headers cannot be used for anonymous GET requests."
 *
 * Unsigned requests that supply any response-* query parameter must be rejected.
 * Signed requests (Authorization header present) must be accepted and the override
 * applied to the response.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3ResponseHeaderOverrideIntegrationTest {

    private static final String BUCKET = "response-override-test-bucket";
    private static final String KEY    = "test-object.txt";

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    @Order(1)
    void setup_createBucketAndObject() {
        given().put("/" + BUCKET).then().statusCode(200);
        given()
            .header("Authorization", AUTH_HEADER)
            .contentType("text/plain")
            .body("hello")
            .put("/" + BUCKET + "/" + KEY)
            .then().statusCode(200);
    }

    // ── Anonymous requests must be rejected (400 InvalidRequest) ─────────────

    @Test
    @Order(2)
    void anonymousGetWithResponseCacheControl_returns400() {
        given()
            .queryParam("response-cache-control", "no-cache")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"))
            .body(containsString("anonymous GET requests"));
    }

    @Test
    @Order(3)
    void anonymousGetWithResponseContentType_returns400() {
        given()
            .queryParam("response-content-type", "text/html")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"));
    }

    @Test
    @Order(4)
    void anonymousGetWithResponseContentDisposition_returns400() {
        given()
            .queryParam("response-content-disposition", "attachment; filename=x.txt")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"));
    }

    @Test
    @Order(5)
    void anonymousGetWithResponseContentEncoding_returns400() {
        given()
            .queryParam("response-content-encoding", "gzip")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"));
    }

    @Test
    @Order(6)
    void anonymousGetWithResponseContentLanguage_returns400() {
        given()
            .queryParam("response-content-language", "en-US")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"));
    }

    @Test
    @Order(7)
    void anonymousGetWithResponseExpires_returns400() {
        given()
            .queryParam("response-expires", "Thu, 01 Jan 2099 00:00:00 GMT")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"));
    }

    @Test
    @Order(8)
    void anonymousHeadWithResponseCacheControl_returns400() {
        given()
            .queryParam("response-cache-control", "no-cache")
        .when()
            .head("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(400);
    }

    // ── Signed requests must be accepted and the override applied ────────────

    @Test
    @Order(9)
    void signedGetWithResponseCacheControl_appliesOverride() {
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("response-cache-control", "max-age=3600")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200)
            .header("Cache-Control", equalTo("max-age=3600"));
    }

    @Test
    @Order(10)
    void signedGetWithResponseContentType_appliesOverride() {
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("response-content-type", "application/octet-stream")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200)
            .header("Content-Type", containsString("application/octet-stream"));
    }

    @Test
    @Order(11)
    void signedGetWithResponseContentDisposition_appliesOverride() {
        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("response-content-disposition", "attachment; filename=download.txt")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200)
            .header("Content-Disposition", equalTo("attachment; filename=download.txt"));
    }

    // ── Anonymous request without response-* params must still work ──────────

    @Test
    @Order(12)
    void anonymousGetWithoutOverrides_returns200() {
        given()
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200);
    }

    // ── Empty response-* values are ignored (not treated as present) ─────────

    @Test
    @Order(13)
    void anonymousGetWithEmptyResponseCacheControl_returns200() {
        // Real S3 ignores ?response-cache-control= (empty value) — should not reject.
        given()
            .queryParam("response-cache-control", "")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200);
    }
}
