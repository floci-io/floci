package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Wire-level integration tests for the S3 Object Annotations subresource (?annotation):
 * PutObjectAnnotation, GetObjectAnnotation, ListObjectAnnotations, DeleteObjectAnnotation.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3AnnotationsIntegrationTest {

    private static final String BUCKET = "annotations-it-bucket";

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
    @Order(2)
    void createTargetObject() {
        given()
            .contentType("text/plain")
            .body("annotation target body")
        .when()
            .put("/" + BUCKET + "/docs/report.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());
    }

    @Test
    @Order(3)
    void putObjectAnnotationReturnsOutputXmlAndETag() {
        given()
            .body("{\"classification\": \"report\"}")
        .when()
            .put("/" + BUCKET + "/docs/report.txt?annotation&annotationName=labels")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .header("x-amz-checksum-crc64nvme", notNullValue())
            .header("x-amz-checksum-type", equalTo("FULL_OBJECT"))
            .body(containsString("<PutObjectAnnotationOutput"))
            .body(containsString("<Key>docs/report.txt</Key>"))
            .body(containsString("<AnnotationName>labels</AnnotationName>"));
    }

    @Test
    @Order(4)
    void getObjectAnnotationReturnsPayload() {
        given()
        .when()
            .get("/" + BUCKET + "/docs/report.txt?annotation&annotationName=labels")
        .then()
            .statusCode(200)
            .body(equalTo("{\"classification\": \"report\"}"))
            .header("ETag", notNullValue())
            .header("Last-Modified", notNullValue());
    }

    @Test
    @Order(5)
    void getObjectAnnotationWithChecksumModeReturnsChecksumHeaders() {
        given()
            .header("x-amz-checksum-mode", "ENABLED")
        .when()
            .get("/" + BUCKET + "/docs/report.txt?annotation&annotationName=labels")
        .then()
            .statusCode(200)
            .header("x-amz-checksum-crc64nvme", notNullValue())
            .header("x-amz-checksum-type", equalTo("FULL_OBJECT"));
    }

    @Test
    @Order(6)
    void getObjectAnnotationWithoutChecksumModeOmitsChecksumHeaders() {
        given()
        .when()
            .get("/" + BUCKET + "/docs/report.txt?annotation&annotationName=labels")
        .then()
            .statusCode(200)
            .header("x-amz-checksum-crc64nvme", nullValue());
    }

    @Test
    @Order(7)
    void listObjectAnnotationsReturnsEntries() {
        given()
            .body("second")
        .when()
            .put("/" + BUCKET + "/docs/report.txt?annotation&annotationName=summary")
        .then()
            .statusCode(200);
        given()
        .when()
            .get("/" + BUCKET + "/docs/report.txt?annotation")
        .then()
            .statusCode(200)
            .body(containsString("<ListObjectAnnotationsOutput"))
            .body(containsString("<Bucket>" + BUCKET + "</Bucket>"))
            .body(containsString("<Key>docs/report.txt</Key>"))
            .body(containsString("<AnnotationEntry>"))
            .body(containsString("<AnnotationName>labels</AnnotationName>"))
            .body(containsString("<AnnotationName>summary</AnnotationName>"))
            .body(containsString("<Size>6</Size>"))
            .body(containsString("<AnnotationCount>2</AnnotationCount>"))
            .body(containsString("<MaxAnnotationResults>1000</MaxAnnotationResults>"))
            .body(containsString("<IsTruncated>false</IsTruncated>"))
            .body(not(containsString("<NextContinuationToken>")));
    }

    @Test
    @Order(7)
    void headObjectAnnotationMatchesGetStatus() {
        given()
        .when()
            .head("/" + BUCKET + "/docs/report.txt?annotation&annotationName=labels")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());
        given()
        .when()
            .head("/" + BUCKET + "/docs/report.txt?annotation&annotationName=missing")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(8)
    void getMissingAnnotationReturnsNoSuchAnnotation() {
        given()
        .when()
            .get("/" + BUCKET + "/docs/report.txt?annotation&annotationName=missing")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchAnnotation</Code>"));
    }

    @Test
    @Order(9)
    void getAnnotationOnMissingObjectReturnsNoSuchKey() {
        given()
        .when()
            .get("/" + BUCKET + "/no-such-key.txt?annotation&annotationName=labels")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchKey</Code>"));
    }

    @Test
    @Order(10)
    void putAnnotationWithInvalidNameIsRejected() {
        given()
            .body("x")
        .when()
            .put("/" + BUCKET + "/docs/report.txt?annotation&annotationName=s3-reserved")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidAnnotationName</Code>"));
    }

    @Test
    @Order(11)
    void putAnnotationWithOversizedPayloadIsRejected() {
        byte[] tooBig = new byte[1_048_577];
        given()
            .body(tooBig)
        .when()
            .put("/" + BUCKET + "/docs/report.txt?annotation&annotationName=big")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidRequest</Code>"));
    }

    @Test
    @Order(12)
    void deleteObjectAnnotationReturns204() {
        given()
        .when()
            .delete("/" + BUCKET + "/docs/report.txt?annotation&annotationName=summary")
        .then()
            .statusCode(204);
        // Deleting a nonexistent annotation is idempotent.
        given()
        .when()
            .delete("/" + BUCKET + "/docs/report.txt?annotation&annotationName=summary")
        .then()
            .statusCode(204);
        given()
        .when()
            .get("/" + BUCKET + "/docs/report.txt?annotation&annotationName=summary")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(13)
    void emptyAnnotationNameDispatchesToList() {
        given()
        .when()
            .get("/" + BUCKET + "/docs/report.txt?annotation&annotationName=")
        .then()
            .statusCode(200)
            .body(containsString("<ListObjectAnnotationsOutput"));
    }

    // ========== Versioned bucket behavior ==========

    @Test
    @Order(20)
    void enableVersioning() {
        given()
            .body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
        .when()
            .put("/" + BUCKET + "?versioning")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(21)
    void putVersionedObjects() {
        given()
            .contentType("text/plain")
            .body("version one")
        .when()
            .put("/" + BUCKET + "/docs/versioned.txt")
        .then()
            .statusCode(200)
            .header("x-amz-version-id", notNullValue());
        given()
            .contentType("text/plain")
            .body("version two")
        .when()
            .put("/" + BUCKET + "/docs/versioned.txt")
        .then()
            .statusCode(200)
            .header("x-amz-version-id", notNullValue());
    }

    @Test
    @Order(22)
    void annotationOnLatestVersionHasNoInheritanceFromPrevious() {
        given()
            .body("latest annotation")
        .when()
            .put("/" + BUCKET + "/docs/versioned.txt?annotation&annotationName=state")
        .then()
            .statusCode(200)
            .header("x-amz-object-version-id", notNullValue());
    }

    @Test
    @Order(23)
    void listOnLatestReturnsSingleAnnotation() {
        given()
        .when()
            .get("/" + BUCKET + "/docs/versioned.txt?annotation")
        .then()
            .statusCode(200)
            .body(containsString("<AnnotationName>state</AnnotationName>"))
            .body(containsString("<AnnotationCount>1</AnnotationCount>"))
            .header("x-amz-object-version-id", notNullValue());
    }

    @Test
    @Order(24)
    void deleteMarkerHidesAnnotationsOnLatest() {
        given()
        .when()
            .delete("/" + BUCKET + "/docs/versioned.txt")
        .then()
            .statusCode(204)
            .header("x-amz-delete-marker", equalTo("true"));
        given()
        .when()
            .get("/" + BUCKET + "/docs/versioned.txt?annotation")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchKey</Code>"));
    }

    @Test
    @Order(25)
    void putAnnotationWithChecksumAlgorithmIsStoredPerAnnotation() {
        // Recreate the object after the delete marker.
        String versionId = given()
            .contentType("text/plain")
            .body("version three")
        .when()
            .put("/" + BUCKET + "/docs/versioned.txt")
        .then()
            .statusCode(200)
            .header("x-amz-version-id", notNullValue())
            .extract()
            .header("x-amz-version-id");
        given()
            .header("x-amz-sdk-checksum-algorithm", "SHA256")
            .body("checksummed annotation")
        .when()
            .put("/" + BUCKET + "/docs/versioned.txt?annotation&annotationName=hashed")
        .then()
            .statusCode(200)
            .header("x-amz-checksum-sha256", notNullValue())
            .header("x-amz-checksum-type", equalTo("FULL_OBJECT"))
            .header("x-amz-object-version-id", equalTo(versionId));
        given()
            .header("x-amz-checksum-mode", "ENABLED")
        .when()
            .get("/" + BUCKET + "/docs/versioned.txt?annotation&annotationName=hashed")
        .then()
            .statusCode(200)
            .body(equalTo("checksummed annotation"))
            .header("x-amz-checksum-sha256", notNullValue());
        // The stored annotation reports the SHA256 algorithm in the list output.
        given()
        .when()
            .get("/" + BUCKET + "/docs/versioned.txt?annotation&annotation-prefix=hash")
        .then()
            .statusCode(200)
            .body(containsString("<ChecksumAlgorithm>SHA256</ChecksumAlgorithm>"));
    }

    @Test
    @Order(26)
    void unicodePayloadRoundTrips() {
        given()
            .queryParam("annotation")
            .queryParam("annotationName", "résumé")
            .body("données annotées".getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/" + BUCKET + "/docs/report.txt")
        .then()
            .statusCode(200);
        given()
            .queryParam("annotation")
            .queryParam("annotationName", "résumé")
        .when()
            .get("/" + BUCKET + "/docs/report.txt")
        .then()
            .statusCode(200)
            .body(equalTo("données annotées"));
    }
}