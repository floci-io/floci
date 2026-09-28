package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.s3.model.S3Checksum;
import io.github.hectorvent.floci.testing.S3EnforceAuthProfile;
import io.github.hectorvent.floci.testutil.S3RequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    void presignedPutWithMismatchedChecksumCrc32InQueryFailsWith400BadDigest() throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/mismatched-crc32.txt";
        // SDK JS v3 presigner ký CRC32 của empty payload (AAAAAA==) khi chưa có body tại thời điểm ký
        String emptyPayloadCrc32 = S3Checksum.crc32Base64(new byte[0]);
        String url = presign("PUT", path, Map.of("x-amz-checksum-crc32", emptyPayloadCrc32), Map.of());

        byte[] body = "not an empty body".getBytes(StandardCharsets.UTF_8);
        given()
            .urlEncodingEnabled(false)
            .body(body)
        .when()
            .put(url)
        .then()
            .statusCode(400)
            .body(containsString("BadDigest"))
            .body(containsString("The CRC32 checksum you specified did not match the payload."));

        // Xác nhận object không được lưu vào storage
        given()
            .filter(LOCAL_SIGNER)
        .when()
            .get(path)
        .then()
            .statusCode(404)
            .body(containsString("NoSuchKey"));
    }

    @Test
    void presignedPutWithMatchingChecksumCrc32InQuerySucceedsAndStoresChecksum() throws Exception {
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
            .header("x-amz-checksum-crc32", equalTo(expectedCrc32));

        // Kiểm tra HeadObject với x-amz-checksum-mode=ENABLED trả về checksum đã lưu
        given()
            .filter(LOCAL_SIGNER)
            .header("x-amz-checksum-mode", "ENABLED")
        .when()
            .head(path)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-crc32", equalTo(expectedCrc32));
    }

    @Test
    void presignedPutWithChecksumAlgorithmInQueryComputesAndStoresChecksum() throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/algorithm-query.txt";
        byte[] body = "calculate crc32 from algorithm query param".getBytes(StandardCharsets.UTF_8);
        String expectedCrc32 = S3Checksum.crc32Base64(body);
        String url = presign("PUT", path, Map.of("x-amz-checksum-algorithm", "CRC32"), Map.of());

        given()
            .urlEncodingEnabled(false)
            .body(body)
        .when()
            .put(url)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-crc32", equalTo(expectedCrc32));

        // Query param x-amz-checksum-mode=ENABLED trên presigned GET cũng phải trả về checksum
        String getUrl = presign("GET", path, Map.of("x-amz-checksum-mode", "ENABLED"), Map.of());
        given()
            .urlEncodingEnabled(false)
        .when()
            .get(getUrl)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-crc32", equalTo(expectedCrc32))
            .body(equalTo(new String(body, StandardCharsets.UTF_8)));
    }

    @Test
    void presignedPutWithMismatchedSha256InQueryFailsWith400BadDigest() throws Exception {
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
            .statusCode(400)
            .body(containsString("BadDigest"))
            .body(containsString("The SHA256 checksum you specified did not match the payload."));
    }

    @Test
    void presignedUploadPartWithMismatchedChecksumQueryParamFailsWith400BadDigest() throws Exception {
        createBucket();
        String key = "multipart-checksum.txt";
        String path = "/" + BUCKET + "/" + key;

        // Khởi tạo multipart upload
        String uploadId = given()
            .filter(LOCAL_SIGNER)
        .when()
            .post(path + "?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        // Tạo presigned URL cho upload part có x-amz-checksum-crc32 sai
        String partPath = path;
        String wrongCrc32 = S3Checksum.crc32Base64(new byte[0]);
        String partUrl = presign("PUT", partPath,
                Map.of("uploadId", uploadId, "partNumber", "1", "x-amz-checksum-crc32", wrongCrc32),
                Map.of());

        given()
            .urlEncodingEnabled(false)
            .body("part data not matching empty crc32".getBytes(StandardCharsets.UTF_8))
        .when()
            .put(partUrl)
        .then()
            .statusCode(400)
            .body(containsString("BadDigest"))
            .body(containsString("The CRC32 checksum you specified did not match the payload."));
    }

    @Test
    void presignedCopyObjectWithChecksumAlgorithmInQueryComputesAndStoresChecksum() throws Exception {
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
        String expectedSha256 = S3Checksum.sha256Base64(body);
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
            .body(containsString("<ChecksumSHA256>" + expectedSha256 + "</ChecksumSHA256>"));

        given()
            .filter(LOCAL_SIGNER)
            .header("x-amz-checksum-mode", "ENABLED")
        .when()
            .head(destPath)
        .then()
            .statusCode(200)
            .header("x-amz-checksum-sha256", equalTo(expectedSha256));
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
                + sha256Hex(canonicalRequest);

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

    private static String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hexEncode(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
