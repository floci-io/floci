package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.s3.model.S3Checksum;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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
    private static final String ENTITY_TOO_LARGE_MESSAGE = "Your proposed upload exceeds the maximum allowed size";
    // S3 names the size proposed and the most allowed, in this order, between the message and the request id.
    private static final String ENTITY_TOO_LARGE_SIZES =
            "</Message><ProposedSize>5368709121</ProposedSize><MaxSizeAllowed>5368709120</MaxSizeAllowed><RequestId>";
    private static final String INVALID_CHUNK_SIZE_MESSAGE =
            "Only the last chunk is allowed to have a size less than 8192 bytes";
    // Two 100-byte chunks and the CRC32 of the 200 bytes: the first chunk is not the last, so it is under 8 KiB.
    private static final String SMALL_CHUNKS = "64\r\n" + "a".repeat(100) + "\r\n64\r\n" + "b".repeat(100)
            + "\r\n0\r\nx-amz-checksum-crc32:0+vo5w==\r\n\r\n";

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
            .header("x-amz-trailer", "x-amz-checksum-crc32")
            .body("5;chunk-signature=abc\r\nhello\r\n0;chunk-signature=def\r\n"
                    + "x-amz-checksum-crc32:NhCmhg==\r\nx-amz-trailer-signature:ghi\r\n\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200);

        given().when().get("/" + bucket + "/object.txt").then().statusCode(200).body(equalTo("hello"));
    }

    @Test
    void putObject_trailingChecksumMismatch_isBadDigestAndNotStored() {
        String bucket = createBucket("chunked-bad-digest");

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .header("x-amz-trailer", "x-amz-checksum-crc32")
            .body("b\r\nhello world\r\n0\r\nx-amz-checksum-crc32:AAAAAA==\r\n\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(400)
            .body(containsString("<Code>BadDigest</Code>"))
            .body(containsString("The CRC32 you specified did not match the calculated checksum."));

        assertObjectAbsent(bucket, "object.txt");
    }

    @Test
    void putObject_trailingChecksumMatch_storesTheDecodedPayload() {
        String bucket = createBucket("chunked-good-digest");

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .header("x-amz-trailer", "x-amz-checksum-crc32")
            .body("b\r\nhello world\r\n0\r\nx-amz-checksum-crc32:DUoRhQ==\r\n\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200);

        given().when().get("/" + bucket + "/object.txt").then().statusCode(200).body(equalTo("hello world"));
    }

    @Test
    void putObject_declaredTrailerMissingFromTheBody_isBadDigestAndNotStored() {
        String bucket = createBucket("chunked-trailer-missing");
        for (String body : new String[] {
                "b\r\nhello world\r\n0\r\nx-amz-checksum-crc32c:AAAAAA==\r\n\r\n",
                "b\r\nhello world\r\n0\r\n\r\n"}) {
            given()
                .header("x-amz-content-sha256", STREAMING_UNSIGNED)
                .header("x-amz-trailer", "x-amz-checksum-crc32")
                .body(body)
            .when()
                .put("/" + bucket + "/object.txt")
            .then()
                .statusCode(400)
                .body(containsString("<Code>BadDigest</Code>"))
                .body(containsString("The CRC32 you specified did not match the calculated checksum."));
        }

        assertObjectAbsent(bucket, "object.txt");
    }

    @Test
    void uploadPart_trailingChecksumMismatch_isBadDigestAndNoPartIsStored() {
        String bucket = createBucket("chunked-part-digest");
        String uploadId = given()
            .when().post("/" + bucket + "/object.txt?uploads")
            .then().statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .header("x-amz-trailer", "x-amz-checksum-crc32")
            .body("b\r\nhello world\r\n0\r\nx-amz-checksum-crc32:AAAAAA==\r\n\r\n")
        .when()
            .put("/" + bucket + "/object.txt?partNumber=1&uploadId=" + uploadId)
        .then()
            .statusCode(400)
            .body(containsString("<Code>BadDigest</Code>"))
            .body(containsString("The CRC32 you specified did not match the calculated checksum."));

        given()
            .when().get("/" + bucket + "/object.txt?uploadId=" + uploadId)
            .then().statusCode(200)
            .body(not(containsString("<Part>")));
    }

    @Test
    void uploadPart_trailingChecksumMatch_storesThePart() {
        String bucket = createBucket("chunked-part-ok");
        String uploadId = given()
            .when().post("/" + bucket + "/object.txt?uploads")
            .then().statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .header("x-amz-trailer", "x-amz-checksum-crc32")
            .body("b\r\nhello world\r\n0\r\nx-amz-checksum-crc32:DUoRhQ==\r\n"
                    + "x-amz-trailer-signature:ghi\r\n\r\n")
        .when()
            .put("/" + bucket + "/object.txt?partNumber=1&uploadId=" + uploadId)
        .then()
            .statusCode(200);

        given()
            .when().get("/" + bucket + "/object.txt?uploadId=" + uploadId)
            .then().statusCode(200)
            .body(containsString("<Part>"))
            .body(containsString("<Size>11</Size>"));
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

    @Test
    void putObject_chunkBeforeTheLastUnder8KiB_isInvalidChunkSizeErrorAndNotStored() {
        String bucket = createBucket("chunked-small");

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .header("Content-Encoding", "aws-chunked")
            .header("x-amz-decoded-content-length", "200")
            .header("x-amz-trailer", "x-amz-checksum-crc32")
            .body(SMALL_CHUNKS)
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(403)
            .body(containsString("<Code>InvalidChunkSizeError</Code>"))
            .body(containsString("<Message>" + INVALID_CHUNK_SIZE_MESSAGE + "</Message>"));

        assertObjectAbsent(bucket, "object.txt");
    }

    @Test
    void uploadPart_chunkBeforeTheLastUnder8KiB_isInvalidChunkSizeErrorAndNoPartIsStored() {
        String bucket = createBucket("chunked-part-small");
        String uploadId = given()
            .when().post("/" + bucket + "/object.txt?uploads")
            .then().statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .header("Content-Encoding", "aws-chunked")
            .header("x-amz-decoded-content-length", "200")
            .header("x-amz-trailer", "x-amz-checksum-crc32")
            .body(SMALL_CHUNKS)
        .when()
            .put("/" + bucket + "/object.txt?partNumber=1&uploadId=" + uploadId)
        .then()
            .statusCode(403)
            .body(containsString("<Code>InvalidChunkSizeError</Code>"))
            .body(containsString("<Message>" + INVALID_CHUNK_SIZE_MESSAGE + "</Message>"));

        given()
            .when().get("/" + bucket + "/object.txt?uploadId=" + uploadId)
            .then().statusCode(200)
            .body(not(containsString("<Part>")));
    }

    @Test
    void putObject_lastChunkUnder8KiB_storesTheDecodedPayload() {
        String bucket = createBucket("chunked-small-last");
        String payload = "a".repeat(8192) + "b".repeat(100);

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .header("Content-Encoding", "aws-chunked")
            .header("x-amz-decoded-content-length", String.valueOf(payload.length()))
            .header("x-amz-trailer", "x-amz-checksum-crc32")
            .body("2000\r\n" + "a".repeat(8192) + "\r\n64\r\n" + "b".repeat(100) + "\r\n0\r\nx-amz-checksum-crc32:"
                    + S3Checksum.crc32Base64(payload.getBytes(StandardCharsets.US_ASCII)) + "\r\n\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200);

        given().when().get("/" + bucket + "/object.txt").then().statusCode(200).body(equalTo(payload));
    }

    @Test
    void putObject_declaringMoreThanFiveGibibytes_isEntityTooLargeAndNotStored() {
        String bucket = createBucket("chunked-too-large");

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .header("Content-Encoding", "aws-chunked")
            .header("x-amz-decoded-content-length", "5368709121")
            .body("5\r\nhello\r\n0\r\n\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(400)
            .body(containsString("<Code>EntityTooLarge</Code>"))
            .body(containsString("<Message>" + ENTITY_TOO_LARGE_MESSAGE + ENTITY_TOO_LARGE_SIZES));
        assertObjectAbsent(bucket, "object.txt");

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .header("Content-Encoding", "aws-chunked")
            .header("x-amz-decoded-content-length", "5")
            .body("5\r\nhello\r\n0\r\n\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200);
    }

    @Test
    void uploadPart_declaringMoreThanFiveGibibytes_isEntityTooLargeAndNoPartIsStored() {
        String bucket = createBucket("chunked-part-too-large");
        String uploadId = given()
            .when().post("/" + bucket + "/object.txt?uploads")
            .then().statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .header("Content-Encoding", "aws-chunked")
            .header("x-amz-decoded-content-length", "5368709121")
            .body("5\r\nhello\r\n0\r\n\r\n")
        .when()
            .put("/" + bucket + "/object.txt?partNumber=1&uploadId=" + uploadId)
        .then()
            .statusCode(400)
            .body(containsString("<Code>EntityTooLarge</Code>"))
            .body(containsString("<Message>" + ENTITY_TOO_LARGE_MESSAGE + ENTITY_TOO_LARGE_SIZES));

        given()
            .when().get("/" + bucket + "/object.txt?uploadId=" + uploadId)
            .then().statusCode(200)
            .body(not(containsString("<Part>")));
    }

    @Test
    void putObject_chunkedBodyWithoutATrailer_storesTheDecodedPayload() {
        String bucket = createBucket("chunked-no-trailer");

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .body("5\r\nhello\r\n0\r\nx-amz-trailer-signature:ghi\r\n\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200);

        given().when().get("/" + bucket + "/object.txt").then().statusCode(200).body(equalTo("hello"));
    }

    @Test
    void putObject_undeclaredChecksumTrailer_isBadDigestAndNotStored() {
        String bucket = createBucket("chunked-undeclared");

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .body("b\r\nhello world\r\n0\r\nx-amz-checksum-crc32:DUoRhQ==\r\n\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(400)
            .body(containsString("<Code>BadDigest</Code>"))
            .body(containsString("The CRC32 you specified did not match the calculated checksum."));

        given()
            .header("x-amz-content-sha256", STREAMING_UNSIGNED)
            .header("x-amz-trailer", "x-amz-checksum-crc32")
            .body("b\r\nhello world\r\n0\r\nx-amz-checksum-crc32:DUoRhQ==\r\n"
                    + "x-amz-checksum-sha256:abc\r\n\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(400)
            .body(containsString("<Code>BadDigest</Code>"))
            .body(containsString("The SHA256 you specified did not match the calculated checksum."));

        assertObjectAbsent(bucket, "object.txt");
    }

    @Test
    void putObject_awsChunkedTrailerWithoutStreamingSha256_rejectsAMismatchedTrailer() {
        String bucket = createBucket("chunked-lenient-trailer");

        given()
            .header("Content-Encoding", "aws-chunked")
            .header("x-amz-trailer", "x-amz-checksum-crc32")
            .body("b\r\nhello world\r\n0\r\nx-amz-checksum-crc32:AAAAAA==\r\n\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(400)
            .body(containsString("<Code>BadDigest</Code>"))
            .body(containsString("The CRC32 you specified did not match the calculated checksum."));

        assertObjectAbsent(bucket, "object.txt");
    }

    @Test
    void putObject_awsChunkedTrailerWithoutStreamingSha256_storesAMatchingTrailer() {
        String bucket = createBucket("chunked-lenient-ok");

        given()
            .header("Content-Encoding", "aws-chunked")
            .header("x-amz-trailer", "x-amz-checksum-crc32")
            .body("b\r\nhello world\r\n0\r\nx-amz-checksum-crc32:DUoRhQ==\r\n\r\n")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200);

        given().when().get("/" + bucket + "/object.txt").then().statusCode(200).body(equalTo("hello world"));
    }

    @Test
    void uploadPart_awsChunkedTrailerWithoutStreamingSha256_rejectsAMismatchedTrailer() {
        String bucket = createBucket("chunked-part-lenient");
        String uploadId = given()
            .when().post("/" + bucket + "/object.txt?uploads")
            .then().statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .header("Content-Encoding", "aws-chunked")
            .header("x-amz-trailer", "x-amz-checksum-crc32")
            .body("b\r\nhello world\r\n0\r\nx-amz-checksum-crc32:AAAAAA==\r\n\r\n")
        .when()
            .put("/" + bucket + "/object.txt?partNumber=1&uploadId=" + uploadId)
        .then()
            .statusCode(400)
            .body(containsString("<Code>BadDigest</Code>"))
            .body(containsString("The CRC32 you specified did not match the calculated checksum."));

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
