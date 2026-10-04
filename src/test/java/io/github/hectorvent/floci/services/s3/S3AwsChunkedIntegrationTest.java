package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class S3AwsChunkedIntegrationTest {

    private static final String STREAMING_UNSIGNED = "STREAMING-UNSIGNED-PAYLOAD-TRAILER";
    private static final String INCOMPLETE_BODY_MESSAGE =
            "You did not provide the number of bytes specified by the Content-Length HTTP header.";

    @Test
    void putObject_chunkShorterThanItsDeclaredSize_isIncompleteBodyAndNotStored() {
        String bucket = createBucket("chunked-short");

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .header("Content-Encoding", "aws-chunked")
            .body("10\r\nonly-five\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(400)
            .body(containsString("<Code>IncompleteBody</Code>"))
            .body(containsString("<Message>" + INCOMPLETE_BODY_MESSAGE + "</Message>"));

        assertObjectAbsent(bucket, "object.txt");
    }

    @Test
    void putObject_bodyEndingBeforeItsFinalChunk_isIncompleteBodyAndNotStored() {
        String bucket = createBucket("chunked-truncated");

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .header("Content-Encoding", "aws-chunked")
            .body("5\r\nhello\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(400)
            .body(containsString("<Code>IncompleteBody</Code>"));

        assertObjectAbsent(bucket, "object.txt");
    }

    @Test
    void putObject_chunkSizeThatIsNotHex_isIncompleteBodyAndNotStored() {
        String bucket = createBucket("chunked-size");

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .header("Content-Encoding", "aws-chunked")
            .body("five\r\nhello\r\n0\r\n\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(400)
            .body(containsString("<Code>IncompleteBody</Code>"));

        assertObjectAbsent(bucket, "object.txt");
    }

    @Test
    void putObject_chunkNotFollowedByItsLineBreak_isIncompleteBodyAndNotStored() {
        String bucket = createBucket("chunked-delimiter");

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .header("Content-Encoding", "aws-chunked")
            .body("5\r\nhello0\r\n\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(400)
            .body(containsString("<Code>IncompleteBody</Code>"));

        assertObjectAbsent(bucket, "object.txt");
    }

    @Test
    void putObject_bodyEndingBeforeItsTrailerIsComplete_isIncompleteBodyAndNotStored() {
        String bucket = createBucket("chunked-trailer");

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .header("Content-Encoding", "aws-chunked")
            .body("5\r\nhello\r\n0\r\nx-amz-checksum-crc32:NhCmhg==\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(400)
            .body(containsString("<Code>IncompleteBody</Code>"));

        assertObjectAbsent(bucket, "object.txt");
    }

    @Test
    void putObject_bodyEndingRightAfterItsFinalChunkLine_isIncompleteBodyAndNotStored() {
        String bucket = createBucket("chunked-final-line");

        given()
            .header("x-amz-content-sha256", "STREAMING-AWS4-HMAC-SHA256-PAYLOAD")
            .header("Content-Encoding", "aws-chunked")
            .body("5;chunk-signature=abc\r\nhello\r\n0;chunk-signature=def\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(400)
            .body(containsString("<Code>IncompleteBody</Code>"));

        assertObjectAbsent(bucket, "object.txt");
    }

    @Test
    void putObject_malformedBodyDoesNotReplaceExistingObject() {
        String bucket = createBucket("chunked-overwrite");
        given().body("original").when().put("/" + bucket + "/object.txt").then().statusCode(200);

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .header("Content-Encoding", "aws-chunked")
            .body("10\r\nonly-five\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(400);

        given().when().get("/" + bucket + "/object.txt").then().statusCode(200).body(equalTo("original"));
    }

    // SigV4a signs streaming uploads with ECDSA; the body is framed the same way.
    @Test
    void putObject_malformedSigV4aStreamingBody_isIncompleteBodyAndNotStored() {
        String bucket = createBucket("chunked-sigv4a");

        given()
            .header("x-amz-content-sha256", "STREAMING-AWS4-ECDSA-P256-SHA256-PAYLOAD")
            .header("Content-Encoding", "aws-chunked")
            .body("10;chunk-signature=abc\r\nonly-five\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(400)
            .body(containsString("<Code>IncompleteBody</Code>"));

        assertObjectAbsent(bucket, "object.txt");
    }

    @Test
    void putObject_sigV4aStreamingBodyWithTrailer_storesTheDecodedPayload() {
        String bucket = createBucket("chunked-sigv4a-ok");

        given()
            .header("x-amz-content-sha256", "STREAMING-AWS4-ECDSA-P256-SHA256-PAYLOAD-TRAILER")
            .body("5;chunk-signature=abc\r\nhello\r\n0;chunk-signature=def\r\n"
                    + "x-amz-checksum-crc32:NhCmhg==\r\nx-amz-trailer-signature:ghi\r\n\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200);

        given().when().get("/" + bucket + "/object.txt").then().statusCode(200).body(equalTo("hello"));
    }

    @Test
    void uploadPart_malformedBody_isIncompleteBodyAndNoPartIsStored() {
        String bucket = createBucket("chunked-part");
        String uploadId = given()
            .when().post("/" + bucket + "/object.txt?uploads")
            .then().statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .header("Content-Encoding", "aws-chunked")
            .body("10\r\nonly-five\r\n")
        .when()
            .put("/" + bucket + "/object.txt?partNumber=1&uploadId=" + uploadId)
        .then()
            .statusCode(400)
            .body(containsString("<Code>IncompleteBody</Code>"));

        given()
            .when().get("/" + bucket + "/object.txt?uploadId=" + uploadId)
            .then().statusCode(200)
            .body(not(containsString("<Part>")));
    }

    // Without a streaming x-amz-content-sha256, a Content-Encoding naming aws-chunked does not
    // mean the body is framed, so a body that is not framed is stored as sent.
    @Test
    void putObject_awsChunkedEncodingWithoutStreamingPayload_storesTheBodyAsSent() {
        String bucket = createBucket("chunked-lenient");

        given()
            .header("Content-Encoding", "aws-chunked")
            .body("plain-content")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200);

        given().when().get("/" + bucket + "/object.txt").then().statusCode(200).body(equalTo("plain-content"));
    }

    private static String createBucket(String prefix) {
        String bucket = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        given().when().put("/" + bucket).then().statusCode(200);
        return bucket;
    }

    private static void assertObjectAbsent(String bucket, String key) {
        given().when().get("/" + bucket + "/" + key).then().statusCode(404);
    }
}
