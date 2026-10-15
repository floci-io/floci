package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.s3.model.S3Checksum;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * GetObject and HeadObject with {@code partNumber}, following what S3 answers in LocalStack's
 * AWS-validated {@code test_get_object_part}, {@code test_get_object_part_checksum} and
 * {@code test_complete_multipart_parts_checksum_*} captures: an object completed from a 5 MiB part and
 * the 16 bytes of "test content 123".
 */
@QuarkusTest
class S3GetObjectPartIntegrationTest {

    private static final byte[] FIRST_PART = filled(5 * 1024 * 1024);
    private static final String SECOND_PART = "test content 123";
    private static final long OBJECT_SIZE = FIRST_PART.length + SECOND_PART.length();

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void getObject_partOfAMultipartObject_isServedLikeItsRange() {
        String bucket = createBucket("part-get");
        String eTag = uploadInTwoParts(bucket, "object.bin", null, null);

        given()
        .when()
            .get("/" + bucket + "/object.bin?partNumber=2")
        .then()
            .statusCode(206)
            .header("Content-Length", equalTo("16"))
            .header("Content-Range", equalTo("bytes 5242880-5242895/" + OBJECT_SIZE))
            .header("x-amz-mp-parts-count", equalTo("2"))
            .header("ETag", equalTo(eTag))
            .body(equalTo(SECOND_PART));

        byte[] first = given()
        .when()
            .get("/" + bucket + "/object.bin?partNumber=1")
        .then()
            .statusCode(206)
            .header("Content-Range", equalTo("bytes 0-5242879/" + OBJECT_SIZE))
            .header("x-amz-mp-parts-count", equalTo("2"))
            .extract().asByteArray();
        assertArrayEquals(FIRST_PART, first);
    }

    @Test
    void headObject_partOfAMultipartObject_describesThatPart() {
        String bucket = createBucket("part-head");
        String eTag = uploadInTwoParts(bucket, "object.bin", null, null);

        given()
        .when()
            .head("/" + bucket + "/object.bin?partNumber=2")
        .then()
            .statusCode(206)
            .header("Content-Length", equalTo("16"))
            .header("Content-Range", equalTo("bytes 5242880-5242895/" + OBJECT_SIZE))
            .header("x-amz-mp-parts-count", equalTo("2"))
            .header("ETag", equalTo(eTag));
    }

    @Test
    void getObject_partTheObjectDoesNotHave_isInvalidPartNumber() {
        String bucket = createBucket("part-missing");
        uploadInTwoParts(bucket, "object.bin", null, null);

        given()
        .when()
            .get("/" + bucket + "/object.bin?partNumber=3")
        .then()
            .statusCode(416)
            .body(containsString("<Code>InvalidPartNumber</Code>"
                    + "<Message>The requested partnumber is not satisfiable</Message>"
                    + "<PartNumberRequested>3</PartNumberRequested><ActualPartCount>2</ActualPartCount>"));
        given()
        .when()
            .head("/" + bucket + "/object.bin?partNumber=3")
        .then()
            .statusCode(416)
            .body(emptyString());
        // Past 10,000 is still a part the object does not have, not an invalid part number.
        given()
        .when()
            .get("/" + bucket + "/object.bin?partNumber=10001")
        .then()
            .statusCode(416)
            .body(containsString("<Code>InvalidPartNumber</Code>"))
            .body(containsString("<PartNumberRequested>10001</PartNumberRequested><ActualPartCount>2</ActualPartCount>"));
        // So is one past what a long holds, named as it was asked for.
        given()
        .when()
            .get("/" + bucket + "/object.bin?partNumber=9223372036854775808")
        .then()
            .statusCode(416)
            .body(containsString("<Code>InvalidPartNumber</Code>"))
            .body(containsString("<PartNumberRequested>9223372036854775808</PartNumberRequested>"
                    + "<ActualPartCount>2</ActualPartCount>"));
        given()
        .when()
            .head("/" + bucket + "/object.bin?partNumber=9223372036854775808")
        .then()
            .statusCode(416)
            .body(emptyString());
    }

