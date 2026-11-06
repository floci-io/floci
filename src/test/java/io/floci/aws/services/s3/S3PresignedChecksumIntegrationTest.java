package io.floci.aws.services.s3;

import io.floci.aws.core.common.Digests;
import io.floci.aws.services.s3.model.S3Checksum;
import io.floci.aws.testing.S3EnforceAuthProfile;
import io.floci.aws.testutil.S3RequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestProfile(S3EnforceAuthProfile.class)
class S3PresignedChecksumIntegrationTest {

    private static final String BUCKET = "presigned-checksum-bucket";
    private static final String ACCESS_KEY = "test";
    private static final String SECRET_KEY = "test";
    private static final S3RequestSigner LOCAL_SIGNER = S3RequestSigner.signedAs(ACCESS_KEY, SECRET_KEY);
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    @Test
    void presignedPutWithSdkEmptyBodyChecksumInQueryIgnoresIt() throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/mismatched-crc32.txt";
        // JS SDK v3 presigns before the body is known; AWS ignores this hoisted empty-body checksum.
        String emptyPayloadCrc32 = S3Checksum.crc32Base64(new byte[0]);
        String url = presign("PUT", path, Map.of(
                "x-amz-checksum-crc32", emptyPayloadCrc32,
                "x-amz-sdk-checksum-algorithm", "CRC32"), Map.of());

        byte[] body = "not an empty body".getBytes(StandardCharsets.UTF_8);
        given()
            .urlEncodingEnabled(false)
            .body(body)
        .when()
            .put(url)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-crc32", nullValue());

        given()
            .filter(LOCAL_SIGNER)
        .when()
            .get(path)
        .then()
            .statusCode(200)
            .body(equalTo(new String(body, StandardCharsets.UTF_8)));

