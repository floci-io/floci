package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.iam.IamService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(PreSignedUrlIntegrationTest.PresignValidationProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PreSignedUrlIntegrationTest {

    public static final class PresignValidationProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.auth.validate-signatures", "true");
        }
    }

    private static final String BUCKET = "presign-test-bucket";

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Inject
    PreSignedUrlGenerator presignGenerator;

    @Inject
    IamService iamService;

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
            .urlEncodingEnabled(false)
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
        assertTrue(url.contains("X-Amz-Security-Token="));
        assertTrue(url.contains("X-Amz-SignedHeaders=host"));
        assertTrue(url.contains("X-Amz-Signature="));

        // Verify X-Amz-Credential uses request-scoped account ID, not the hardcoded placeholder
        assertFalse(url.contains("AKIAIOSFODNN7EXAMPLE"),
                "X-Amz-Credential must not contain hardcoded AKIAIOSFODNN7EXAMPLE");

        URI uri = URI.create(url);
        String credential = queryParam(uri, "X-Amz-Credential");
        String sessionToken = queryParam(uri, "X-Amz-Security-Token");
        String accessKeyId = credential.substring(0, credential.indexOf('/'));

        assertTrue(accessKeyId.matches("ASIA[A-Z0-9]{16}"),
                "X-Amz-Credential should use a temporary access key, got: " + credential);
        assertNotNull(sessionToken);
        assertTrue(iamService.findSecretKey(accessKeyId, sessionToken).isPresent(),
                "The temporary credential must be registered for SigV4 validation");

        URI secondUri = URI.create(presignGenerator.generatePresignedUrl(
                "http://localhost:8080", BUCKET, "second-file.txt", "GET", 300));
        String secondCredential = queryParam(secondUri, "X-Amz-Credential");
        assertEquals(accessKeyId, secondCredential.substring(0, secondCredential.indexOf('/')),
                "Generated URLs should reuse the account and region credential while it is valid");
        assertEquals(sessionToken, queryParam(secondUri, "X-Amz-Security-Token"));
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
            .urlEncodingEnabled(false)
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

    @Test
    @Order(6)
    void tamperedPresignedUrlReturnsSignatureMismatch() {
        int port = io.restassured.RestAssured.port;
        String url = presignGenerator.generatePresignedUrl(
                "http://localhost:" + port, BUCKET, "secret-file.txt", "GET", 3600);
        URI uri = URI.create(url);
        String tamperedQuery = uri.getRawQuery().replaceFirst(
                "X-Amz-Signature=[0-9a-f]+", "X-Amz-Signature=" + "0".repeat(64));

        given()
            .urlEncodingEnabled(false)
        .when()
            .get(uri.getRawPath() + "?" + tamperedQuery)
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("SignatureDoesNotMatch"));
    }

    private static String queryParam(URI uri, String name) {
        for (String pair : uri.getRawQuery().split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && name.equals(pair.substring(0, equals))) {
                return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
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
        // Must be a signed request per AWS spec (response-* params require Authorization or presigned URL).
        given()
            .urlEncodingEnabled(false)
            .header("Authorization", AUTH_HEADER)
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
            .header("Authorization", AUTH_HEADER)
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
            .header("Authorization", AUTH_HEADER)
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
            .header("Authorization", AUTH_HEADER)
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
