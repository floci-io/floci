package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PreSignedUrlIntegrationTest {

    private static final String BUCKET = "presign-test-bucket";

    @Inject
    PreSignedUrlGenerator presignGenerator;

    @Test
    @Order(1)
    void createBucketAndUploadObject() {
        given().when().put("/" + BUCKET).then().statusCode(200);
        given()
            .body("presigned content")
            .contentType("text/plain")
        .when()
            .put("/" + BUCKET + "/secret-file.txt")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void accessWithPresignedGetUrl() {
        int port = io.restassured.RestAssured.port;
        String fullBaseUrl = "http://localhost:" + port;

        String presignedUrl = presignGenerator.generatePresignedUrl(
                fullBaseUrl, BUCKET, "secret-file.txt", "GET", 3600);

        // Extract path and query from the URL
        URI uri = URI.create(presignedUrl);

        given()
        .when()
            .get(uri.getRawPath() + "?" + uri.getRawQuery())
        .then()
            .statusCode(200)
            .body(equalTo("presigned content"));
    }

    @Test
    @Order(3)
    void presignedUrlGeneratesValidStructure() {
        String url = presignGenerator.generatePresignedUrl(
                "http://localhost:4566", BUCKET, "file.txt", "GET", 300);

        assertTrue(url.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"));
        assertTrue(url.contains("X-Amz-Credential="));
        assertTrue(url.contains("X-Amz-Date="));
        assertTrue(url.contains("X-Amz-Expires=300"));
        assertTrue(url.contains("X-Amz-SignedHeaders=host"));
        assertTrue(url.contains("X-Amz-Signature="));
    }

    @Test
    @Order(4)
    void expiredPresignedUrlReturns403() {
        // Create a URL with expired date by constructing manually
        int port = io.restassured.RestAssured.port;

        // Use an obviously expired date (year 2020)
        String expiredPath = "/" + BUCKET + "/secret-file.txt"
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=test"
                + "&X-Amz-Date=20200101T000000Z"
                + "&X-Amz-Expires=1"
                + "&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=invalidsig";

        given()
        .when()
            .get(expiredPath)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));
    }

    @Test
    @Order(5)
    void presignedPutUrl() {
        int port = io.restassured.RestAssured.port;
        String fullBaseUrl = "http://localhost:" + port;
        String url = presignGenerator.generatePresignedUrl(
                fullBaseUrl, BUCKET, "uploaded-via-presign.txt", "PUT", 3600);

        URI uri = URI.create(url);

        given()
            .body("uploaded via presigned PUT")
        .when()
            .put(uri.getRawPath() + "?" + uri.getRawQuery())
        .then()
            .statusCode(200);

        // Verify the object was created
        given()
        .when()
            .get("/" + BUCKET + "/uploaded-via-presign.txt")
        .then()
            .statusCode(200)
            .body(equalTo("uploaded via presigned PUT"));
    }

    // --- response-* query parameter overrides on presigned GET/HEAD ---

    @Test
    @Order(10)
    void uploadObjectWithStoredHeadersForOverrideTests() {
        given()
            .body("override-content")
            .contentType("text/plain")
            .header("Content-Disposition", "inline")
            .header("Cache-Control", "max-age=60")
        .when()
            .put("/" + BUCKET + "/disposition-file.txt")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(11)
    void getObjectAppliesResponseContentDispositionOverride() {
        // Stored disposition is "inline"; override should win.
        given()
            .urlEncodingEnabled(false)
        .when()
            .get("/" + BUCKET + "/disposition-file.txt?response-content-disposition=attachment%3B%20filename%3D%22file.txt%22")
        .then()
            .statusCode(200)
            .header("Content-Disposition", equalTo("attachment; filename=\"file.txt\""));
    }

    @Test
    @Order(12)
    void getObjectAppliesAllResponseOverrides() {
        given()
            .urlEncodingEnabled(false)
        .when()
            .get("/" + BUCKET + "/disposition-file.txt"
                + "?response-content-type=application%2Fpdf"
                + "&response-content-language=en-US"
                + "&response-expires=Thu%2C%2001%20Dec%202099%2016%3A00%3A00%20GMT"
                + "&response-cache-control=no-store"
                + "&response-content-disposition=attachment"
                + "&response-content-encoding=identity")
        .then()
            .statusCode(200)
            .header("Content-Type", equalTo("application/pdf"))
            .header("Content-Language", equalTo("en-US"))
            .header("Expires", equalTo("Thu, 01 Dec 2099 16:00:00 GMT"))
            .header("Cache-Control", equalTo("no-store"))
            .header("Content-Disposition", equalTo("attachment"))
            .header("Content-Encoding", equalTo("identity"));
    }

    @Test
    @Order(13)
    void getObjectWithoutOverridesReturnsStoredHeaders() {
        given()
        .when()
            .get("/" + BUCKET + "/disposition-file.txt")
        .then()
            .statusCode(200)
            .header("Content-Disposition", equalTo("inline"))
            .header("Cache-Control", equalTo("max-age=60"));
    }

    @Test
    @Order(14)
    void headObjectAppliesResponseContentDispositionOverride() {
        given()
            .urlEncodingEnabled(false)
        .when()
            .head("/" + BUCKET + "/disposition-file.txt?response-content-disposition=attachment%3B%20filename%3D%22head.txt%22")
        .then()
            .statusCode(200)
            .header("Content-Disposition", equalTo("attachment; filename=\"head.txt\""));
    }

    @Test
    @Order(15)
    void emptyResponseOverrideIsIgnoredAndFallsBackToStored() {
        // `?response-content-disposition=` binds as "" in JAX-RS; real S3 treats it as absent.
        given()
            .urlEncodingEnabled(false)
        .when()
            .get("/" + BUCKET + "/disposition-file.txt?response-content-disposition=")
        .then()
            .statusCode(200)
            .header("Content-Disposition", equalTo("inline"));
    }

    @Test
    @Order(16)
    void rangeRequestAppliesResponseContentDispositionOverride() {
        given()
            .urlEncodingEnabled(false)
            .header("Range", "bytes=0-3")
        .when()
            .get("/" + BUCKET + "/disposition-file.txt?response-content-disposition=attachment%3B%20filename%3D%22range.txt%22")
        .then()
            .statusCode(206)
            .header("Content-Disposition", equalTo("attachment; filename=\"range.txt\""));
    }

    @Test
    @Order(17)
    void rangeRequestWithoutOverrideFallsBackToStoredDisposition() {
        given()
            .header("Range", "bytes=0-3")
        .when()
            .get("/" + BUCKET + "/disposition-file.txt")
        .then()
            .statusCode(206)
            .header("Content-Disposition", equalTo("inline"))
            .header("Cache-Control", equalTo("max-age=60"));
    }

    @Test
    @Order(99)
    void cleanUp() {
        given().when().delete("/" + BUCKET + "/secret-file.txt").then().statusCode(204);
        given().when().delete("/" + BUCKET + "/uploaded-via-presign.txt").then().statusCode(204);
        given().when().delete("/" + BUCKET + "/disposition-file.txt").then().statusCode(204);
        given().when().delete("/" + BUCKET).then().statusCode(204);
    }
}