    @Test
    void getObject_partNumberWithARange_isInvalidRequest() {
        String bucket = createBucket("part-range");
        uploadInTwoParts(bucket, "object.bin", null, null);

        for (String method : new String[] {"GET", "HEAD"}) {
            given()
                .header("Range", "bytes=0-8")
            .when()
                .request(method, "/" + bucket + "/object.bin?partNumber=2")
            .then()
                .statusCode(400);
        }
        given()
            .header("Range", "bytes=0-8")
        .when()
            .get("/" + bucket + "/object.bin?partNumber=2")
        .then()
            .body(containsString("<Code>InvalidRequest</Code>"
                    + "<Message>Cannot specify both Range header and partNumber query parameter</Message>"));
    }

    @Test
    void getObject_partNumberBelowOne_isInvalidArgument() {
        String bucket = createBucket("part-invalid");
        given().body("test-123").when().put("/" + bucket + "/object.txt").then().statusCode(200);

        for (String partNumber : new String[] {"0", "-1", "abc"}) {
            given()
            .when()
                .get("/" + bucket + "/object.txt?partNumber=" + partNumber)
            .then()
                .statusCode(400)
                .body(containsString("<Code>InvalidArgument</Code>"
                        + "<Message>Part number must be an integer between 1 and 10000, inclusive</Message>"))
                .body(containsString("<ArgumentName>partNumber</ArgumentName>"
                        + "<ArgumentValue>" + partNumber + "</ArgumentValue>"));
        }
    }

    @Test
    void getObject_partOfAnObjectStoredInOneRequest_isTheWholeObjectAsPartOne() {
        String bucket = createBucket("part-single");
        String crc32 = S3Checksum.crc32Base64("test-123".getBytes(StandardCharsets.UTF_8));
        given()
            .header("x-amz-checksum-crc32", crc32)
            .body("test-123")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200);

