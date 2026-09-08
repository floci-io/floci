package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.s3.model.S3Checksum;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3MultipartIntegrationTest {

    private static final String BUCKET = "multipart-test-bucket";
    private static final String KEY = "large-file.bin";
    private static final String SSE_CUSTOMER_KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String SSE_CUSTOMER_KEY_MD5 = customerKeyMd5(SSE_CUSTOMER_KEY);
    private static final String WRONG_SSE_CUSTOMER_KEY = Base64.getEncoder().encodeToString("abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8));
    private static final String WRONG_SSE_CUSTOMER_KEY_MD5 = customerKeyMd5(WRONG_SSE_CUSTOMER_KEY);
    private static final String PART_1 = "Part1Data-Hello";
    private static final String PART_2 = "Part2Data-World";
    private static final String COMPOSITE_SHA256 = compositeSha256(PART_1, PART_2);
    private static String uploadId;

    @Test
    @Order(1)
    void createBucket() {
        given()
            .when().put("/" + BUCKET)
            .then().statusCode(200);
    }

    @Test
    @Order(2)
    void initiateMultipartUpload() {
        uploadId = given()
            .contentType("application/octet-stream")
            .header("x-amz-meta-owner", "team-a")
            .header("x-amz-storage-class", "STANDARD_IA")
            .header("Content-Disposition", "attachment; filename=\"multipart.bin\"")
            .header("x-amz-server-side-encryption", "AES256")
            .header("x-amz-checksum-algorithm", "SHA256")
        .when()
            .post("/" + BUCKET + "/" + KEY + "?uploads")
        .then()
            .statusCode(200)
            .body(containsString("<UploadId>"))
            .body(containsString("<Bucket>" + BUCKET + "</Bucket>"))
            .body(containsString("<Key>" + KEY + "</Key>"))
            .extract().xmlPath().getString(
                "InitiateMultipartUploadResult.UploadId");
    }

    @Test
    @Order(3)
    void uploadPart1() {
        given()
            .body(PART_1)
        .when()
            .put("/" + BUCKET + "/" + KEY + "?uploadId=" + uploadId + "&partNumber=1")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .header("x-amz-checksum-sha256", equalTo(S3Checksum.sha256Base64(PART_1.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    @Order(4)
    void uploadPart2() {
        given()
            .body(PART_2)
        .when()
            .put("/" + BUCKET + "/" + KEY + "?uploadId=" + uploadId + "&partNumber=2")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .header("x-amz-checksum-sha256", equalTo(S3Checksum.sha256Base64(PART_2.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    @Order(5)
    void listParts() {
        given()
        .when()
            .get("/" + BUCKET + "/" + KEY + "?uploadId=" + uploadId)
        .then()
            .statusCode(200)
            .body(containsString("<ListPartsResult"))
            .body(containsString("<Bucket>" + BUCKET + "</Bucket>"))
            .body(containsString("<Key>" + KEY + "</Key>"))
            .body(containsString("<UploadId>" + uploadId + "</UploadId>"))
            .body(containsString("<PartNumber>1</PartNumber>"))
            .body(containsString("<PartNumber>2</PartNumber>"))
            .body(containsString("<IsTruncated>false</IsTruncated>"));
    }

    @Test
    @Order(6)
    void listMultipartUploads() {
        given()
        .when()
            .get("/" + BUCKET + "?uploads")
        .then()
            .statusCode(200)
            .body(containsString("<UploadId>" + uploadId + "</UploadId>"))
            .body(containsString("<Key>" + KEY + "</Key>"));
    }

    @Test
    @Order(8)
    void completeMultipartUpload() {
        String completeXml = """
                <CompleteMultipartUpload>
                    <Part><PartNumber>1</PartNumber><ETag>etag1</ETag><ChecksumSHA256>%s</ChecksumSHA256></Part>
                    <Part><PartNumber>2</PartNumber><ETag>etag2</ETag><ChecksumSHA256>%s</ChecksumSHA256></Part>
                </CompleteMultipartUpload>""".formatted(sha256Base64(PART_1), sha256Base64(PART_2));

        given()
            .contentType("application/xml")
            .body(completeXml)
        .when()
            .post("/" + BUCKET + "/" + KEY + "?uploadId=" + uploadId)
        .then()
            .statusCode(200)
            .body(containsString("<CompleteMultipartUploadResult"))
            .body(containsString("<ETag>"))
            .body(containsString("-2")) // Composite ETag ends with -2
            .body(containsString("<ChecksumSHA256>" + COMPOSITE_SHA256 + "</ChecksumSHA256>"))
            .body(containsString("<ChecksumType>COMPOSITE</ChecksumType>"));
    }

    @Test
    @Order(9)
    void getCompletedObject() {
        given()
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200)
            .header("Content-Disposition", equalTo("attachment; filename=\"multipart.bin\""))
            .header("x-amz-server-side-encryption", equalTo("AES256"))
            .header("x-amz-meta-owner", equalTo("team-a"))
            .header("x-amz-storage-class", equalTo("STANDARD_IA"))
            .body(equalTo(PART_1 + PART_2));

        given()
            .header("x-amz-checksum-mode", "ENABLED")
        .when()
            .head("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-sha256", equalTo(COMPOSITE_SHA256))
            .header("x-amz-checksum-type", equalTo("COMPOSITE"));
    }

    @Test
    @Order(10)
    void getMultipartObjectAttributes() {
        given()
            .header("x-amz-object-attributes", "ETag,ObjectParts,Checksum,StorageClass")
            .header("x-amz-max-parts", 1)
        .when()
            .get("/" + BUCKET + "/" + KEY + "?attributes")
        .then()
            .statusCode(200)
            .body(containsString("<GetObjectAttributesResponse"))
            .body(matchesPattern("(?s).*<ETag>[0-9a-f]{32}-2</ETag>.*"))
            .body(containsString("<StorageClass>STANDARD_IA</StorageClass>"))
            .body(containsString("<ObjectParts>"))
            .body(containsString("<PartsCount>2</PartsCount>"))
            // GetObjectAttributes reports the composite value without the "-N" suffix
            .body(containsString("<ChecksumSHA256>" + S3Checksum.withoutPartCount(COMPOSITE_SHA256) + "</ChecksumSHA256>"))
            .body(containsString("<ChecksumType>COMPOSITE</ChecksumType>"))
            .body(containsString("<ChecksumSHA256>" + S3Checksum.sha256Base64(PART_1.getBytes(StandardCharsets.UTF_8))
                    + "</ChecksumSHA256>"));
    }

    @Test
    @Order(11)
    void multipartUploadNoLongerListed() {
        given()
        .when()
            .get("/" + BUCKET + "?uploads")
        .then()
            .statusCode(200)
            .body(not(containsString("<UploadId>")));
    }

    @Test
    @Order(12)
    void abortMultipartUpload() {
        // Initiate new upload
        String newUploadId = given()
            .when()
                .post("/" + BUCKET + "/abort-test.bin?uploads")
            .then()
                .statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        // Upload a part
        given()
            .body("some data")
        .when()
            .put("/" + BUCKET + "/abort-test.bin?uploadId=" + newUploadId + "&partNumber=1")
        .then()
            .statusCode(200);

        // Abort
        given()
        .when()
            .delete("/" + BUCKET + "/abort-test.bin?uploadId=" + newUploadId)
        .then()
            .statusCode(204);

        // Verify upload is gone
        given()
        .when()
            .get("/" + BUCKET + "?uploads")
        .then()
            .statusCode(200)
            .body(not(containsString(newUploadId)));
    }

    @Test
    @Order(13)
    void uploadPartCopy() {
        // Put a source object
        given()
            .body("ABCDEFGHIJ")
        .when()
            .put("/" + BUCKET + "/source-for-copy.bin")
        .then()
            .statusCode(200);

        // Initiate multipart upload for destination
        String copyUploadId = given()
            .when()
                .post("/" + BUCKET + "/copy-dest.bin?uploads")
            .then()
                .statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        // UploadPartCopy full source
        given()
            .header("x-amz-copy-source", "/" + BUCKET + "/source-for-copy.bin")
        .when()
            .put("/" + BUCKET + "/copy-dest.bin?uploadId=" + copyUploadId + "&partNumber=1")
        .then()
            .statusCode(200)
            .body(containsString("<CopyPartResult"))
            .body(containsString("<ETag>"));

        // UploadPartCopy with range (bytes 2-5 → "CDEF")
        given()
            .header("x-amz-copy-source", "/" + BUCKET + "/source-for-copy.bin")
            .header("x-amz-copy-source-range", "bytes=2-5")
        .when()
            .put("/" + BUCKET + "/copy-dest.bin?uploadId=" + copyUploadId + "&partNumber=2")
        .then()
            .statusCode(200)
            .body(containsString("<CopyPartResult"))
            .body(containsString("<ETag>"));

        // Percent-encoded bucket/key separator: the AWS SDK for .NET encodes the whole
        // copy source, so the header carries no literal slash.
        given()
            .header("x-amz-copy-source", BUCKET + "%2Fsource-for-copy.bin")
            .header("x-amz-copy-source-range", "bytes=2-5")
        .when()
            .put("/" + BUCKET + "/copy-dest.bin?uploadId=" + copyUploadId + "&partNumber=3")
        .then()
            .statusCode(200)
            .body(containsString("<CopyPartResult"))
            .body(containsString("<ETag>"));

        // Complete the upload
        String completeXml = """
                <CompleteMultipartUpload>
                    <Part><PartNumber>1</PartNumber><ETag>etag1</ETag></Part>
                    <Part><PartNumber>2</PartNumber><ETag>etag2</ETag></Part>
                    <Part><PartNumber>3</PartNumber><ETag>etag3</ETag></Part>
                </CompleteMultipartUpload>""";
        given()
            .contentType("application/xml")
            .body(completeXml)
        .when()
            .post("/" + BUCKET + "/copy-dest.bin?uploadId=" + copyUploadId)
        .then()
            .statusCode(200);

        // Verify contents: full source, ranged slice, then the same slice copied
        // through the percent-encoded separator. The last four bytes prove the
        // encoded source resolved to the same object, not just that it returned 200.
        given()
        .when()
            .get("/" + BUCKET + "/copy-dest.bin")
        .then()
            .statusCode(200)
            .body(equalTo("ABCDEFGHIJCDEFCDEF"));
    }

    @Test
    @Order(14)
    void initiateMultipartUploadRejectsUnsupportedServerSideEncryption() {
        given()
            .header("x-amz-server-side-encryption", "totally-unsupported")
        .when()
            .post("/" + BUCKET + "/invalid-sse.bin?uploads")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"))
            .body(containsString("Unsupported x-amz-server-side-encryption value"));
    }

    @Test
    @Order(15)
    void multipartUploadWithSseCustomerKeyRequiresMatchingPartKeys() {
        String sseUploadId = given()
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
        .when()
            .post("/" + BUCKET + "/sse-c-multipart.bin?uploads")
        .then()
            .statusCode(200)
            .header("x-amz-server-side-encryption-customer-algorithm", equalTo("AES256"))
            .header("x-amz-server-side-encryption-customer-key-MD5", equalTo(SSE_CUSTOMER_KEY_MD5))
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .body("missing-key")
        .when()
            .put("/" + BUCKET + "/sse-c-multipart.bin?uploadId=" + sseUploadId + "&partNumber=1")
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"));

        given()
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", WRONG_SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", WRONG_SSE_CUSTOMER_KEY_MD5)
            .body("wrong-key")
        .when()
            .put("/" + BUCKET + "/sse-c-multipart.bin?uploadId=" + sseUploadId + "&partNumber=1")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        String partETag = given()
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
            .body("sse-c-part")
        .when()
            .put("/" + BUCKET + "/sse-c-multipart.bin?uploadId=" + sseUploadId + "&partNumber=1")
        .then()
            .statusCode(200)
            .header("x-amz-server-side-encryption-customer-algorithm", equalTo("AES256"))
            .header("x-amz-server-side-encryption-customer-key-MD5", equalTo(SSE_CUSTOMER_KEY_MD5))
            .extract().header("ETag");

        String completeXml = """
                <CompleteMultipartUpload>
                    <Part><PartNumber>1</PartNumber><ETag>%s</ETag></Part>
                </CompleteMultipartUpload>""".formatted(partETag);
        given()
            .contentType("application/xml")
            .body(completeXml)
        .when()
            .post("/" + BUCKET + "/sse-c-multipart.bin?uploadId=" + sseUploadId)
        .then()
            .statusCode(200)
            .header("x-amz-server-side-encryption-customer-algorithm", equalTo("AES256"))
            .header("x-amz-server-side-encryption-customer-key-MD5", equalTo(SSE_CUSTOMER_KEY_MD5));

        given()
        .when()
            .get("/" + BUCKET + "/sse-c-multipart.bin")
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"));

        given()
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
        .when()
            .get("/" + BUCKET + "/sse-c-multipart.bin")
        .then()
            .statusCode(200)
            .header("x-amz-server-side-encryption-customer-algorithm", equalTo("AES256"))
            .header("x-amz-server-side-encryption-customer-key-MD5", equalTo(SSE_CUSTOMER_KEY_MD5))
            .body(equalTo("sse-c-part"));
    }

    @Test
    @Order(16)
    void uploadPartCopyWithSseCustomerSourceRequiresSourceKey() {
        given()
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
            .body("SSE-C-COPY")
        .when()
            .put("/" + BUCKET + "/sse-c-source-for-copy.bin")
        .then()
            .statusCode(200);

        String copyUploadId = given()
            .when()
                .post("/" + BUCKET + "/sse-c-upload-part-copy.bin?uploads")
            .then()
                .statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .header("x-amz-copy-source", "/" + BUCKET + "/sse-c-source-for-copy.bin")
        .when()
            .put("/" + BUCKET + "/sse-c-upload-part-copy.bin?uploadId=" + copyUploadId + "&partNumber=1")
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"));

        given()
            .header("x-amz-copy-source", "/" + BUCKET + "/sse-c-source-for-copy.bin")
            .header("x-amz-copy-source-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-copy-source-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-copy-source-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
        .when()
            .put("/" + BUCKET + "/sse-c-upload-part-copy.bin?uploadId=" + copyUploadId + "&partNumber=1")
        .then()
            .statusCode(200)
            .body(containsString("<CopyPartResult"));

        given()
        .when()
            .delete("/" + BUCKET + "/sse-c-upload-part-copy.bin?uploadId=" + copyUploadId)
        .then()
            .statusCode(204);

        String normalUploadId = given()
            .when()
                .post("/" + BUCKET + "/sse-c-headers-on-normal-upload.bin?uploads")
            .then()
                .statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
            .body("unexpected-sse-c-part")
        .when()
            .put("/" + BUCKET + "/sse-c-headers-on-normal-upload.bin?uploadId=" + normalUploadId + "&partNumber=1")
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"));

        given()
        .when()
            .delete("/" + BUCKET + "/sse-c-headers-on-normal-upload.bin?uploadId=" + normalUploadId)
        .then()
            .statusCode(204);

        String sseCopyUploadId = given()
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
            .when()
                .post("/" + BUCKET + "/sse-c-upload-part-copy-dest.bin?uploads")
            .then()
                .statusCode(200)
                .header("x-amz-server-side-encryption-customer-algorithm", equalTo("AES256"))
                .header("x-amz-server-side-encryption-customer-key-MD5", equalTo(SSE_CUSTOMER_KEY_MD5))
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .header("x-amz-copy-source", "/" + BUCKET + "/sse-c-source-for-copy.bin")
            .header("x-amz-copy-source-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-copy-source-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-copy-source-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
        .when()
            .put("/" + BUCKET + "/sse-c-upload-part-copy-dest.bin?uploadId=" + sseCopyUploadId + "&partNumber=1")
        .then()
            .statusCode(200)
            .header("x-amz-server-side-encryption-customer-algorithm", equalTo("AES256"))
            .header("x-amz-server-side-encryption-customer-key-MD5", equalTo(SSE_CUSTOMER_KEY_MD5))
            .body(containsString("<CopyPartResult"));

        given()
        .when()
            .delete("/" + BUCKET + "/sse-c-upload-part-copy-dest.bin?uploadId=" + sseCopyUploadId)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(17)
    void initiateMultipartUploadRejectsConflictingServerSideEncryption() {
        given()
            .header("x-amz-server-side-encryption", "AES256")
            .header("x-amz-server-side-encryption-customer-algorithm", "AES256")
            .header("x-amz-server-side-encryption-customer-key", SSE_CUSTOMER_KEY)
            .header("x-amz-server-side-encryption-customer-key-MD5", SSE_CUSTOMER_KEY_MD5)
        .when()
            .post("/" + BUCKET + "/conflicting-sse-c-multipart.bin?uploads")
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"))
            .body(containsString("SSE-C cannot be combined"));
    }

    @Test
    @Order(18)
    void multipartUploadAppliesInlineTagging() {
        String taggedKey = "tagged-multipart.bin";
        String taggingUploadId = given()
            .header("x-amz-tagging", "token=abc-123&teamId=42&note=hello%20world")
        .when()
            .post("/" + BUCKET + "/" + taggedKey + "?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .body("TaggedPartData")
        .when()
            .put("/" + BUCKET + "/" + taggedKey + "?uploadId=" + taggingUploadId + "&partNumber=1")
        .then()
            .statusCode(200);

        String completeXml = """
                <CompleteMultipartUpload>
                    <Part><PartNumber>1</PartNumber><ETag>etag1</ETag></Part>
                </CompleteMultipartUpload>""";
        given()
            .contentType("application/xml")
            .body(completeXml)
        .when()
            .post("/" + BUCKET + "/" + taggedKey + "?uploadId=" + taggingUploadId)
        .then()
            .statusCode(200);

        // Tags from the x-amz-tagging header on CreateMultipartUpload must be present
        // on the completed object, including URL-decoded values.
        given()
        .when()
            .get("/" + BUCKET + "/" + taggedKey + "?tagging")
        .then()
            .statusCode(200)
            .body(containsString("<Key>token</Key>"))
            .body(containsString("<Value>abc-123</Value>"))
            .body(containsString("<Key>teamId</Key>"))
            .body(containsString("<Value>42</Value>"))
            .body(containsString("<Key>note</Key>"))
            .body(containsString("<Value>hello world</Value>"));
    }

    @Test
    @Order(19)
    void initiateMultipartUploadRejectsMalformedTaggingHeader() {
        given()
            .header("x-amz-tagging", "missing-equals-sign")
        .when()
            .post("/" + BUCKET + "/bad-tagging.bin?uploads")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"));
    }

    @Test
    @Order(20)
    void completeMultipartUploadRejectsMismatchedFullObjectChecksum() {
        String key = "checksum-mismatch-multipart.bin";
        String newUploadId = given()
            .header("x-amz-checksum-algorithm", "CRC32")
            .header("x-amz-checksum-type", "FULL_OBJECT")
        .when()
            .post("/" + BUCKET + "/" + key + "?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .body("Part1Data-Hello")
        .when()
            .put("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId + "&partNumber=1")
        .then()
            .statusCode(200);

        String completeXml = """
                <CompleteMultipartUpload>
                    <Part><PartNumber>1</PartNumber><ETag>etag1</ETag></Part>
                </CompleteMultipartUpload>""";

        given()
            .contentType("application/xml")
            .header("x-amz-checksum-type", "FULL_OBJECT")
            .header("x-amz-checksum-crc32", "AAAAAA==")
            .body(completeXml)
        .when()
            .post("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId)
        .then()
            .statusCode(400)
            .body(containsString("BadDigest"));

        // The checksum mismatch rejects completion before the upload is consumed, so abort it explicitly.
        given()
        .when()
            .delete("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(21)
    void completeMultipartUploadAcceptsMatchingFullObjectChecksum() {
        String key = "checksum-match-multipart.bin";
        String data = "Part1Data-Hello";
        String correctCrc32 = S3Checksum.crc32Base64(data.getBytes(StandardCharsets.UTF_8));

        String newUploadId = given()
            .header("x-amz-checksum-algorithm", "CRC32")
            .header("x-amz-checksum-type", "FULL_OBJECT")
        .when()
            .post("/" + BUCKET + "/" + key + "?uploads")
        .then()
            .statusCode(200)
            .header("x-amz-checksum-algorithm", equalTo("CRC32"))
            .header("x-amz-checksum-type", equalTo("FULL_OBJECT"))
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .body(data)
        .when()
            .put("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId + "&partNumber=1")
        .then()
            .statusCode(200);

        String completeXml = """
                <CompleteMultipartUpload>
                    <Part><PartNumber>1</PartNumber><ETag>etag1</ETag></Part>
                </CompleteMultipartUpload>""";

        given()
            .contentType("application/xml")
            .header("x-amz-checksum-type", "FULL_OBJECT")
            .header("x-amz-checksum-crc32", correctCrc32)
            .body(completeXml)
        .when()
            .post("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId)
        .then()
            .statusCode(200)
            .body(containsString("<CompleteMultipartUploadResult"));
    }

    @Test
    @Order(22)
    void completeMultipartUploadRejectsFullObjectShaChecksum() {
        String key = "checksum-sha-full-object-multipart.bin";
        String newUploadId = given()
            .header("x-amz-checksum-algorithm", "SHA256")
        .when()
            .post("/" + BUCKET + "/" + key + "?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .body("Part1Data-Hello")
        .when()
            .put("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId + "&partNumber=1")
        .then()
            .statusCode(200);

        given()
            .contentType("application/xml")
            .header("x-amz-checksum-type", "FULL_OBJECT")
            .header("x-amz-checksum-sha256", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            .body(singlePartCompleteXml("<ChecksumSHA256>" + sha256Base64("Part1Data-Hello") + "</ChecksumSHA256>"))
        .when()
            .post("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId)
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"));

        given()
        .when()
            .delete("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(23)
    void completeMultipartUploadReportsFullObjectChecksumType() {
        String key = "checksum-type-multipart.bin";
        String data = "Part1Data-Hello";
        String correctCrc32 = S3Checksum.crc32Base64(data.getBytes(StandardCharsets.UTF_8));

        String newUploadId = given()
            .header("x-amz-checksum-algorithm", "CRC32")
            .header("x-amz-checksum-type", "FULL_OBJECT")
        .when()
            .post("/" + BUCKET + "/" + key + "?uploads")
        .then()
            .statusCode(200)
            .header("x-amz-checksum-algorithm", equalTo("CRC32"))
            .header("x-amz-checksum-type", equalTo("FULL_OBJECT"))
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .body(data)
        .when()
            .put("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId + "&partNumber=1")
        .then()
            .statusCode(200);

        String completeXml = """
                <CompleteMultipartUpload>
                    <Part><PartNumber>1</PartNumber><ETag>etag1</ETag></Part>
                </CompleteMultipartUpload>""";

        given()
            .contentType("application/xml")
            .header("x-amz-checksum-type", "FULL_OBJECT")
            .header("x-amz-checksum-crc32", correctCrc32)
            .body(completeXml)
        .when()
            .post("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId)
        .then()
            .statusCode(200);

        given()
            .header("x-amz-object-attributes", "Checksum")
        .when()
            .get("/" + BUCKET + "/" + key + "?attributes")
        .then()
            .statusCode(200)
            .body(containsString("<ChecksumType>FULL_OBJECT</ChecksumType>"));
    }

    @Test
    @Order(24)
    void initiateMultipartUploadResolvesChecksumType() {
        String newUploadId = given()
            .header("x-amz-checksum-algorithm", "SHA256")
        .when()
            .post("/" + BUCKET + "/checksum-type-default.bin?uploads")
        .then()
            .statusCode(200)
            .header("x-amz-checksum-algorithm", equalTo("SHA256"))
            .header("x-amz-checksum-type", equalTo("COMPOSITE"))
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
        given().when().delete("/" + BUCKET + "/checksum-type-default.bin?uploadId=" + newUploadId).then().statusCode(204);

        given()
            .header("x-amz-checksum-algorithm", "SHA256")
            .header("x-amz-checksum-type", "FULL_OBJECT")
        .when()
            .post("/" + BUCKET + "/checksum-type-invalid.bin?uploads")
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"))
            .body(containsString("The FULL_OBJECT checksum type cannot be used with the sha256 checksum algorithm."));

        given()
            .header("x-amz-checksum-algorithm", "CRC64NVME")
            .header("x-amz-checksum-type", "COMPOSITE")
        .when()
            .post("/" + BUCKET + "/checksum-type-invalid.bin?uploads")
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"));

        // S3 validates the type header on its own, and only accepts it next to an algorithm
        given()
            .header("x-amz-checksum-type", "PARTIAL")
        .when()
            .post("/" + BUCKET + "/checksum-type-invalid.bin?uploads")
        .then()
            .statusCode(400)
            .body(containsString("Value for x-amz-checksum-type header is invalid."));

        given()
            .header("x-amz-checksum-type", "FULL_OBJECT")
        .when()
            .post("/" + BUCKET + "/checksum-type-invalid.bin?uploads")
        .then()
            .statusCode(400)
            .body(containsString("The x-amz-checksum-type header can only be used with the x-amz-checksum-algorithm header."));
    }

    @Test
    @Order(25)
    void completeMultipartUploadRejectsFullObjectTypeOnCompositeUpload() {
        String key = "checksum-mode-mismatch.bin";
        String data = "Part1Data-Hello";
        String newUploadId = given()
            .header("x-amz-checksum-algorithm", "CRC32")
        .when()
            .post("/" + BUCKET + "/" + key + "?uploads")
        .then()
            .statusCode(200)
            .header("x-amz-checksum-type", equalTo("COMPOSITE"))
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .body(data)
        .when()
            .put("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId + "&partNumber=1")
        .then()
            .statusCode(200);

        given()
            .contentType("application/xml")
            .header("x-amz-checksum-type", "FULL_OBJECT")
            .header("x-amz-checksum-crc32", S3Checksum.crc32Base64(data.getBytes(StandardCharsets.UTF_8)))
            .body(singlePartCompleteXml("<ChecksumCRC32>" + S3Checksum.crc32Base64(data.getBytes(StandardCharsets.UTF_8)) + "</ChecksumCRC32>"))
        .when()
            .post("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId)
        .then()
            .statusCode(400)
            .body(containsString("InvalidRequest"))
            .body(containsString("The upload was created using the COMPOSITE checksum mode."));

        given().when().delete("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId).then().statusCode(204);
    }

    @Test
    @Order(26)
    void completeMultipartUploadRejectsMismatchedCompositeChecksum() {
        String key = "composite-mismatch-multipart.bin";
        String newUploadId = given()
            .header("x-amz-checksum-algorithm", "SHA256")
        .when()
            .post("/" + BUCKET + "/" + key + "?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .body("Part1Data-Hello")
        .when()
            .put("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId + "&partNumber=1")
        .then()
            .statusCode(200);

        given()
            .contentType("application/xml")
            .header("x-amz-checksum-type", "COMPOSITE")
            .header("x-amz-checksum-sha256", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=-1")
            .body(singlePartCompleteXml("<ChecksumSHA256>" + sha256Base64("Part1Data-Hello") + "</ChecksumSHA256>"))
        .when()
            .post("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId)
        .then()
            .statusCode(400)
            .body(containsString("BadDigest"))
            .body(containsString("The sha256 you specified did not match the calculated checksum."));

        given().when().delete("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId).then().statusCode(204);
    }

    @Test
    @Order(27)
    void completeMultipartUploadAcceptsMatchingCompositeChecksumAndPartChecksums() {
        String key = "composite-match-multipart.bin";
        String newUploadId = given()
            .header("x-amz-checksum-algorithm", "SHA256")
        .when()
            .post("/" + BUCKET + "/" + key + "?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        String part1Sha256 = S3Checksum.sha256Base64(PART_1.getBytes(StandardCharsets.UTF_8));
        String part2Sha256 = S3Checksum.sha256Base64(PART_2.getBytes(StandardCharsets.UTF_8));
        given()
            .header("x-amz-checksum-sha256", part1Sha256)
            .body(PART_1)
        .when()
            .put("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId + "&partNumber=1")
        .then()
            .statusCode(200);
        given()
            .header("x-amz-checksum-sha256", part2Sha256)
            .body(PART_2)
        .when()
            .put("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId + "&partNumber=2")
        .then()
            .statusCode(200);

        String completeXml = """
                <CompleteMultipartUpload xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <Part><ChecksumSHA256>%s</ChecksumSHA256><ETag>etag1</ETag><PartNumber>1</PartNumber></Part>
                    <Part><ChecksumSHA256>%s</ChecksumSHA256><ETag>etag2</ETag><PartNumber>2</PartNumber></Part>
                </CompleteMultipartUpload>""".formatted(part1Sha256, part2Sha256);

        given()
            .contentType("application/xml")
            .header("x-amz-checksum-type", "COMPOSITE")
            .header("x-amz-checksum-sha256", COMPOSITE_SHA256)
            .body(completeXml)
        .when()
            .post("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId)
        .then()
            .statusCode(200)
            .body(containsString("<ChecksumSHA256>" + COMPOSITE_SHA256 + "</ChecksumSHA256>"))
            .body(containsString("<ChecksumType>COMPOSITE</ChecksumType>"));

        given()
            .header("x-amz-checksum-mode", "ENABLED")
        .when()
            .get("/" + BUCKET + "/" + key)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-sha256", equalTo(COMPOSITE_SHA256))
            .header("x-amz-checksum-type", equalTo("COMPOSITE"));
    }

    @Test
    @Order(28)
    void completeMultipartUploadRejectsMismatchedPartChecksum() {
        String key = "bad-part-checksum-multipart.bin";
        String newUploadId = given()
            .header("x-amz-checksum-algorithm", "SHA256")
        .when()
            .post("/" + BUCKET + "/" + key + "?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .body("Part1Data-Hello")
        .when()
            .put("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId + "&partNumber=1")
        .then()
            .statusCode(200);

        String completeXml = """
                <CompleteMultipartUpload>
                    <Part><PartNumber>1</PartNumber><ETag>etag1</ETag>
                        <ChecksumSHA256>AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=</ChecksumSHA256></Part>
                </CompleteMultipartUpload>""";

        given()
            .contentType("application/xml")
            .body(completeXml)
        .when()
            .post("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId)
        .then()
            .statusCode(400)
            .body(containsString("InvalidPart"));

        given().when().delete("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId).then().statusCode(204);
    }

    @Test
    @Order(29)
    void completeMultipartUploadWithCrc64NvmeReportsFullObjectChecksum() {
        String key = "crc64-multipart.bin";
        String newUploadId = given()
            .header("x-amz-checksum-algorithm", "CRC64NVME")
        .when()
            .post("/" + BUCKET + "/" + key + "?uploads")
        .then()
            .statusCode(200)
            .header("x-amz-checksum-type", equalTo("FULL_OBJECT"))
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given().body(PART_1).when()
            .put("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId + "&partNumber=1")
            .then().statusCode(200);
        given().body(PART_2).when()
            .put("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId + "&partNumber=2")
            .then().statusCode(200);

        String fullObjectCrc64 = S3Checksum.crc64NvmeBase64((PART_1 + PART_2).getBytes(StandardCharsets.UTF_8));
        given()
            .contentType("application/xml")
            .body("""
                <CompleteMultipartUpload>
                    <Part><PartNumber>1</PartNumber><ETag>etag1</ETag></Part>
                    <Part><PartNumber>2</PartNumber><ETag>etag2</ETag></Part>
                </CompleteMultipartUpload>""")
        .when()
            .post("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId)
        .then()
            .statusCode(200)
            .body(containsString("<ChecksumCRC64NVME>" + fullObjectCrc64 + "</ChecksumCRC64NVME>"))
            .body(containsString("<ChecksumType>FULL_OBJECT</ChecksumType>"));

        given()
            .header("x-amz-checksum-mode", "ENABLED")
        .when()
            .head("/" + BUCKET + "/" + key)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-crc64nvme", equalTo(fullObjectCrc64))
            .header("x-amz-checksum-type", equalTo("FULL_OBJECT"));
    }

    @Test
    @Order(30)
    void copyOfMultipartObjectReportsFullObjectChecksum() {
        given()
            .header("x-amz-copy-source", "/" + BUCKET + "/" + KEY)
        .when()
            .put("/" + BUCKET + "/composite-copy.bin")
        .then()
            .statusCode(200)
            .body(containsString("CopyObjectResult"))
            .body(containsString("<ChecksumSHA256>" + S3Checksum.sha256Base64((PART_1 + PART_2).getBytes(StandardCharsets.UTF_8))
                    + "</ChecksumSHA256>"))
            .body(containsString("<ChecksumType>FULL_OBJECT</ChecksumType>"));

        given()
            .header("x-amz-checksum-mode", "ENABLED")
        .when()
            .head("/" + BUCKET + "/composite-copy.bin")
        .then()
            .statusCode(200)
            .header("x-amz-checksum-sha256", equalTo(S3Checksum.sha256Base64((PART_1 + PART_2).getBytes(StandardCharsets.UTF_8))))
            .header("x-amz-checksum-type", equalTo("FULL_OBJECT"));

        given()
            .header("x-amz-object-attributes", "ObjectParts,Checksum")
        .when()
            .get("/" + BUCKET + "/composite-copy.bin?attributes")
        .then()
            .statusCode(200)
            .body(containsString("<ChecksumType>FULL_OBJECT</ChecksumType>"))
            .body(not(containsString("<ObjectParts>")));
    }

    @Test
    @Order(31)
    void completeMultipartUploadRejectsNonNumericPartNumber() {
        String key = "malformed-complete.bin";
        String newUploadId = given()
        .when()
            .post("/" + BUCKET + "/" + key + "?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .contentType("application/xml")
            .body("<CompleteMultipartUpload><Part><PartNumber>one</PartNumber><ETag>etag1</ETag></Part></CompleteMultipartUpload>")
        .when()
            .post("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId)
        .then()
            .statusCode(400)
            .body(containsString("MalformedXML"));

        given().when().delete("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId).then().statusCode(204);
    }

    @Test
    @Order(32)
    void uploadPartEchoesNoChecksumWhenTheUploadDeclaredNoAlgorithm() {
        String key = "no-algorithm-part.bin";
        String newUploadId = given()
        .when()
            .post("/" + BUCKET + "/" + key + "?uploads")
        .then()
            .statusCode(200)
            .header("x-amz-checksum-algorithm", nullValue())
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .body(PART_1)
        .when()
            .put("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId + "&partNumber=1")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .header("x-amz-checksum-crc64nvme", nullValue())
            .header("x-amz-checksum-sha256", nullValue());

        given().when().delete("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId).then().statusCode(204);
    }

    @Test
    @Order(33)
    void completeMultipartUploadRequiresPartChecksumsOnCompositeUploads() {
        String key = "missing-part-checksums.bin";
        String newUploadId = given()
            .header("x-amz-checksum-algorithm", "SHA256")
        .when()
            .post("/" + BUCKET + "/" + key + "?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .body(PART_1)
        .when()
            .put("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId + "&partNumber=1")
        .then()
            .statusCode(200);

        given()
            .contentType("application/xml")
            .body(singlePartCompleteXml(""))
        .when()
            .post("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId)
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidRequest</Code>"))
            .body(containsString("The upload was created using a sha256 checksum. The complete request must include "
                    + "the checksum for each part. It was missing for part 1 in the request."));

        given()
            .contentType("application/xml")
            .body(singlePartCompleteXml("<ChecksumCRC32>" + S3Checksum.crc32Base64(PART_1.getBytes(StandardCharsets.UTF_8)) + "</ChecksumCRC32>"))
        .when()
            .post("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId)
        .then()
            .statusCode(400)
            .body(containsString("<Code>BadDigest</Code>"))
            .body(containsString("The crc32 you specified for part 1 did not match what we received."));

        given().when().delete("/" + BUCKET + "/" + key + "?uploadId=" + newUploadId).then().statusCode(204);
    }

    @Test
    @Order(34)
    void copyOfMultipartObjectGetsItsOwnEtag() {
        given()
            .header("x-amz-copy-source", "/" + BUCKET + "/" + KEY)
        .when()
            .put("/" + BUCKET + "/copy-of-multipart.bin")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            // the copy is a single object: a plain MD5 ETag, not the "-2" of the multipart source
            .body(matchesPattern("(?s).*<ETag>&quot;[0-9a-f]{32}&quot;</ETag>.*"));

        given()
        .when()
            .head("/" + BUCKET + "/copy-of-multipart.bin")
        .then()
            .statusCode(200)
            .header("ETag", matchesPattern("\"[0-9a-f]{32}\""));
    }

    @Test
    @Order(40)
    void cleanUp() {
        given().when().delete("/" + BUCKET + "/copy-of-multipart.bin").then().statusCode(204);
        given().when().delete("/" + BUCKET + "/" + KEY).then().statusCode(204);
        given().when().delete("/" + BUCKET + "/composite-match-multipart.bin").then().statusCode(204);
        given().when().delete("/" + BUCKET + "/crc64-multipart.bin").then().statusCode(204);
        given().when().delete("/" + BUCKET + "/composite-copy.bin").then().statusCode(204);
        given().when().delete("/" + BUCKET + "/tagged-multipart.bin").then().statusCode(204);
        given().when().delete("/" + BUCKET + "/source-for-copy.bin").then().statusCode(204);
        given().when().delete("/" + BUCKET + "/copy-dest.bin").then().statusCode(204);
        given().when().delete("/" + BUCKET + "/sse-c-multipart.bin").then().statusCode(204);
        given().when().delete("/" + BUCKET + "/sse-c-source-for-copy.bin").then().statusCode(204);
        given().when().delete("/" + BUCKET + "/checksum-match-multipart.bin").then().statusCode(204);
        given().when().delete("/" + BUCKET + "/checksum-type-multipart.bin").then().statusCode(204);
        given().when().delete("/" + BUCKET).then().statusCode(204);
    }

    private static String singlePartCompleteXml(String checksumElement) {
        return """
                <CompleteMultipartUpload>
                    <Part><PartNumber>1</PartNumber><ETag>etag1</ETag>%s</Part>
                </CompleteMultipartUpload>""".formatted(checksumElement);
    }

    private static String sha256Base64(String data) {
        return S3Checksum.sha256Base64(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String compositeSha256(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            MessageDigest composite = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                composite.update(digest.digest(part.getBytes(StandardCharsets.UTF_8)));
            }
            return Base64.getEncoder().encodeToString(composite.digest()) + "-" + parts.length;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String customerKeyMd5(String customerKey) {
        try {
            byte[] md5 = MessageDigest.getInstance("MD5").digest(Base64.getDecoder().decode(customerKey));
            return Base64.getEncoder().encodeToString(md5);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }
}
