package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Arrays;

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

    /**
     * Subdomain wildcard: http://*.example.com — covers http://foo.example.com,
     * http://app.example.com, etc., but NOT https://foo.example.com.
     */
    private static final String SUBDOMAIN_WILDCARD_CORS_XML =
            "<CORSConfiguration>" +
            "  <CORSRule>" +
            "    <AllowedOrigin>http://*.example.com</AllowedOrigin>" +
            "    <AllowedMethod>GET</AllowedMethod>" +
            "    <AllowedMethod>PUT</AllowedMethod>" +
            "    <AllowedHeader>*</AllowedHeader>" +
            "    <ExposeHeader>ETag</ExposeHeader>" +
            "    <MaxAgeSeconds>120</MaxAgeSeconds>" +
            "  </CORSRule>" +
            "</CORSConfiguration>";

    /**
     * Mid-string wildcard: http://app-*.example.com — covers http://app-v1.example.com,
     * http://app-staging.example.com, etc.
     */
    private static final String MID_WILDCARD_CORS_XML =
            "<CORSConfiguration>" +
            "  <CORSRule>" +
            "    <AllowedOrigin>http://app-*.example.com</AllowedOrigin>" +
            "    <AllowedMethod>GET</AllowedMethod>" +
            "    <AllowedHeader>*</AllowedHeader>" +
            "    <MaxAgeSeconds>60</MaxAgeSeconds>" +
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
            .header("Access-Control-Allow-Origin", equalTo("*"))
            .header("Access-Control-Allow-Credentials", nullValue());
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
            .header("Access-Control-Allow-Origin", equalTo("*"))
            .header("Access-Control-Allow-Credentials", nullValue());
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
            .header("Access-Control-Allow-Credentials", equalTo("true"))
            .header("Access-Control-Max-Age", equalTo("600"));
    }

    @Test
    @Order(38)
    void actualGetRequestSpecificOriginReceivesAllowCredentials() {
        given()
            .header("Origin", "https://example.com")
        .when()
            .get("/" + BUCKET + "/hello.txt")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("https://example.com"))
            .header("Access-Control-Allow-Credentials", equalTo("true"));
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

    // ── Glob / wildcard origin matching ───────────────────────────────────────

    @Test
    @Order(60)
    void putCorsConfigWithSubdomainWildcard() {
        given()
            .contentType("application/xml")
            .body(SUBDOMAIN_WILDCARD_CORS_XML)
        .when()
            .put("/" + BUCKET + "?cors")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(61)
    void preflightSubdomainWildcardMatchesSubdomain() {
        given()
            .header("Origin", "http://foo.example.com")
            .header("Access-Control-Request-Method", "GET")
        .when()
            .options("/" + BUCKET + "/k")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("http://foo.example.com"))
            .header("Access-Control-Allow-Credentials", equalTo("true"));
    }

    @Test
    @Order(62)
    void preflightSubdomainWildcardMatchesDeeperSubdomain() {
        // * matches any string, including one with dots, so bar.baz.example.com is valid
        given()
            .header("Origin", "http://bar.baz.example.com")
            .header("Access-Control-Request-Method", "PUT")
        .when()
            .options("/" + BUCKET + "/k")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("http://bar.baz.example.com"));
    }

    @Test
    @Order(63)
    void preflightSubdomainWildcardRejectsDifferentScheme() {
        // Pattern is http://* so https:// must not match
        given()
            .header("Origin", "https://foo.example.com")
            .header("Access-Control-Request-Method", "GET")
        .when()
            .options("/" + BUCKET + "/k")
        .then()
            .statusCode(403);
    }

    @Test
    @Order(64)
    void preflightSubdomainWildcardRejectsDifferentDomain() {
        given()
            .header("Origin", "http://foo.other.com")
            .header("Access-Control-Request-Method", "GET")
        .when()
            .options("/" + BUCKET + "/k")
        .then()
            .statusCode(403);
    }

    @Test
    @Order(65)
    void putCorsConfigWithMidStringWildcard() {
        given()
            .contentType("application/xml")
            .body(MID_WILDCARD_CORS_XML)
        .when()
            .put("/" + BUCKET + "?cors")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(66)
    void preflightMidStringWildcardMatchesVariant() {
        given()
            .header("Origin", "http://app-v1.example.com")
            .header("Access-Control-Request-Method", "GET")
        .when()
            .options("/" + BUCKET + "/k")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("http://app-v1.example.com"));
    }

    @Test
    @Order(67)
    void preflightMidStringWildcardMatchesAnotherVariant() {
        given()
            .header("Origin", "http://app-staging.example.com")
            .header("Access-Control-Request-Method", "GET")
        .when()
            .options("/" + BUCKET + "/k")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("http://app-staging.example.com"));
    }

    @Test
    @Order(68)
    void preflightMidStringWildcardRejectsNonMatchingPrefix() {
        // "web-v1.example.com" does not start with "app-"
        given()
            .header("Origin", "http://web-v1.example.com")
            .header("Access-Control-Request-Method", "GET")
        .when()
            .options("/" + BUCKET + "/k")
        .then()
            .statusCode(403);
    }

    @Test
    @Order(69)
    void wildcardMatchAllowsZeroCharactersForStar() {
        // http://app-.example.com — the * matches the empty string
        given()
            .header("Origin", "http://app-.example.com")
            .header("Access-Control-Request-Method", "GET")
        .when()
            .options("/" + BUCKET + "/k")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("http://app-.example.com"));
    }

    // ── Vary header is not duplicated on repeated actual requests ─────────────

    @Test
    @Order(70)
    void putCorsConfigWildcardForVaryTest() {
        given()
            .contentType("application/xml")
            .body(WILDCARD_CORS_XML)
        .when()
            .put("/" + BUCKET + "?cors")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(71)
    void varyOriginAppearsExactlyOnceOnActualRequest() {
        // Collect the raw Vary header values and count "Origin" occurrences
        String vary = given()
            .header("Origin", "http://localhost:3000")
        .when()
            .get("/" + BUCKET + "/hello.txt")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("*"))
            .extract().header("Vary");

        // Vary may be a single comma-separated string or multiple header lines;
        // either way "Origin" should appear exactly once.
        long count = Arrays.stream(vary.split(","))
                .map(String::trim)
                .filter("Origin"::equalsIgnoreCase)
                .count();
        Assertions.assertEquals(1, count,
                "Vary: Origin should appear exactly once, got: " + vary);
    }

    @Test
    @Order(72)
    void accessControlAllowOriginAppearsExactlyOnce() {
        Response resp = given()
            .header("Origin", "http://localhost:3000")
        .when()
            .get("/" + BUCKET + "/hello.txt")
        .then()
            .statusCode(200)
            .extract().response();

        // getHeaders() returns all values including duplicates
        long count = resp.headers().getList("Access-Control-Allow-Origin").stream().count();
        Assertions.assertEquals(1, count,
                "Access-Control-Allow-Origin must appear exactly once");
    }

    // ── Regression: filter must not apply to non-S3 routes ──────────────────────

    @Test
    @Order(80)
    void putCorsConfigOnExecuteApiBucket() {
        String executeApiCorsXml = "<CORSConfiguration>" +
                "  <CORSRule>" +
                "    <AllowedOrigin>https://example.com</AllowedOrigin>" +
                "    <AllowedMethod>GET</AllowedMethod>" +
                "    <AllowedHeader>*</AllowedHeader>" +
                "    <MaxAgeSeconds>600</MaxAgeSeconds>" +
                "  </CORSRule>" +
                "</CORSConfiguration>";
        given()
        .when()
            .put("/execute-api")
        .then()
            .statusCode(200);

        given()
            .contentType("application/xml")
            .body(executeApiCorsXml)
        .when()
            .put("/execute-api?cors")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(81)
    void nonS3RouteWithMatchingBucketNameDoesNotReceiveCorsCredentials() {
        // Request to API Gateway path format /execute-api/{apiId}/{stage}/...
        // should not get S3 CORS headers even if a bucket named "execute-api" has matching CORS
        given()
            .header("Origin", "https://example.com")
        .when()
            .get("/execute-api/abc123/prod/some-path")
        .then()
            .statusCode(404) // API Gateway returns 404 for unknown paths
            .header("Access-Control-Allow-Origin", nullValue())
            .header("Access-Control-Allow-Credentials", nullValue());
    }

    @Test
    @Order(82)
    void nonS3RouteWithMatchingBucketNameDoesNotReceiveCorsCredentialsOnOptions() {
        // OPTIONS to API Gateway path should not get S3 CORS preflight headers
        given()
            .header("Origin", "https://example.com")
            .header("Access-Control-Request-Method", "GET")
        .when()
            .options("/execute-api/abc123/prod/some-path")
        .then()
            .statusCode(404)
            .header("Access-Control-Allow-Origin", nullValue())
            .header("Access-Control-Allow-Credentials", nullValue());
    }

    // ── S3 bucket named execute-api still works ─────────────────────────────────

    @Test
    @Order(83)
    void s3BucketNamedExecuteApiGetsCorsCredentialsOnActualRequest() {
        // S3 request to bucket "execute-api" (not API Gateway path) should get credentials
        given()
            .header("Origin", "https://example.com")
        .when()
            .get("/execute-api/some-key")
        .then()
            .statusCode(404) // object doesn't exist, but CORS headers should be present
            .header("Access-Control-Allow-Origin", equalTo("https://example.com"))
            .header("Access-Control-Allow-Credentials", equalTo("true"));
    }

    @Test
    @Order(84)
    void s3BucketNamedExecuteApiWildcardOriginGetsNoCredentials() {
        // Wildcard origin on S3 bucket "execute-api" should not get credentials
        String wildcardCorsXml = "<CORSConfiguration>" +
                "  <CORSRule>" +
                "    <AllowedOrigin>*</AllowedOrigin>" +
                "    <AllowedMethod>GET</AllowedMethod>" +
                "    <AllowedHeader>*</AllowedHeader>" +
                "    <MaxAgeSeconds>600</MaxAgeSeconds>" +
                "  </CORSRule>" +
                "</CORSConfiguration>";
        given()
            .contentType("application/xml")
            .body(wildcardCorsXml)
        .when()
            .put("/execute-api?cors")
        .then()
            .statusCode(200);

        given()
            .header("Origin", "https://anything.com")
        .when()
            .get("/execute-api/some-key")
        .then()
            .statusCode(404)
            .header("Access-Control-Allow-Origin", equalTo("*"))
            .header("Access-Control-Allow-Credentials", nullValue());
    }

    // ── AllowedHeader wildcards ──────────────────────────────────────────────

    @Test
    @Order(85)
    void allowedHeaderWithWildcardMatchesHeadersByPrefix() {
        String bucket = "cors-header-wildcard-bucket";
        String corsXml = "<CORSConfiguration>" +
                "  <CORSRule>" +
                "    <AllowedOrigin>https://example.com</AllowedOrigin>" +
                "    <AllowedMethod>PUT</AllowedMethod>" +
                "    <AllowedHeader>x-amz-*</AllowedHeader>" +
                "    <AllowedHeader>Content-*</AllowedHeader>" +
                "  </CORSRule>" +
                "</CORSConfiguration>";
        given().when().put("/" + bucket).then().statusCode(200);
        given().contentType("application/xml").body(corsXml)
        .when()
            .put("/" + bucket + "?cors")
        .then()
            .statusCode(200);

        // Browsers send header names in lower case; each one is allowed by name.
        given()
            .header("Origin", "https://example.com")
            .header("Access-Control-Request-Method", "PUT")
            .header("Access-Control-Request-Headers", "content-type,x-amz-meta-owner,x-amz-date")
        .when()
            .options("/" + bucket + "/upload-key")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("https://example.com"))
            .header("Access-Control-Allow-Headers", equalTo("content-type, x-amz-meta-owner, x-amz-date"));

        // A header no pattern covers is still refused.
        given()
            .header("Origin", "https://example.com")
            .header("Access-Control-Request-Method", "PUT")
            .header("Access-Control-Request-Headers", "content-type,authorization")
        .when()
            .options("/" + bucket + "/upload-key")
        .then()
            .statusCode(403);

        // A preflight that asks for no headers gets no Access-Control-Allow-Headers.
        given()
            .header("Origin", "https://example.com")
            .header("Access-Control-Request-Method", "PUT")
        .when()
            .options("/" + bucket + "/upload-key")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Headers", nullValue());
    }
}
