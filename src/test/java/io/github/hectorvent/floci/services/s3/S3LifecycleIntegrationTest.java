package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for S3 bucket lifecycle configuration.
 *
 * <p>Covers {@code PutBucketLifecycleConfiguration},
 * {@code GetBucketLifecycleConfiguration}, and {@code DeleteBucketLifecycle}.
 * Lifecycle XML is stored and echoed verbatim, so assertions focus on body
 * round-tripping and HTTP status codes.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>GET before any PUT returns {@code NoSuchLifecycleConfiguration} (404)</li>
 *   <li>Round-trip for {@code Filter.Prefix=""} (the shape Terraform emits)</li>
 *   <li>Round-trip for the legacy no-{@code Filter} rule shape</li>
 *   <li>Round-trip for {@code Filter.Tag} and {@code Filter.And} combinations</li>
 *   <li>Overwrite: a second PUT replaces, rather than merging with, the prior config</li>
 *   <li>DELETE returns 204; subsequent GET is 404</li>
 *   <li>Virtual-host routing ({@code Host: bucket.localhost} + {@code ?lifecycle})</li>
 *   <li>PUT against a non-existent bucket returns {@code NoSuchBucket} (404)</li>
 * </ul>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3LifecycleIntegrationTest {

    private static final String BUCKET = "lifecycle-test-bucket";

    /** {@code Filter.Prefix=""} - the shape Terraform produces. */
    private static final String FILTER_PREFIX_EMPTY_XML =
            "<LifecycleConfiguration>" +
            "  <Rule>" +
            "    <ID>expire-everything</ID>" +
            "    <Filter><Prefix></Prefix></Filter>" +
            "    <Status>Enabled</Status>" +
            "    <Expiration><Days>365</Days></Expiration>" +
            "  </Rule>" +
            "</LifecycleConfiguration>";

    /** Legacy schema: no {@code Filter} element, only top-level {@code Prefix}. */
    private static final String NO_FILTER_LEGACY_XML =
            "<LifecycleConfiguration>" +
            "  <Rule>" +
            "    <ID>legacy-rule</ID>" +
            "    <Prefix>logs/</Prefix>" +
            "    <Status>Enabled</Status>" +
            "    <Expiration><Days>30</Days></Expiration>" +
            "  </Rule>" +
            "</LifecycleConfiguration>";

    /** Tag-only filter. */
    private static final String FILTER_TAG_XML =
            "<LifecycleConfiguration>" +
            "  <Rule>" +
            "    <ID>tag-rule</ID>" +
            "    <Filter><Tag><Key>env</Key><Value>ephemeral</Value></Tag></Filter>" +
            "    <Status>Enabled</Status>" +
            "    <Expiration><Days>7</Days></Expiration>" +
            "  </Rule>" +
            "</LifecycleConfiguration>";

    /** {@code Filter.And} combining a prefix and multiple tags. */
    private static final String FILTER_AND_XML =
            "<LifecycleConfiguration>" +
            "  <Rule>" +
            "    <ID>and-rule</ID>" +
            "    <Filter>" +
            "      <And>" +
            "        <Prefix>tmp/</Prefix>" +
            "        <Tag><Key>env</Key><Value>dev</Value></Tag>" +
            "        <Tag><Key>team</Key><Value>platform</Value></Tag>" +
            "      </And>" +
            "    </Filter>" +
            "    <Status>Enabled</Status>" +
            "    <Expiration><Days>1</Days></Expiration>" +
            "  </Rule>" +
            "</LifecycleConfiguration>";

    /** Distinct from the others so the overwrite test can distinguish them. */
    private static final String OVERWRITE_B_XML =
            "<LifecycleConfiguration>" +
            "  <Rule>" +
            "    <ID>overwrite-target</ID>" +
            "    <Filter><Prefix>archive/</Prefix></Filter>" +
            "    <Status>Disabled</Status>" +
            "    <Expiration><Days>180</Days></Expiration>" +
            "  </Rule>" +
            "</LifecycleConfiguration>";

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
        // Idempotent: deleteBucketLifecycle does not require an existing config
        given().delete("/" + BUCKET + "?lifecycle");

        given()
        .when()
            .delete("/" + BUCKET)
        .then()
            .statusCode(204);
    }

    // ── No config yet: GET returns 404 ────────────────────────────────────────

    @Test
    @Order(2)
    void getLifecycleBeforePutReturns404() {
        given()
        .when()
            .get("/" + BUCKET + "?lifecycle")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchLifecycleConfiguration"));
    }

    // ── Filter.Prefix="" round-trip (Terraform shape, issue #441) ────────────

    @Test
    @Order(10)
    void putLifecycleWithEmptyFilterPrefix() {
        given()
            .contentType("application/xml")
            .body(FILTER_PREFIX_EMPTY_XML)
        .when()
            .put("/" + BUCKET + "?lifecycle")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(11)
    void getLifecycleReturnsFilterPrefixEmpty() {
        // Byte-for-byte round-trip. Critical for #441: the empty <Prefix></Prefix>
        // must survive unchanged, nested inside <Filter>.
        given()
        .when()
            .get("/" + BUCKET + "?lifecycle")
        .then()
            .statusCode(200)
            .body(equalTo(FILTER_PREFIX_EMPTY_XML));
    }

    // ── Legacy no-Filter shape ───────────────────────────────────────────────

    @Test
    @Order(20)
    void putLifecycleLegacyNoFilter() {
        given()
            .contentType("application/xml")
            .body(NO_FILTER_LEGACY_XML)
        .when()
            .put("/" + BUCKET + "?lifecycle")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(21)
    void getLifecycleReturnsLegacyShape() {
        // Full round-trip: server must not inject a <Filter> wrapper
        // around the legacy top-level <Prefix>, nor drop the prefix value.
        given()
        .when()
            .get("/" + BUCKET + "?lifecycle")
        .then()
            .statusCode(200)
            .body(equalTo(NO_FILTER_LEGACY_XML));
    }

    // ── Filter.Tag ───────────────────────────────────────────────────────────

    @Test
    @Order(30)
    void putLifecycleWithFilterTag() {
        given()
            .contentType("application/xml")
            .body(FILTER_TAG_XML)
        .when()
            .put("/" + BUCKET + "?lifecycle")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(31)
    void getLifecycleReturnsFilterTag() {
        given()
        .when()
            .get("/" + BUCKET + "?lifecycle")
        .then()
            .statusCode(200)
            .body(equalTo(FILTER_TAG_XML));
    }

    // ── Filter.And (prefix + multiple tags) ──────────────────────────────────

    @Test
    @Order(40)
    void putLifecycleWithFilterAnd() {
        given()
            .contentType("application/xml")
            .body(FILTER_AND_XML)
        .when()
            .put("/" + BUCKET + "?lifecycle")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(41)
    void getLifecycleReturnsFilterAnd() {
        // Full round-trip covers both tags and the prefix inside <And>
        given()
        .when()
            .get("/" + BUCKET + "?lifecycle")
        .then()
            .statusCode(200)
            .body(equalTo(FILTER_AND_XML));
    }

    // ── Overwrite: second PUT replaces, not merges ───────────────────────────

    @Test
    @Order(50)
    void overwritePutReplacesRatherThanMerges() {
        given()
            .contentType("application/xml")
            .body(OVERWRITE_B_XML)
        .when()
            .put("/" + BUCKET + "?lifecycle")
        .then()
            .statusCode(200);

        // equalTo proves the whole previous config was replaced, not merged:
        // any lingering rule from a prior PUT would fail equality.
        given()
        .when()
            .get("/" + BUCKET + "?lifecycle")
        .then()
            .statusCode(200)
            .body(equalTo(OVERWRITE_B_XML));
    }

    // ── Delete + subsequent GET is 404 ───────────────────────────────────────

    @Test
    @Order(60)
    void deleteLifecycleReturns204() {
        given()
        .when()
            .delete("/" + BUCKET + "?lifecycle")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(61)
    void getLifecycleAfterDeleteReturns404() {
        given()
        .when()
            .get("/" + BUCKET + "?lifecycle")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchLifecycleConfiguration"));
    }

    // ── Virtual-host routing (Host: bucket.localhost, path "/?lifecycle") ────

    @Test
    @Order(70)
    void virtualHostPutLifecycle() {
        given()
            .header("Host", BUCKET + ".localhost")
            .contentType("application/xml")
            .body(FILTER_PREFIX_EMPTY_XML)
        .when()
            .put("/?lifecycle")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(71)
    void virtualHostGetLifecycleReturnsConfig() {
        // Verbatim round-trip via virtual-host routing, guards against the
        // virtual-host filter stripping or rewriting the ?lifecycle body.
        given()
            .header("Host", BUCKET + ".localhost")
        .when()
            .get("/?lifecycle")
        .then()
            .statusCode(200)
            .body(equalTo(FILTER_PREFIX_EMPTY_XML));
    }

    @Test
    @Order(72)
    void virtualHostDeleteLifecycle() {
        given()
            .header("Host", BUCKET + ".localhost")
        .when()
            .delete("/?lifecycle")
        .then()
            .statusCode(204);

        given()
            .header("Host", BUCKET + ".localhost")
        .when()
            .get("/?lifecycle")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchLifecycleConfiguration"));
    }

    // ── PUT against a non-existent bucket returns NoSuchBucket ───────────────

    @Test
    @Order(80)
    void putLifecycleAgainstMissingBucketReturns404() {
        given()
            .contentType("application/xml")
            .body(FILTER_PREFIX_EMPTY_XML)
        .when()
            .put("/lifecycle-no-such-bucket-" + System.currentTimeMillis() + "?lifecycle")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }
}
