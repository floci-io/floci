package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * Verifies S3 XML responses match the real AWS wire format
 * ({@code Content-Type: application/xml}, no {@code charset} parameter).
 */
@QuarkusTest
class S3ContentTypeCharsetFilterTest {

    @Test
    void listBucketsContentTypeHasNoCharset() {
        given()
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .header("Content-Type", equalTo("application/xml"));
    }

    @Test
    void noSuchBucketErrorContentTypeHasNoCharset() {
        given()
        .when()
            .get("/charset-test-missing-bucket-xyz")
        .then()
            .statusCode(404)
            .header("Content-Type", equalTo("application/xml"));
    }

    @Test
    void jsonResponseContentTypeUntouched() {
        given()
        .when()
            .get("/_floci/health")
        .then()
            .statusCode(200)
            .header("Content-Type", startsWith("application/json"));
    }
}
