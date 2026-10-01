package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class S3ContentMd5IntegrationTest {

    private static final String INVALID_DIGEST_MESSAGE = "The Content-MD5 you specified is not valid.";
    private static final String BAD_DIGEST_MESSAGE = "The Content-MD5 you specified did not match what we received.";

    @Test
    void putObject_matchingContentMd5_isStored() throws Exception {
        String bucket = createBucket("md5-match");

        given()
            .header("Content-MD5", md5("body"))
            .body("body")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200);

        assertObjectBody(bucket, "object.txt", "body");
    }

    @Test
    void putObject_mismatchedContentMd5_isBadDigestAndNotStored() throws Exception {
        String bucket = createBucket("md5-mismatch");

        given()
            .header("Content-MD5", md5("other"))
            .body("body")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(400)
            .body(containsString("<Code>BadDigest</Code>"))
            .body(containsString("<Message>" + BAD_DIGEST_MESSAGE + "</Message>"));

        assertObjectAbsent(bucket, "object.txt");
    }

    @Test
    void putObject_mismatchedContentMd5_doesNotReplaceExistingObject() throws Exception {
        String bucket = createBucket("md5-overwrite");
        given().body("original").when().put("/" + bucket + "/object.txt").then().statusCode(200);

        given()
            .header("Content-MD5", md5("other"))
            .body("replacement")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(400)
            .body(containsString("<Code>BadDigest</Code>"))
            .body(containsString("<Message>" + BAD_DIGEST_MESSAGE + "</Message>"));

        assertObjectBody(bucket, "object.txt", "original");
    }

    @Test
    void putObject_malformedContentMd5_isInvalidDigest() {
        String bucket = createBucket("md5-malformed");

        // Not base64 at all, then base64 of the wrong length.
        for (String malformed : new String[] {"not-base64!!", Base64.getEncoder().encodeToString(new byte[8])}) {
            given()
                .header("Content-MD5", malformed)
                .body("body")
            .when()
                .put("/" + bucket + "/object.txt")
            .then()
                .statusCode(400)
                .body(containsString("<Code>InvalidDigest</Code>"))
                .body(containsString("<Message>" + INVALID_DIGEST_MESSAGE + "</Message>"));
        }

        assertObjectAbsent(bucket, "object.txt");
    }

    @Test
    void putObject_awsChunkedBody_isCheckedAgainstTheDecodedPayload() throws Exception {
        String bucket = createBucket("md5-chunked");
        String payload = "decoded-payload";
        String chunked = Integer.toHexString(payload.length()) + "\r\n" + payload + "\r\n0\r\n\r\n";

        given()
            .header("Content-Encoding", "aws-chunked")
            .header("x-amz-content-sha256", "STREAMING-UNSIGNED-PAYLOAD-TRAILER")
            .header("x-amz-decoded-content-length", String.valueOf(payload.length()))
            .header("Content-MD5", md5(payload))
            .body(chunked)
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200);

        assertObjectBody(bucket, "object.txt", payload);
    }

    @Test
    void putObject_awsChunkedBody_withTheDigestOfTheFramedBody_isBadDigest() throws Exception {
        String bucket = createBucket("md5-chunked-framed");
        String payload = "decoded-payload";
        String chunked = Integer.toHexString(payload.length()) + "\r\n" + payload + "\r\n0\r\n\r\n";

        // The digest is of the payload S3 stores, not of the chunk framing on the wire.
        given()
            .header("Content-Encoding", "aws-chunked")
            .header("x-amz-content-sha256", "STREAMING-UNSIGNED-PAYLOAD-TRAILER")
            .header("x-amz-decoded-content-length", String.valueOf(payload.length()))
            .header("Content-MD5", md5(chunked))
            .body(chunked)
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(400)
            .body(containsString("<Code>BadDigest</Code>"))
            .body(containsString("<Message>" + BAD_DIGEST_MESSAGE + "</Message>"));

        assertObjectAbsent(bucket, "object.txt");
    }

    @Test
    void uploadPart_mismatchedContentMd5_isBadDigest() throws Exception {
        String bucket = createBucket("md5-part");
        String uploadId = given()
        .when()
            .post("/" + bucket + "/object.txt?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .header("Content-MD5", md5("other"))
            .body("part")
        .when()
            .put("/" + bucket + "/object.txt?partNumber=1&uploadId=" + uploadId)
        .then()
            .statusCode(400)
            .body(containsString("<Code>BadDigest</Code>"))
            .body(containsString("<Message>" + BAD_DIGEST_MESSAGE + "</Message>"));

        // The rejected part was not stored.
        given()
        .when()
            .get("/" + bucket + "/object.txt?uploadId=" + uploadId)
        .then()
            .statusCode(200)
            .body(not(containsString("<Part>")));

        given()
            .header("Content-MD5", md5("part"))
            .body("part")
        .when()
            .put("/" + bucket + "/object.txt?partNumber=1&uploadId=" + uploadId)
        .then()
            .statusCode(200);
    }

    private static String md5(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    private static String createBucket(String prefix) {
        String bucket = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        given().when().put("/" + bucket).then().statusCode(200);
        return bucket;
    }

    private static void assertObjectBody(String bucket, String key, String body) {
        given().when().get("/" + bucket + "/" + key).then().statusCode(200).body(equalTo(body));
    }

    private static void assertObjectAbsent(String bucket, String key) {
        given().when().get("/" + bucket + "/" + key).then().statusCode(404);
    }
}
