package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for S3 CORS enforcement.
 *
 * <p>Covers:
 * <ul>
 *   <li>OPTIONS preflight with no CORS config → 403</li>
 *   <li>Wildcard-origin CORS config: correct preflight headers returned</li>
 *   <li>Actual request with {@code Origin} header receives {@code Access-Control-*} response headers</li>
 *   <li>Specific-origin CORS config: matching and non-matching origin / method / requested-headers</li>
 *   <li>After {@code DeleteBucketCors}, preflights return 403 again</li>
 *   <li>OPTIONS without {@code Origin} header is not a preflight (plain 200, no CORS headers)</li>
 * </ul>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3CorsIntegrationTest {

    private static final String BUCKET = "cors-test-bucket";

    /** AllowedOrigin=*, all common methods, wildcard headers, ExposeHeader=ETag, MaxAgeSeconds=3000 */
    private static final String WILDCARD_CORS_XML =
            "<CORSConfiguration>" +
            "  <CORSRule>" +
            "    <AllowedOrigin>*</AllowedOrigin>" +
            "    <AllowedMethod>GET</AllowedMethod>" +
            "    <AllowedMethod>PUT</AllowedMethod>" +
            "    <AllowedMethod>POST</AllowedMethod>" +
            "    <AllowedMethod>DELETE</AllowedMethod>" +
            "    <AllowedMethod>HEAD</AllowedMethod>" +
            "    <AllowedHeader>*</AllowedHeader>" +
            "    <ExposeHeader>ETag</ExposeHeader>" +
            "    <MaxAgeSeconds>3000</MaxAgeSeconds>" +
            "  </CORSRule>" +
            "</CORSConfiguration>";

    /** AllowedOrigin=https://example.com, GET+PUT only, specific allowed headers, two ExposeHeaders */
    private static final String SPECIFIC_ORIGIN_CORS_XML =
            "<CORSConfiguration>" +
            "  <CORSRule>" +
            "    <AllowedOrigin>https://example.com</AllowedOrigin>" +
            "    <AllowedMethod>GET</AllowedMethod>" +
            "    <AllowedMethod>PUT</AllowedMethod>" +
            "    <AllowedHeader>Content-Type</AllowedHeader>" +
            "    <AllowedHeader>Authorization</AllowedHeader>" +
            "    <ExposeHeader>ETag</ExposeHeader>" +
            "    <ExposeHeader>x-amz-request-id</ExposeHeader>" +
            "    <MaxAgeSeconds>600</MaxAgeSeconds>" +
            "  </CORSRule>" +
            "</CORSConfiguration>";

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void createBucket() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(99)
    void cleanupDeleteBucket() {
        // Remove objects created during the test first
        given().delete("/" + BUCKET + "/hello.txt");

        given()
        .when()
            .delete("/" + BUCKET)
        .then()
            .statusCode(204);
    }

    // ── No CORS config present ────────────────────────────────────────────────

    @Test
    @Order(2)
    void optionsPreflightWithoutCorsConfigOnObjectPathReturnsForbidden() {
        given()
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "GET")
        .when()
            .options("/" + BUCKET + "/any-key")
        .then()
            .statusCode(403)
            .body(containsString("CORSResponse"));
    }

    @Test
    @Order(3)
    void optionsPreflightWithoutCorsConfigOnBucketPathReturnsForbidden() {
        given()
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "PUT")
        .when()
            .options("/" + BUCKET)
        .then()
            .statusCode(403);
    }

    // ── Wildcard-origin CORS config ───────────────────────────────────────────

    @Test
    @Order(10)
    void putCorsConfigWithWildcardOrigin() {
        given()
            .contentType("application/xml")
            .body(WILDCARD_CORS_XML)
        .when()
            .put("/" + BUCKET + "?cors")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(11)
    void optionsPreflightWildcardOriginReturnsOkWithAllowOriginStar() {
        given()
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "PUT")
        .when()
            .options("/" + BUCKET + "/some-key")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("*"));
    }

    @Test
    @Order(12)
    void optionsPreflightReturnsAllowedMethods() {
        given()
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "GET")
        .when()
            .options("/" + BUCKET + "/some-key")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Methods", containsString("GET"))
            .header("Access-Control-Allow-Methods", containsString("PUT"))
            .header("Access-Control-Allow-Methods", containsString("DELETE"));
    }

    @Test
    @Order(13)
    void optionsPreflightReturnsMaxAge() {
        given()
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "GET")
        .when()
            .options("/" + BUCKET + "/some-key")
        .then()
            .statusCode(200)
            .header("Access-Control-Max-Age", equalTo("3000"));
    }

    @Test
    @Order(14)
    void optionsPreflightWildcardAllowedHeadersReturnsStarAllowHeaders() {
        given()
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "PUT")
            .header("Access-Control-Request-Headers", "Content-Type, Authorization, x-amz-meta-owner")
        .when()
            .options("/" + BUCKET + "/upload-key")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("*"))
            .header("Access-Control-Allow-Headers", equalTo("*"));
    }

    @Test
    @Order(15)
    void optionsPreflightAlsoWorksOnBucketPath() {
        given()
            .header("Origin", "https://app.example.com")
            .header("Access-Control-Request-Method", "GET")
        .when()
            .options("/" + BUCKET)
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("*"));
    }

    @Test
    @Order(16)
    void optionsPreflightReturnsExposeHeaders() {
        given()
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "GET")
        .when()
            .options("/" + BUCKET + "/some-key")
        .then()
            .statusCode(200)
            .header("Access-Control-Expose-Headers", containsString("ETag"));
    }

    // ── Actual requests receive CORS response headers ─────────────────────────

    @Test
    @Order(20)
    void putObjectForCorsActualRequestTests() {
        given()
            .contentType("text/plain")
            .body("hello cors")
        .when()
            .put("/" + BUCKET + "/hello.txt")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(21)
    void actualGetRequestReceivesAllowOriginHeader() {
        given()
            .header("Origin", "http://localhost:3000")
        .when()
            .get("/" + BUCKET + "/hello.txt")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("*"));
    }

    @Test
    @Order(22)
    void actualGetRequestReceivesVaryOriginHeader() {
        given()
            .header("Origin", "http://localhost:3000")
        .when()
            .get("/" + BUCKET + "/hello.txt")
        .then()
            .statusCode(200)
            .header("Vary", containsString("Origin"));
    }

    @Test
    @Order(23)
    void actualGetRequestReceivesExposeHeadersHeader() {
        given()
            .header("Origin", "http://localhost:3000")
        .when()
            .get("/" + BUCKET + "/hello.txt")
        .then()
            .statusCode(200)
            .header("Access-Control-Expose-Headers", containsString("ETag"));
    }

    @Test
    @Order(24)
    void requestWithoutOriginHeaderGetsNoCorsHeaders() {
        given()
        .when()
            .get("/" + BUCKET + "/hello.txt")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", nullValue());
    }

    // ── Specific-origin CORS config ───────────────────────────────────────────

    @Test
    @Order(30)
    void replaceCorsConfigWithSpecificOrigin() {
        given()
            .contentType("application/xml")
            .body(SPECIFIC_ORIGIN_CORS_XML)
        .when()
            .put("/" + BUCKET + "?cors")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(31)
    void optionsPreflightMatchingSpecificOriginReturnsOk() {
        given()
            .header("Origin", "https://example.com")
            .header("Access-Control-Request-Method", "GET")
            .header("Access-Control-Request-Headers", "Content-Type")
        .when()
            .options("/" + BUCKET + "/some-key")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("https://example.com"))
            .header("Access-Control-Max-Age", equalTo("600"));
    }

    @Test
    @Order(32)
    void optionsPreflightSpecificOriginReturnsAllExposeHeaders() {
        given()
            .header("Origin", "https://example.com")
            .header("Access-Control-Request-Method", "PUT")
        .when()
            .options("/" + BUCKET + "/some-key")
        .then()
            .statusCode(200)
            .header("Access-Control-Expose-Headers", containsString("ETag"))
            .header("Access-Control-Expose-Headers", containsString("x-amz-request-id"));
    }

    @Test
    @Order(33)
    void optionsPreflightNonMatchingOriginReturnsForbidden() {
        given()
            .header("Origin", "https://attacker.evil.com")
            .header("Access-Control-Request-Method", "GET")
        .when()
            .options("/" + BUCKET + "/some-key")
        .then()
            .statusCode(403);
    }

    @Test
    @Order(34)
    void optionsPreflightNonMatchingMethodReturnsForbidden() {
        // DELETE is not listed in the specific-origin rule
        given()
            .header("Origin", "https://example.com")
            .header("Access-Control-Request-Method", "DELETE")
        .when()
            .options("/" + BUCKET + "/some-key")
        .then()
            .statusCode(403);
    }

    @Test
    @Order(35)
    void optionsPreflightNonMatchingRequestHeaderReturnsForbidden() {
        // X-Custom-Header is not in AllowedHeaders and the rule has no wildcard
        given()
            .header("Origin", "https://example.com")
            .header("Access-Control-Request-Method", "GET")
            .header("Access-Control-Request-Headers", "X-Custom-Header")
        .when()
            .options("/" + BUCKET + "/some-key")
        .then()
            .statusCode(403);
    }

    @Test
    @Order(36)
    void actualGetRequestMatchingSpecificOriginGetsCorsHeaders() {
        given()
            .header("Origin", "https://example.com")
        .when()
            .get("/" + BUCKET + "/hello.txt")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("https://example.com"))
            .header("Vary", containsString("Origin"));
    }

    @Test
    @Order(37)
    void actualGetRequestNonMatchingOriginGetsNoCorsHeaders() {
        given()
            .header("Origin", "https://not-allowed.com")
        .when()
            .get("/" + BUCKET + "/hello.txt")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", nullValue());
    }

    // ── Delete CORS config ────────────────────────────────────────────────────

    @Test
    @Order(40)
    void deleteCorsConfig() {
        given()
        .when()
            .delete("/" + BUCKET + "?cors")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(41)
    void optionsPreflightAfterDeleteCorsReturnsForbidden() {
        given()
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "GET")
        .when()
            .options("/" + BUCKET + "/some-key")
        .then()
            .statusCode(403);
    }

    @Test
    @Order(42)
    void actualGetAfterDeleteCorsGetsNoCorsHeaders() {
        given()
            .header("Origin", "http://localhost:3000")
        .when()
            .get("/" + BUCKET + "/hello.txt")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", nullValue());
    }

    // ── OPTIONS without Origin is not a preflight ─────────────────────────────

    @Test
    @Order(50)
    void optionsWithoutOriginHeaderReturnsOkWithNoCorsHeaders() {
        given()
        .when()
            .options("/" + BUCKET + "/some-key")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", nullValue());
    }
}