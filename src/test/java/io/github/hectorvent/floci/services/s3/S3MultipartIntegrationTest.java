package io.github.hectorvent.floci.services.s3;

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
            .body("Part1Data-Hello")
        .when()
            .put("/" + BUCKET + "/" + KEY + "?uploadId=" + uploadId + "&partNumber=1")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());
    }

    @Test
    @Order(4)
    void uploadPart2() {
        given()
            .body("Part2Data-World")
        .when()
            .put("/" + BUCKET + "/" + KEY + "?uploadId=" + uploadId + "&partNumber=2")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());
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
                    <Part><PartNumber>1</PartNumber><ETag>etag1</ETag></Part>
                    <Part><PartNumber>2</PartNumber><ETag>etag2</ETag></Part>
                </CompleteMultipartUpload>""";

        given()
            .contentType("application/xml")
            .body(completeXml)
        .when()
            .post("/" + BUCKET + "/" + KEY + "?uploadId=" + uploadId)
        .then()
            .statusCode(200)
            .body(containsString("<CompleteMultipartUploadResult"))
            .body(containsString("<ETag>"))
            .body(containsString("-2")); // Composite ETag ends with -2
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
            .body(equalTo("Part1Data-HelloPart2Data-World"));
    }

    @Test
    @Order(10)
    void getMultipartObjectAttributes() {
        given()
            .header("x-amz-object-attributes", "ObjectParts,Checksum,StorageClass")
            .header("x-amz-max-parts", 1)
        .when()
            .get("/" + BUCKET + "/" + KEY + "?attributes")
        .then()
            .statusCode(200)
            .body(containsString("<GetObjectAttributesResponse"))
            .body(containsString("<StorageClass>STANDARD_IA</StorageClass>"))
            .body(containsString("<ObjectParts>"))
            .body(containsString("<PartsCount>2</PartsCount>"))
            .body(containsString("<ChecksumSHA256>"));
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

        // Complete the upload
        String completeXml = """
                <CompleteMultipartUpload>
                    <Part><PartNumber>1</PartNumber><ETag>etag1</ETag></Part>
                    <Part><PartNumber>2</PartNumber><ETag>etag2</ETag></Part>
                </CompleteMultipartUpload>""";
        given()
            .contentType("application/xml")
            .body(completeXml)
        .when()
            .post("/" + BUCKET + "/copy-dest.bin?uploadId=" + copyUploadId)
        .then()
            .statusCode(200);

        // Verify contents: full source + ranged slice
        given()
        .when()
            .get("/" + BUCKET + "/copy-dest.bin")
        .then()
            .statusCode(200)
            .body(equalTo("ABCDEFGHIJCDEF"));
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
    void cleanUp() {
        given().when().delete("/" + BUCKET + "/" + KEY).then().statusCode(204);
        given().when().delete("/" + BUCKET + "/source-for-copy.bin").then().statusCode(204);
        given().when().delete("/" + BUCKET + "/copy-dest.bin").then().statusCode(204);
        given().when().delete("/" + BUCKET + "/sse-c-multipart.bin").then().statusCode(204);
        given().when().delete("/" + BUCKET + "/sse-c-source-for-copy.bin").then().statusCode(204);
        given().when().delete("/" + BUCKET).then().statusCode(204);
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
