package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies that S3 XML responses match the production wire format:
 * {@code Content-Type: application/xml} with no {@code charset} parameter.
 *
 * <p>Quarkus REST would otherwise append {@code ;charset=UTF-8}, but real AWS
 * S3 never does — see the response examples in the AWS S3 API Reference.
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
        // GET on a non-existent bucket returns an XML error body, which must
        // also be charset-free. Using a missing bucket (instead of duplicate
        // PUT) avoids region-dependent behaviour: PUT to an existing bucket
        // returns 200 in us-east-1 and 409 elsewhere.
        given()
        .when()
            .get("/charset-test-missing-bucket-xyz")
        .then()
            .statusCode(404)
            .header("Content-Type", equalTo("application/xml"));
    }
}
