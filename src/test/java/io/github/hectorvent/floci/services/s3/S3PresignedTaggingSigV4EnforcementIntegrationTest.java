package io.github.hectorvent.floci.services.s3;

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
import static org.hamcrest.Matchers.not;

/**
 * Companion to {@link S3PresignedPutTaggingIntegrationTest} for #3608, run with
 * {@code floci.services.s3.enforce-auth} on so {@link PreSignedUrlFilter} verifies the SigV4
 * signature. The URLs here are built the way SDK presigners build them: every query
 * parameter, {@code x-amz-tagging} included, is part of the canonical query string, and only
 * the headers listed in {@code X-Amz-SignedHeaders} are covered by the signature. This proves
 * a real presigned request carrying tags in the query passes verification and reaches the
 * query fallback, and that a tag parameter added after signing is rejected.
 */
@QuarkusTest
@TestProfile(S3AuthEnforcementIntegrationTest.S3AuthProfile.class)
class S3PresignedTaggingSigV4EnforcementIntegrationTest {

    private static final String BUCKET = "presigned-tagging-sigv4-bucket";
    private static final String ACCESS_KEY = "test";
    private static final String SECRET_KEY = "test";
    private static final S3RequestSigner LOCAL_SIGNER = S3RequestSigner.signedAs(ACCESS_KEY, SECRET_KEY);
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    @Test
    void presignedPutWithTaggingInSignedQueryAppliesTagsUnderEnforcedAuth() throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/tagged-via-signed-query.txt";
        // Header form is URL-encoded (c=d%20e); the presigner encodes that whole value again as
        // a query parameter, so the wire carries x-amz-tagging=a%3Db%26c%3Dd%2520e.
        String url = presign("PUT", path, Map.of("x-amz-tagging", "a=b&c=d%20e"), Map.of());

        given()
            .urlEncodingEnabled(false)
            .contentType("text/plain")
            .body("uploaded via SigV4-presigned PUT with tagging in the query string")
        .when()
            .put(url)
        .then()
            .statusCode(200);

        given()
            .filter(LOCAL_SIGNER)
        .when()
            .get(path + "?tagging")
        .then()
            .statusCode(200)
            .body(containsString("<Key>a</Key>"))
            .body(containsString("<Value>b</Value>"))
            .body(containsString("<Key>c</Key>"))
            .body(containsString("<Value>d e</Value>"));
    }

    @Test
    void presignedPutWithTaggingAppendedAfterSigningIsRejectedUnderEnforcedAuth() throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/tampered-tagging.txt";
        String url = presign("PUT", path, Map.of(), Map.of());

        given()
            .urlEncodingEnabled(false)
            .contentType("text/plain")
            .body("the tag parameter is not covered by the signature")
        .when()
            .put(url + "&x-amz-tagging=a%3Db")
        .then()
            .statusCode(403)
            .body("Error.Code", equalTo("SignatureDoesNotMatch"));
    }

    @Test
    void presignedPutWithSignedTaggingHeaderAndTaggingQueryParameterPrefersHeader() throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/tagged-via-signed-header-and-query.txt";
        String url = presign("PUT", path,
                Map.of("x-amz-tagging", "query=1"),
                Map.of("x-amz-tagging", "header=1"));

        given()
            .urlEncodingEnabled(false)
            .contentType("text/plain")
            .header("x-amz-tagging", "header=1")
            .body("the signed header wins over the query parameter")
        .when()
            .put(url)
        .then()
            .statusCode(200);

        given()
            .filter(LOCAL_SIGNER)
        .when()
            .get(path + "?tagging")
        .then()
            .statusCode(200)
            .body(containsString("<Key>header</Key>"))
            .body(containsString("<Value>1</Value>"))
            .body(not(containsString("<Key>query</Key>")));
    }

    @Test
    void presignedCreateMultipartUploadWithTaggingInSignedQueryAppliesTagsUnderEnforcedAuth()
            throws Exception {
        createBucket();
        String path = "/" + BUCKET + "/tagged-multipart-via-signed-query.txt";
        String url = presign("POST", path, Map.of("uploads", "", "x-amz-tagging", "a=b&c=d"), Map.of());

        String uploadId = given()
            .urlEncodingEnabled(false)
        .when()
            .post(url)
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        String partETag = given()
            .filter(LOCAL_SIGNER)
            .body("part-one")
        .when()
            .put(path + "?uploadId=" + uploadId + "&partNumber=1")
        .then()
            .statusCode(200)
            .extract().header("ETag");

        String completeXml = """
                <CompleteMultipartUpload>
                    <Part><PartNumber>1</PartNumber><ETag>%s</ETag></Part>
                </CompleteMultipartUpload>""".formatted(partETag);

        given()
            .filter(LOCAL_SIGNER)
            .contentType("application/xml")
            .body(completeXml)
        .when()
            .post(path + "?uploadId=" + uploadId)
        .then()
            .statusCode(200);

        given()
            .filter(LOCAL_SIGNER)
        .when()
            .get(path + "?tagging")
        .then()
            .statusCode(200)
            .body(containsString("<Key>a</Key>"))
            .body(containsString("<Value>b</Value>"))
            .body(containsString("<Key>c</Key>"))
            .body(containsString("<Value>d</Value>"));
    }

    private static void createBucket() {
        given()
            .filter(LOCAL_SIGNER)
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    /**
     * Builds a SigV4 query-string-authenticated URL like an SDK presigner: {@code queryParams}
     * holds decoded values that all become part of the canonical query string, and
     * {@code signedHeaders} lists the request headers, besides {@code host}, that the signature
     * covers. The payload is unsigned, as with every SDK-presigned S3 upload.
     */
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

    /** RFC 3986 encoding as SigV4 requires it: only unreserved characters stay literal. */
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