        given()
            .filter(LOCAL_SIGNER)
            .header("x-amz-checksum-mode", "ENABLED")
        .when()
            .head(path)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-crc32", nullValue())
            .header("x-amz-checksum-crc64nvme", equalTo(S3Checksum.crc64NvmeBase64(body)));
    }

    @Test
    void presignedPutWithMatchingChecksumInQueryDoesNotStoreIt() throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/matching-crc32.txt";
        byte[] body = "hello presigned checksum world".getBytes(StandardCharsets.UTF_8);
        String expectedCrc32 = S3Checksum.crc32Base64(body);
        String url = presign("PUT", path, Map.of("x-amz-checksum-crc32", expectedCrc32), Map.of());

        given()
            .urlEncodingEnabled(false)
            .body(body)
        .when()
            .put(url)
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .header("x-amz-checksum-crc32", nullValue());

        given()
            .filter(LOCAL_SIGNER)
            .header("x-amz-checksum-mode", "ENABLED")
        .when()
            .head(path)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-crc32", nullValue())
            .header("x-amz-checksum-crc64nvme", equalTo(S3Checksum.crc64NvmeBase64(body)));
    }

    @Test
    void presignedPutWithChecksumAlgorithmInQueryUsesDefaultChecksum() throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/algorithm-query.txt";
        byte[] body = "calculate crc32 from algorithm query param".getBytes(StandardCharsets.UTF_8);
        String expectedChecksum = S3Checksum.crc64NvmeBase64(body);
        String url = presign("PUT", path, Map.of("x-amz-checksum-algorithm", "CRC32"), Map.of());

        given()
            .urlEncodingEnabled(false)
            .body(body)
        .when()
            .put(url)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-crc32", nullValue())
            .header("x-amz-checksum-crc64nvme", equalTo(expectedChecksum));

        String getUrl = presign("GET", path, Map.of("x-amz-checksum-mode", "ENABLED"), Map.of());
        given()
            .urlEncodingEnabled(false)
        .when()
            .get(getUrl)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-crc64nvme", equalTo(expectedChecksum))
            .body(equalTo(new String(body, StandardCharsets.UTF_8)));
    }

    @Test
    void presignedPutWithMismatchedSha256InQueryIgnoresIt() throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/mismatched-sha256.txt";
        String invalidSha256 = S3Checksum.sha256Base64("other content".getBytes(StandardCharsets.UTF_8));
        String url = presign("PUT", path, Map.of("x-amz-checksum-sha256", invalidSha256), Map.of());

        given()
            .urlEncodingEnabled(false)
            .body("real content".getBytes(StandardCharsets.UTF_8))
        .when()
            .put(url)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-sha256", nullValue());
    }

    @Test
    void presignedUploadPartWithMismatchedChecksumQueryParamIgnoresIt() throws Exception {
        createBucket();
        String key = "multipart-checksum.txt";
        String path = "/" + BUCKET + "/" + key;

        String uploadId = given()
            .filter(LOCAL_SIGNER)
        .when()
            .post(path + "?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        String wrongCrc32 = S3Checksum.crc32Base64(new byte[0]);
        String partUrl = presign("PUT", path,
                Map.of("uploadId", uploadId, "partNumber", "1", "x-amz-checksum-crc32", wrongCrc32),
                Map.of());

        String etag = given()
            .urlEncodingEnabled(false)
            .body("part data not matching empty crc32".getBytes(StandardCharsets.UTF_8))
        .when()
            .put(partUrl)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-crc32", nullValue())
            .extract().header("ETag");

        String completeUrl = presign("POST", path,
                Map.of("uploadId", uploadId, "x-amz-checksum-type", "COMPOSITE"), Map.of());
        given()
            .urlEncodingEnabled(false)
            .body("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>" + etag
                    + "</ETag></Part></CompleteMultipartUpload>")
        .when()
            .post(completeUrl)
        .then()
            .statusCode(200);

        given()
            .filter(LOCAL_SIGNER)
        .when()
            .get(path)
        .then()
            .statusCode(200)
            .body(equalTo("part data not matching empty crc32"));
    }

    @Test
    void presignedCreateMultipartUploadWithQueryChecksumSettingsIgnoresThem() throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/multipart-query-settings.txt";
        String url = presign("POST", path, Map.of("uploads", "",
                "x-amz-checksum-algorithm", "CRC32", "x-amz-checksum-type", "FULL_OBJECT"), Map.of());

        String uploadId = given()
            .urlEncodingEnabled(false)
        .when()
            .post(url)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-algorithm", nullValue())
            .header("x-amz-checksum-type", nullValue())
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .filter(LOCAL_SIGNER)
        .when()
            .delete(path + "?uploadId=" + uploadId)
        .then()
            .statusCode(204);
    }

    @Test
    void presignedCreateMultipartUploadWithChecksumHeadersPreservesThem() throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/multipart-header-settings.txt";
        String url = presign("POST", path, Map.of("uploads", ""),
                Map.of("x-amz-checksum-algorithm", "CRC32", "x-amz-checksum-type", "FULL_OBJECT"));

        String uploadId = given()
            .urlEncodingEnabled(false)
            .header("x-amz-checksum-algorithm", "CRC32")
            .header("x-amz-checksum-type", "FULL_OBJECT")
        .when()
            .post(url)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-algorithm", equalTo("CRC32"))
            .header("x-amz-checksum-type", equalTo("FULL_OBJECT"))
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .filter(LOCAL_SIGNER)
        .when()
            .delete(path + "?uploadId=" + uploadId)
        .then()
            .statusCode(204);
    }

    @Test
    void presignedCopyObjectWithChecksumAlgorithmInQueryPreservesSourceChecksum() throws Exception {
        createBucket();
        String sourcePath = "/" + BUCKET + "/copy-source.txt";
        byte[] body = "copy source content for checksum test".getBytes(StandardCharsets.UTF_8);
        given()
            .filter(LOCAL_SIGNER)
            .body(body)
        .when()
            .put(sourcePath)
        .then()
            .statusCode(200);

        String destPath = "/" + BUCKET + "/copy-dest.txt";
        String expectedChecksum = S3Checksum.crc64NvmeBase64(body);
        String copyUrl = presign("PUT", destPath,
                Map.of("x-amz-checksum-algorithm", "SHA256"),
                Map.of("x-amz-copy-source", "/" + BUCKET + "/copy-source.txt"));

        given()
            .urlEncodingEnabled(false)
            .header("x-amz-copy-source", "/" + BUCKET + "/copy-source.txt")
        .when()
            .put(copyUrl)
        .then()
            .statusCode(200)
            .body(containsString("CopyObjectResult"))
            .body(containsString("<ChecksumCRC64NVME>" + expectedChecksum + "</ChecksumCRC64NVME>"));

        given()
            .filter(LOCAL_SIGNER)
            .header("x-amz-checksum-mode", "ENABLED")
        .when()
            .head(destPath)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-sha256", nullValue())
            .header("x-amz-checksum-crc64nvme", equalTo(expectedChecksum));
    }

    @Test
    void presignedPutWithMatchingChecksumHeaderStoresItDespiteMismatchedQuery() throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/matching-header.txt";
        byte[] body = "checksum header takes precedence".getBytes(StandardCharsets.UTF_8);
        String checksum = S3Checksum.crc32Base64(body);
        String url = presign("PUT", path,
                Map.of("x-amz-checksum-crc32", "AAAAAA=="),
                Map.of("x-amz-checksum-crc32", checksum, "x-amz-sdk-checksum-algorithm", "CRC32"));

        given()
            .urlEncodingEnabled(false)
            .header("x-amz-checksum-crc32", checksum)
            .header("x-amz-sdk-checksum-algorithm", "CRC32")
            .body(body)
        .when()
            .put(url)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-crc32", equalTo(checksum));

        given()
            .filter(LOCAL_SIGNER)
            .header("x-amz-checksum-mode", "ENABLED")
        .when()
            .head(path)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-crc32", equalTo(checksum));
    }

    @Test
    void presignedPutWithMismatchedChecksumHeaderFailsDespiteMatchingQuery() throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/mismatched-header.txt";
        byte[] body = "body does not match the header".getBytes(StandardCharsets.UTF_8);
        String url = presign("PUT", path,
                Map.of("x-amz-checksum-crc32", S3Checksum.crc32Base64(body)),
                Map.of("x-amz-checksum-crc32", "AAAAAA=="));

        given()
            .urlEncodingEnabled(false)
            .header("x-amz-checksum-crc32", "AAAAAA==")
            .body(body)
        .when()
            .put(url)
        .then()
            .statusCode(400)
            .body(containsString("BadDigest"));

        given()
            .filter(LOCAL_SIGNER)
        .when()
            .head(path)
        .then()
            .statusCode(404);
    }

    @Test
    void presignedPutWithTamperedChecksumQueryIsRejected() throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/tampered-query.txt";
        String url = presign("PUT", path, Map.of("x-amz-checksum-crc32", "AAAAAA=="), Map.of());
        String tampered = url.replace("x-amz-checksum-crc32=" + uriEncode("AAAAAA=="),
                "x-amz-checksum-crc32=" + uriEncode("BBBBBB=="));

        given()
            .urlEncodingEnabled(false)
            .body("not an empty body")
        .when()
            .put(tampered)
        .then()
            .statusCode(403)
            .body(containsString("SignatureDoesNotMatch"));
    }

    @Test
    void presignedUploadPartWithMismatchedChecksumHeaderFails() throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/multipart-header.txt";
        String uploadId = given()
            .filter(LOCAL_SIGNER)
        .when()
            .post(path + "?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
        String url = presign("PUT", path, Map.of("uploadId", uploadId, "partNumber", "1"),
                Map.of("x-amz-checksum-crc32", "AAAAAA=="));

        given()
            .urlEncodingEnabled(false)
            .header("x-amz-checksum-crc32", "AAAAAA==")
            .body("not an empty body")
        .when()
            .put(url)
        .then()
            .statusCode(400)
            .body(containsString("BadDigest"));
    }

    @Test
    void presignedPutWithUserMetadataInQueryStoresTheMetadata() throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/query-metadata.txt";
        String url = presign("PUT", path,
                Map.of("x-amz-meta-source", "presigned-put", "x-amz-meta-Uploaded-By", "tester"), Map.of());

        given()
            .urlEncodingEnabled(false)
            .body("x".getBytes(StandardCharsets.UTF_8))
        .when()
            .put(url)
        .then()
            .statusCode(200);

        given()
            .filter(LOCAL_SIGNER)
        .when()
            .head(path)
        .then()
            .statusCode(200)
            .header("x-amz-meta-source", equalTo("presigned-put"))
            .header("x-amz-meta-uploaded-by", equalTo("tester"));
    }

    @Test
    void presignedPutWithUnsignedMetadataHeaderIsRejectedAsHeadersNotSigned() throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/unsigned-metadata-header.txt";
        String url = presign("PUT", path, Map.of("x-amz-meta-source", "signed"), Map.of());

        given()
            .urlEncodingEnabled(false)
            .header("x-amz-meta-source", "altered")
            .body("x".getBytes(StandardCharsets.UTF_8))
        .when()
            .put(url)
        .then()
            .statusCode(403)
            .body(containsString("<Code>AccessDenied</Code>"))
            .body(containsString("<HeadersNotSigned>x-amz-meta-source</HeadersNotSigned>"));

        given()
            .filter(LOCAL_SIGNER)
        .when()
            .head(path)
        .then()
            .statusCode(404);
    }

    @Test
    void presignedCreateMultipartUploadWithUserMetadataInQueryStoresTheMetadata() throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/multipart-query-metadata.txt";
        String initiateUrl = presign("POST", path,
                Map.of("uploads", "", "x-amz-meta-source", "presigned-multipart"), Map.of());

        String uploadId = given()
            .urlEncodingEnabled(false)
        .when()
            .post(initiateUrl)
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        String etag = given()
            .filter(LOCAL_SIGNER)
            .body("part".getBytes(StandardCharsets.UTF_8))
        .when()
            .put(path + "?partNumber=1&uploadId=" + uploadId)
        .then()
            .statusCode(200)
            .extract().header("ETag");

        given()
            .filter(LOCAL_SIGNER)
            .body("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>" + etag
                    + "</ETag></Part></CompleteMultipartUpload>")
        .when()
            .post(path + "?uploadId=" + uploadId)
        .then()
            .statusCode(200);

        given()
            .filter(LOCAL_SIGNER)
        .when()
            .head(path)
        .then()
            .statusCode(200)
            .header("x-amz-meta-source", equalTo("presigned-multipart"));
    }

    @Test
    void presignedCopyObjectWithReplacedUserMetadataInQueryStoresTheMetadata() throws Exception {
        createBucket();
        String sourcePath = "/" + BUCKET + "/metadata-copy-source.txt";
        given()
            .filter(LOCAL_SIGNER)
            .header("x-amz-meta-source", "original")
            .body("copy me".getBytes(StandardCharsets.UTF_8))
        .when()
            .put(sourcePath)
        .then()
            .statusCode(200);

        String destPath = "/" + BUCKET + "/metadata-copy-dest.txt";
        String copyUrl = presign("PUT", destPath,
                Map.of("x-amz-meta-source", "copied"),
                Map.of("x-amz-copy-source", sourcePath, "x-amz-metadata-directive", "REPLACE"));

        given()
            .urlEncodingEnabled(false)
            .header("x-amz-copy-source", sourcePath)
            .header("x-amz-metadata-directive", "REPLACE")
        .when()
            .put(copyUrl)
        .then()
            .statusCode(200)
            .body(containsString("CopyObjectResult"));

        given()
            .filter(LOCAL_SIGNER)
        .when()
            .head(destPath)
        .then()
            .statusCode(200)
            .header("x-amz-meta-source", equalTo("copied"));
    }

    private static void createBucket() {
        given()
            .filter(LOCAL_SIGNER)
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    private static String presign(String method, String path, Map<String, String> queryParams,
                                  Map<String, String> signedHeaders) throws Exception {
        String amzDate = AMZ_DATE.format(Instant.now());
        String date = amzDate.substring(0, 8);
        String credentialScope = date + "/us-east-1/s3/aws4_request";

        TreeMap<String, String> headers = new TreeMap<>(signedHeaders);
        headers.put("host", "localhost:" + RestAssured.port);
        String signedHeaderNames = String.join(";", headers.keySet());

        TreeMap<String, String> params = new TreeMap<>(queryParams);
        params.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        params.put("X-Amz-Credential", ACCESS_KEY + "/" + credentialScope);
        params.put("X-Amz-Date", amzDate);
        params.put("X-Amz-Expires", "3600");
        params.put("X-Amz-SignedHeaders", signedHeaderNames);
        String canonicalQuery = params.entrySet().stream()
                .map(entry -> uriEncode(entry.getKey()) + "=" + uriEncode(entry.getValue()))
                .collect(Collectors.joining("&"));
        String canonicalHeaders = headers.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue().trim() + "\n")
                .collect(Collectors.joining());

        String canonicalRequest = method + "\n"
                + path + "\n"
                + canonicalQuery + "\n"
                + canonicalHeaders + "\n"
                + signedHeaderNames + "\n"
                + "UNSIGNED-PAYLOAD";
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + Digests.sha256Hex(canonicalRequest);

        byte[] kDate = hmacSha256(("AWS4" + SECRET_KEY).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmacSha256(kDate, "us-east-1");
        byte[] kService = hmacSha256(kRegion, "s3");
        byte[] signingKey = hmacSha256(kService, "aws4_request");
        String signature = hexEncode(hmacSha256(signingKey, stringToSign));

        return path + "?" + canonicalQuery + "&X-Amz-Signature=" + signature;
    }

    private static String uriEncode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(String.format("%02X", c));
            }
        }
        return encoded.toString();
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