        given()
            .header("x-amz-checksum-mode", "ENABLED")
        .when()
            .get("/" + bucket + "/object.txt?partNumber=1")
        .then()
            .statusCode(206)
            .header("Content-Range", equalTo("bytes 0-7/8"))
            .header("x-amz-mp-parts-count", nullValue())
            .header("x-amz-checksum-crc32", equalTo(crc32))
            .header("x-amz-checksum-type", equalTo("FULL_OBJECT"))
            .body(equalTo("test-123"));
        given()
        .when()
            .get("/" + bucket + "/object.txt?partNumber=2")
        .then()
            .statusCode(416)
            .body(containsString("<PartNumberRequested>2</PartNumberRequested><ActualPartCount>1</ActualPartCount>"));
    }

    @Test
    void getObject_partOfAnEmptyObject_isAnsweredAsTheWholeEmptyObject() {
        String bucket = createBucket("part-empty");
        given().body(new byte[0]).when().put("/" + bucket + "/empty.txt").then().statusCode(200);

        given()
        .when()
            .get("/" + bucket + "/empty.txt?partNumber=1")
        .then()
            .statusCode(200)
            .header("Content-Length", equalTo("0"))
            .header("Content-Range", nullValue())
            .body(emptyString());
    }

    @Test
    void getObject_partOfACompositeChecksumObject_carriesThatPartsChecksum() {
        String bucket = createBucket("part-composite");
        uploadInTwoParts(bucket, "object.bin", "CRC32C", null);

        for (String method : new String[] {"GET", "HEAD"}) {
            given()
                .header("x-amz-checksum-mode", "ENABLED")
            .when()
                .request(method, "/" + bucket + "/object.bin?partNumber=2")
            .then()
                .statusCode(206)
                .header("x-amz-checksum-crc32c",
                        equalTo(S3Checksum.crc32cBase64(SECOND_PART.getBytes(StandardCharsets.UTF_8))))
                .header("x-amz-checksum-type", equalTo("COMPOSITE"));
        }
        given()
            .header("x-amz-checksum-mode", "ENABLED")
        .when()
            .get("/" + bucket + "/object.bin?partNumber=1")
        .then()
            .statusCode(206)
            .header("x-amz-checksum-crc32c", equalTo(S3Checksum.crc32cBase64(FIRST_PART)));
        // Without checksum mode, no checksum is sent.
        given()
        .when()
            .get("/" + bucket + "/object.bin?partNumber=2")
        .then()
            .statusCode(206)
            .header("x-amz-checksum-crc32c", nullValue());
    }

    @Test
    void getObject_partOfAFullObjectChecksumObjectOfSeveralParts_carriesNoChecksum() {
        String bucket = createBucket("part-full-object");
        uploadInTwoParts(bucket, "object.bin", "CRC32C", "FULL_OBJECT");

        given()
            .header("x-amz-checksum-mode", "ENABLED")
        .when()
            .get("/" + bucket + "/object.bin?partNumber=2")
        .then()
            .statusCode(206)
            .header("x-amz-checksum-crc32c", nullValue())
            .header("x-amz-checksum-type", nullValue());
    }

    @Test
    void getObject_onlyPartOfAFullObjectChecksumObject_carriesTheObjectsChecksum() {
        String bucket = createBucket("part-one-full-object");
        String uploadId = createUpload(bucket, "object.bin", "CRC32C", "FULL_OBJECT");
        String partETag = uploadPart(bucket, "object.bin", uploadId, 1, SECOND_PART.getBytes(StandardCharsets.UTF_8));
        complete(bucket, "object.bin", uploadId, partXml(1, partETag, null));

        given()
            .header("x-amz-checksum-mode", "ENABLED")
        .when()
            .get("/" + bucket + "/object.bin?partNumber=1")
        .then()
            .statusCode(206)
            .header("x-amz-mp-parts-count", equalTo("1"))
            .header("x-amz-checksum-crc32c",
                    equalTo(S3Checksum.crc32cBase64(SECOND_PART.getBytes(StandardCharsets.UTF_8))))
            .header("x-amz-checksum-type", equalTo("FULL_OBJECT"));
    }

    /** Uploads {@link #FIRST_PART} and {@link #SECOND_PART} as parts 1 and 2, returning the object's ETag. */
    private static String uploadInTwoParts(String bucket, String key, String checksumAlgorithm, String checksumType) {
        String uploadId = createUpload(bucket, key, checksumAlgorithm, checksumType);
        byte[] second = SECOND_PART.getBytes(StandardCharsets.UTF_8);
        String firstETag = uploadPart(bucket, key, uploadId, 1, FIRST_PART);
        String secondETag = uploadPart(bucket, key, uploadId, 2, second);
        // A composite checksum is completed from the checksum of every part.
        boolean composite = checksumAlgorithm != null && checksumType == null;
        return complete(bucket, key, uploadId,
                partXml(1, firstETag, composite ? S3Checksum.crc32cBase64(FIRST_PART) : null)
                        + partXml(2, secondETag, composite ? S3Checksum.crc32cBase64(second) : null));
    }

    private static String createUpload(String bucket, String key, String checksumAlgorithm, String checksumType) {
        RequestSpecification request = given();
        if (checksumAlgorithm != null) {
            request.header("x-amz-checksum-algorithm", checksumAlgorithm);
        }
        if (checksumType != null) {
            request.header("x-amz-checksum-type", checksumType);
        }
        return request
        .when()
            .post("/" + bucket + "/" + key + "?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
    }

    private static String uploadPart(String bucket, String key, String uploadId, int partNumber, byte[] data) {
        return given()
            .body(data)
        .when()
            .put("/" + bucket + "/" + key + "?partNumber=" + partNumber + "&uploadId=" + uploadId)
        .then()
            .statusCode(200)
            .extract().header("ETag");
    }

    private static String complete(String bucket, String key, String uploadId, String partsXml) {
        return given()
            .contentType("application/xml")
            .body("<CompleteMultipartUpload>" + partsXml + "</CompleteMultipartUpload>")
        .when()
            .post("/" + bucket + "/" + key + "?uploadId=" + uploadId)
        .then()
            .statusCode(200)
            .body(containsString("<ETag>"))
            .extract().xmlPath().getString("CompleteMultipartUploadResult.ETag");
    }

    private static String partXml(int partNumber, String eTag, String crc32c) {
        return "<Part><PartNumber>" + partNumber + "</PartNumber><ETag>" + eTag + "</ETag>"
                + (crc32c != null ? "<ChecksumCRC32C>" + crc32c + "</ChecksumCRC32C>" : "") + "</Part>";
    }

    private String createBucket(String prefix) {
        String bucket = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(), auth("test", "s3"), bucket));
        given().when().put("/" + bucket).then().statusCode(200);
        return bucket;
    }

    private static byte[] filled(int size) {
        byte[] data = new byte[size];
        Arrays.fill(data, (byte) 'a');
        return data;
    }
}
